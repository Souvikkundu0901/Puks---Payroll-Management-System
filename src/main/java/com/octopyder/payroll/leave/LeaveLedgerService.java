package com.octopyder.payroll.leave;

import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.leave.dto.LeaveBalanceDto;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LeaveLedgerService {

    private static final Logger log = LoggerFactory.getLogger(LeaveLedgerService.class);

    private final LeaveLedgerEntryRepository ledgerRepository;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final SettingsService settingsService;
    private final BusinessClock businessClock;

    public LeaveLedgerService(
        LeaveLedgerEntryRepository ledgerRepository,
        UserRepository userRepository,
        EmployeeProfileRepository employeeProfileRepository,
        SettingsService settingsService,
        BusinessClock businessClock
    ) {
        this.ledgerRepository = ledgerRepository;
        this.userRepository = userRepository;
        this.employeeProfileRepository = employeeProfileRepository;
        this.settingsService = settingsService;
        this.businessClock = businessClock;
    }

    @Transactional
    public void ensureCreditsUpTo(UUID userId, LocalDate today) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            return;
        }

        EmployeeProfile profile = employeeProfileRepository.findById(userId).orElse(null);
        LocalDate joiningDate = profile != null && profile.getJoiningDate() != null
            ? profile.getJoiningDate()
            : user.getCreatedAt().atZone(getZone()).toLocalDate();

        LocalDate exitDate = profile != null ? profile.getExitDate() : null;

        processCreditsAndResetForUser(userId, joiningDate, exitDate, today);
    }

    @Transactional
    public void ensureCreditsUpTo(LocalDate today) {
        List<User> activeUsers = userRepository.findAll().stream()
            .filter(u -> u.getDeletedAt() == null && u.getStatus().name().equals("ACTIVE"))
            .toList();

        for (User u : activeUsers) {
            ensureCreditsUpTo(u.getId(), today);
        }
    }

    private void processCreditsAndResetForUser(UUID userId, LocalDate joiningDate, LocalDate exitDate, LocalDate today) {
        MonthDay resetDate = parseResetDate();
        int monthlyCredit = settingsService.getInt("leave.monthlyCredit", 1);
        int maxCarryForward = settingsService.getInt("leave.carryForwardOnReset", 0);

        LocalDate effectiveEnd = exitDate != null && exitDate.isBefore(today) ? exitDate : today;
        if (joiningDate.isAfter(effectiveEnd)) {
            return;
        }

        YearMonth startYm = YearMonth.from(joiningDate);
        YearMonth endYm = YearMonth.from(effectiveEnd);

        // Process monthly credits
        for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
            LocalDate periodMonth = ym.atDay(1);
            if (!ledgerRepository.existsByUserIdAndEntryTypeAndPeriodMonth(userId, LeaveLedgerEntryType.CREDIT, periodMonth)) {
                int leaveYear = LeaveCalculator.getLeaveYear(periodMonth, resetDate);
                LeaveLedgerEntry entry = new LeaveLedgerEntry();
                entry.setUserId(userId);
                entry.setLeaveYear(leaveYear);
                entry.setEntryType(LeaveLedgerEntryType.CREDIT);
                entry.setEffectiveOn(periodMonth);
                entry.setDays(BigDecimal.valueOf(monthlyCredit));
                entry.setPeriodMonth(periodMonth);
                entry.setNote("Monthly credit for " + ym);
                entry.setCreatedAt(businessClock.instant());

                try {
                    ledgerRepository.saveAndFlush(entry);
                } catch (DataIntegrityViolationException ignored) {
                    // Concurrent insert caught by unique index
                }
            }
        }

        // Process annual resets for past leave years
        int firstYear = LeaveCalculator.getLeaveYear(joiningDate, resetDate);
        int currentYear = LeaveCalculator.getLeaveYear(today, resetDate);

        for (int y = firstYear; y < currentYear; y++) {
            if (!ledgerRepository.existsByUserIdAndEntryTypeAndLeaveYear(userId, LeaveLedgerEntryType.RESET, y)) {
                LocalDate resetDay = resetDate.atYear(y + 1);
                LocalDate lastDayOfLeaveYear = resetDay.minusDays(1);

                // Compute balance on 31 March (end of leave year) even on late run
                BigDecimal balanceOnLastDay = ledgerRepository.getBalanceAsOf(userId, lastDayOfLeaveYear);

                LeaveLedgerEntry resetEntry = new LeaveLedgerEntry();
                resetEntry.setUserId(userId);
                resetEntry.setLeaveYear(y);
                resetEntry.setEntryType(LeaveLedgerEntryType.RESET);
                resetEntry.setEffectiveOn(resetDay);
                resetEntry.setDays(balanceOnLastDay.compareTo(BigDecimal.ZERO) > 0 ? balanceOnLastDay.negate() : BigDecimal.ZERO);
                resetEntry.setNote("Annual reset for leave year " + y);
                resetEntry.setCreatedAt(businessClock.instant());

                try {
                    ledgerRepository.saveAndFlush(resetEntry);
                } catch (DataIntegrityViolationException ignored) {
                    continue;
                }

                if (maxCarryForward > 0 && balanceOnLastDay.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal carryAmount = balanceOnLastDay.min(BigDecimal.valueOf(maxCarryForward));
                    if (carryAmount.compareTo(BigDecimal.ZERO) > 0) {
                        LeaveLedgerEntry cfEntry = new LeaveLedgerEntry();
                        cfEntry.setUserId(userId);
                        cfEntry.setLeaveYear(y + 1);
                        cfEntry.setEntryType(LeaveLedgerEntryType.CARRY_FORWARD);
                        cfEntry.setEffectiveOn(resetDay);
                        cfEntry.setDays(carryAmount);
                        cfEntry.setNote("Carry forward from leave year " + y);
                        cfEntry.setCreatedAt(businessClock.instant());

                        try {
                            ledgerRepository.saveAndFlush(cfEntry);
                        } catch (DataIntegrityViolationException ignored) {}
                    }
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public LeaveBalanceDto getBalance(UUID userId, LocalDate asOfDate) {
        MonthDay resetDate = parseResetDate();
        int leaveYear = LeaveCalculator.getLeaveYear(asOfDate, resetDate);

        BigDecimal available = ledgerRepository.getBalanceAsOf(userId, asOfDate);
        BigDecimal earned = ledgerRepository.getEarnedInYear(userId, leaveYear, asOfDate);
        BigDecimal used = ledgerRepository.getUsedInYear(userId, leaveYear, asOfDate);

        BigDecimal carryForward = ledgerRepository.findByUserIdAndEntryTypeAndLeaveYear(userId, LeaveLedgerEntryType.CARRY_FORWARD, leaveYear)
            .map(LeaveLedgerEntry::getDays)
            .orElse(BigDecimal.ZERO);

        BigDecimal annualAllowance = earned.add(carryForward);

        return new LeaveBalanceDto(annualAllowance, earned, used, available, carryForward);
    }

    public MonthDay parseResetDate() {
        String resetStr = settingsService.getString("leave.resetDate", "04-01");
        try {
            return MonthDay.parse("--" + resetStr);
        } catch (Exception e) {
            return MonthDay.of(4, 1);
        }
    }

    public ZoneId getZone() {
        String tz = settingsService.getString("company.timezone", "Asia/Kolkata");
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
