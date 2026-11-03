package com.octopyder.payroll.payroll.service;

import com.octopyder.payroll.attendance.AttendanceService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.leave.LeaveService;
import com.octopyder.payroll.leave.dto.PendingLeaveItemDto;
import com.octopyder.payroll.org.HolidayService;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.payroll.calculator.PayrollInputs;
import com.octopyder.payroll.payroll.calculator.PayrollSettings;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.dto.EmployeePayrollDetails;
import com.octopyder.payroll.task.TaskService;
import com.octopyder.payroll.task.dto.TaskMonthStats;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PayrollInputsAssembler {

    private final BusinessClock businessClock;
    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final LeaveService leaveService;
    private final TaskService taskService;
    private final SettingsService settingsService;
    private final HolidayService holidayService;

    public PayrollInputsAssembler(
        BusinessClock businessClock,
        EmployeeService employeeService,
        AttendanceService attendanceService,
        LeaveService leaveService,
        TaskService taskService,
        SettingsService settingsService,
        HolidayService holidayService
    ) {
        this.businessClock = businessClock;
        this.employeeService = employeeService;
        this.attendanceService = attendanceService;
        this.leaveService = leaveService;
        this.taskService = taskService;
        this.settingsService = settingsService;
        this.holidayService = holidayService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssembledPayrollInputs assemble(UUID employeeId, String monthStr, boolean treatPendingAsPresent) {
        if (employeeId == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Employee ID is required");
        }
        if (monthStr == null || monthStr.isBlank() || !monthStr.trim().matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_INVALID_MONTH", "Invalid month format. Expected YYYY-MM: " + monthStr);
        }

        YearMonth month;
        try {
            month = YearMonth.parse(monthStr.trim());
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_INVALID_MONTH", "Invalid month format. Expected YYYY-MM: " + monthStr);
        }

        if (month.getYear() < 2000 || month.getYear() > 2100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PAYROLL_INVALID_MONTH", "Month year must be between 2000 and 2100: " + month.getYear());
        }

        LocalDate today = businessClock.today();
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        // BR-P3 Cutoff rule: Month must be ended unless allowMidMonthGeneration is enabled
        boolean allowMidMonth = settingsService.getBoolean("payroll.allowMidMonthGeneration", false);
        if (today.isBefore(monthEnd) && !allowMidMonth) {
            throw new AppException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYROLL_MONTH_NOT_ENDED",
                "Payroll can only be generated after the month has ended"
            );
        }

        LocalDate cutoffDate = today.isBefore(monthEnd) ? today : monthEnd;

        // BR-P13 Pending inputs check
        List<LocalDate> pendingAttendance = attendanceService.findPendingAttendanceInMonth(employeeId, monthStart, monthEnd);
        List<PendingLeaveItemDto> pendingLeave = leaveService.findPendingLeaveInMonth(employeeId, monthStart, monthEnd);

        if ((!pendingAttendance.isEmpty() || !pendingLeave.isEmpty()) && !treatPendingAsPresent) {
            Map<String, Object> props = new HashMap<>();
            props.put("pendingAttendance", pendingAttendance);
            props.put("pendingLeave", pendingLeave);
            throw new AppException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYROLL_PENDING_ITEMS",
                "Payroll generation blocked by pending attendance or leave items",
                props
            );
        }

        // Employee eligibility and statutory deductions
        boolean includeAdmins = settingsService.getBoolean("payroll.includeAdmins", false);
        EmployeePayrollDetails details = employeeService.getEmployeePayrollDetails(employeeId, includeAdmins);
        User user = details.user();
        EmployeeProfile profile = details.profile();

        // Task statistics with company.timezone
        String tzStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tzStr);
        } catch (Exception e) {
            zoneId = ZoneId.of("Asia/Kolkata");
        }
        TaskMonthStats taskStats = taskService.getTaskStatsForMonth(employeeId, month, cutoffDate, zoneId);

        // D27 Proration for mid-month joiners and leavers: non-employed working days count as unpaid days
        Set<DayOfWeek> weeklyOffDays = parseWeeklyOffDays();
        Set<LocalDate> holidays = new HashSet<>(holidayService.getHolidayDatesBetween(monthStart, cutoffDate));

        LocalDate joiningDate = profile.getJoiningDate();
        LocalDate exitDate = profile.getExitDate();

        if (joiningDate != null && joiningDate.isAfter(monthEnd)) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_USER_NOT_ELIGIBLE",
                "Employee joining date (" + joiningDate + ") is after payroll month (" + monthStr + ")");
        }
        if (exitDate != null && exitDate.isBefore(monthStart)) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYROLL_USER_NOT_ELIGIBLE",
                "Employee exit date (" + exitDate + ") is before payroll month (" + monthStr + ")");
        }

        int nonEmployedWorkingDays = 0;
        for (LocalDate d = monthStart; !d.isAfter(cutoffDate); d = d.plusDays(1)) {
            if (weeklyOffDays.contains(d.getDayOfWeek())) continue;
            if (holidays.contains(d)) continue;

            boolean beforeJoining = joiningDate != null && d.isBefore(joiningDate);
            boolean afterExit = exitDate != null && d.isAfter(exitDate);

            if (beforeJoining || afterExit) {
                nonEmployedWorkingDays++;
            }
        }

        // D19 Role exemptions: managers and admins get no retention and no absent-day deduction but still lose approved unpaid leave
        int absentDays;
        boolean retentionEligible;

        if (user.getRole() == Role.MANAGER || user.getRole() == Role.ADMIN) {
            absentDays = 0;
            retentionEligible = false;
        } else {
            // EMPLOYEE role: BR-A5 days before joining or after exit are ignored for attendance
            LocalDate effectiveAttStart = joiningDate != null && joiningDate.isAfter(monthStart) ? joiningDate : monthStart;
            LocalDate effectiveAttEnd = exitDate != null && exitDate.isBefore(cutoffDate) ? exitDate : cutoffDate;

            int absentWorkingDays = 0;
            if (!effectiveAttStart.isAfter(effectiveAttEnd)) {
                absentWorkingDays = attendanceService.countAbsentWorkingDays(employeeId, effectiveAttStart, effectiveAttEnd, treatPendingAsPresent);
            }
            // D27 Proration: non-employed working days count as unpaid absent days
            absentDays = absentWorkingDays + nonEmployedWorkingDays;
            retentionEligible = true;
        }

        int unpaidLeaveDays = leaveService.countUnpaidLeaveDaysInMonth(employeeId, month, cutoffDate);

        // Calculator configuration from system settings
        int divisor = settingsService.getInt("payroll.dayRateDivisor", 30);
        boolean noTasksCountsAs100 = settingsService.getBoolean("payroll.noTasksCountsAs100", true);
        PayrollSettings calcSettings = new PayrollSettings(
            divisor,
            new BigDecimal("70.00"),
            new BigDecimal("50.00"),
            2,
            7,
            noTasksCountsAs100
        );

        // Working days in month
        int totalWorkingDays = 0;
        for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
            if (!weeklyOffDays.contains(d.getDayOfWeek()) && !holidays.contains(d)) {
                totalWorkingDays++;
            }
        }
        BigDecimal presentDays = BigDecimal.valueOf(Math.max(0, totalWorkingDays - absentDays - unpaidLeaveDays));

        PayrollInputs inputs = new PayrollInputs(
            profile.getBaseSalary(),
            taskStats.totalTasks(),
            taskStats.approvedTasks(),
            absentDays,
            unpaidLeaveDays,
            details.tds(),
            details.pf(),
            details.pt(),
            retentionEligible,
            calcSettings
        );

        return new AssembledPayrollInputs(inputs, user, profile, cutoffDate, totalWorkingDays, presentDays, pendingAttendance, pendingLeave);
    }

    private Set<DayOfWeek> parseWeeklyOffDays() {
        String val = settingsService.getString("attendance.weeklyOffDays", "SAT,SUN");
        Set<DayOfWeek> days = new HashSet<>();
        if (val != null) {
            for (String part : val.split(",")) {
                String clean = part.trim().toUpperCase();
                switch (clean) {
                    case "MON", "MONDAY" -> days.add(DayOfWeek.MONDAY);
                    case "TUE", "TUESDAY" -> days.add(DayOfWeek.TUESDAY);
                    case "WED", "WEDNESDAY" -> days.add(DayOfWeek.WEDNESDAY);
                    case "THU", "THURSDAY" -> days.add(DayOfWeek.THURSDAY);
                    case "FRI", "FRIDAY" -> days.add(DayOfWeek.FRIDAY);
                    case "SAT", "SATURDAY" -> days.add(DayOfWeek.SATURDAY);
                    case "SUN", "SUNDAY" -> days.add(DayOfWeek.SUNDAY);
                    default -> {}
                }
            }
        }
        return days;
    }
}
