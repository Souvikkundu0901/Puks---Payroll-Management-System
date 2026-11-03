package com.octopyder.payroll.leave;

import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.PayrollQueryService;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.leave.dto.AdminLeaveApplyRequest;
import com.octopyder.payroll.leave.dto.LeaveApplyRequest;
import com.octopyder.payroll.leave.dto.LeaveBalanceDto;
import com.octopyder.payroll.leave.dto.LeaveBulkDecisionItemResult;
import com.octopyder.payroll.leave.dto.LeaveBulkDecisionRequest;
import com.octopyder.payroll.leave.dto.LeaveBulkDecisionResult;
import com.octopyder.payroll.leave.dto.LeaveDecisionRequest;
import com.octopyder.payroll.leave.dto.LeaveLedgerDetailsDto;
import com.octopyder.payroll.leave.dto.LeaveLedgerEntryDto;
import com.octopyder.payroll.leave.dto.LeaveRejectRequest;
import com.octopyder.payroll.leave.dto.LeaveRequestResponse;
import com.octopyder.payroll.leave.dto.LeaveSummaryDto;
import com.octopyder.payroll.leave.dto.PendingLeaveItemDto;
import com.octopyder.payroll.notification.NotificationService;
import com.octopyder.payroll.notification.NotificationType;
import com.octopyder.payroll.org.Holiday;
import com.octopyder.payroll.org.HolidayRepository;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.TeamScope;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.storage.StorageService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeaveService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveLedgerEntryRepository ledgerRepository;
    private final LeaveLedgerService leaveLedgerService;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final DepartmentRepository departmentRepository;
    private final HolidayRepository holidayRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final PayrollQueryService payrollQueryService;
    private final SettingsService settingsService;
    private final StorageService storageService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TeamScope teamScope;
    private final BusinessClock businessClock;

    public LeaveService(
        LeaveRequestRepository leaveRequestRepository,
        LeaveLedgerEntryRepository ledgerRepository,
        LeaveLedgerService leaveLedgerService,
        UserRepository userRepository,
        EmployeeProfileRepository employeeProfileRepository,
        DepartmentRepository departmentRepository,
        HolidayRepository holidayRepository,
        AttendanceRecordRepository attendanceRecordRepository,
        PayrollQueryService payrollQueryService,
        SettingsService settingsService,
        StorageService storageService,
        AuditService auditService,
        NotificationService notificationService,
        TeamScope teamScope,
        BusinessClock businessClock
    ) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.ledgerRepository = ledgerRepository;
        this.leaveLedgerService = leaveLedgerService;
        this.userRepository = userRepository;
        this.employeeProfileRepository = employeeProfileRepository;
        this.departmentRepository = departmentRepository;
        this.holidayRepository = holidayRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.payrollQueryService = payrollQueryService;
        this.settingsService = settingsService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.teamScope = teamScope;
        this.businessClock = businessClock;
    }

    @Transactional
    public LeaveBalanceDto getMyBalance(CurrentUser currentUser) {
        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(currentUser.id(), today);
        return leaveLedgerService.getBalance(currentUser.id(), today);
    }

    @Transactional
    public List<LeaveLedgerEntryDto> getMyLedger(CurrentUser currentUser) {
        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(currentUser.id(), today);
        return ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(currentUser.id()).stream()
            .map(this::toLedgerDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> getMyRequests(CurrentUser currentUser) {
        return leaveRequestRepository.findByUserIdOrderByCreatedAtDesc(currentUser.id()).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public LeaveRequestResponse applyLeave(CurrentUser currentUser, LeaveApplyRequest request, MultipartFile attachment) {
        return processLeaveApplication(currentUser.id(), request.type(), request.startDate(), request.endDate(), request.reason(), attachment, false);
    }

    @Transactional
    public LeaveRequestResponse applyLeaveOnBehalf(CurrentUser currentUser, AdminLeaveApplyRequest request, MultipartFile attachment) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only administrators can apply leave on behalf of employees");
        }
        return processLeaveApplication(request.userId(), request.type(), request.startDate(), request.endDate(), request.reason(), attachment, true);
    }

    private LeaveRequestResponse processLeaveApplication(
        UUID targetUserId,
        LeaveType type,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        MultipartFile attachment,
        boolean isAdminOnBehalf
    ) {
        LocalDate today = getToday();
        MonthDay resetDate = leaveLedgerService.parseResetDate();

        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LEAVE_INVALID_RANGE", "Start date must be on or before end date");
        }

        // Cross-leave-year boundary check
        if (LeaveCalculator.crossesLeaveYearBoundary(startDate, endDate, resetDate)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LEAVE_CROSSES_YEAR_BOUNDARY", "Leave request cannot span across the annual reset date. Please submit separate requests for each leave year.");
        }

        // Lock user row for concurrency
        User user = userRepository.findByIdForUpdate(targetUserId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        if (user.getDeletedAt() != null) {
            throw new AppException(HttpStatus.NOT_FOUND, "USER_DEACTIVATED", "User not found or has been deactivated");
        }

        EmployeeProfile profile = employeeProfileRepository.findById(targetUserId).orElse(null);
        if (profile != null && profile.getExitDate() != null) {
            if (!startDate.isBefore(profile.getExitDate())) {
                throw new AppException(HttpStatus.BAD_REQUEST, "LEAVE_AFTER_EXIT_DATE", "Leave start date cannot be on or after employee exit date");
            }
        }
        LocalDate joiningDate = profile != null && profile.getJoiningDate() != null
            ? profile.getJoiningDate()
            : user.getCreatedAt().atZone(getZone()).toLocalDate();

        if (startDate.isBefore(joiningDate)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LEAVE_BEFORE_JOINING_DATE", "Leave start date cannot be before joining date");
        }

        // Payroll check: cannot apply for month with generated payroll
        for (YearMonth ym = YearMonth.from(startDate); !ym.isAfter(YearMonth.from(endDate)); ym = ym.plusMonths(1)) {
            if (payrollQueryService.existsPayrollForUserAndMonth(targetUserId, ym)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_EXISTS", "Cannot apply for leave in a month with generated payroll");
            }
        }

        // Overlap check
        List<LeaveRequest> overlapping = leaveRequestRepository.findOverlapping(
            targetUserId, startDate, endDate, List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED)
        );
        if (!overlapping.isEmpty()) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_OVERLAP", "Leave request overlaps with an existing pending or approved request");
        }

        // Attendance conflict check: refuse leave over dates that have attendance
        List<AttendanceRecord> attendanceRecords = attendanceRecordRepository.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(targetUserId, startDate, endDate);
        boolean hasAttendance = attendanceRecords.stream().anyMatch(a -> a.getApprovalStatus() != AttendanceApprovalStatus.REJECTED);
        if (hasAttendance) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_ATTENDANCE_CONFLICT", "Leave dates conflict with existing attendance records");
        }

        // Working day calculation
        Set<DayOfWeek> weeklyOffDays = parseWeeklyOffDays();
        Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(startDate, endDate).stream()
            .map(Holiday::getHolidayDate)
            .collect(Collectors.toSet());

        int workingDays = LeaveCalculator.calculateWorkingDays(startDate, endDate, weeklyOffDays, holidays);
        if (workingDays <= 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "LEAVE_INVALID_RANGE", "Selected range has 0 working days");
        }

        // Store attachment if present (with orphan cleanup)
        String attachmentKey = null;
        if (attachment != null && !attachment.isEmpty()) {
            try (InputStream in = attachment.getInputStream()) {
                attachmentKey = storageService.store(attachment.getOriginalFilename(), in, attachment.getContentType(), attachment.getSize());
            } catch (Exception e) {
                if (e instanceof AppException ae) throw ae;
                throw new AppException(HttpStatus.BAD_REQUEST, "STORAGE_ERROR", "Failed to upload attachment: " + e.getMessage());
            }
        }

        try {
            // Per-user catch-up
            leaveLedgerService.ensureCreditsUpTo(targetUserId, today);

            BigDecimal days = BigDecimal.valueOf(workingDays);
            BigDecimal available = ledgerRepository.getBalanceAsOf(targetUserId, startDate);
            boolean insufficient = (type != LeaveType.UNPAID && days.compareTo(available) > 0);

            boolean managerApproval = settingsService.getBoolean("leave.managerApproval", true);

            LeaveRequest leaveRequest = new LeaveRequest();
            leaveRequest.setUserId(targetUserId);
            leaveRequest.setLeaveType(type);
            leaveRequest.setStartDate(startDate);
            leaveRequest.setEndDate(endDate);
            leaveRequest.setDays(days);
            leaveRequest.setReason(reason);
            leaveRequest.setAttachmentKey(attachmentKey);
            leaveRequest.setManagerId(user.getManagerId());
            leaveRequest.setCreatedAt(businessClock.instant());
            leaveRequest.setUpdatedAt(businessClock.instant());

            if (!managerApproval && !insufficient) {
                // Auto-approve debits when balance covers it
                leaveRequest.setStatus(LeaveStatus.APPROVED);
                leaveRequest.setDecidedAt(businessClock.instant());
                leaveRequest.setInsufficientBalance(false);

                if (type == LeaveType.UNPAID) {
                    leaveRequest.setDebitedDays(BigDecimal.ZERO);
                } else {
                    leaveRequest.setDebitedDays(days);
                    LeaveLedgerEntry debitEntry = new LeaveLedgerEntry();
                    debitEntry.setUserId(targetUserId);
                    debitEntry.setLeaveYear(LeaveCalculator.getLeaveYear(startDate, resetDate));
                    debitEntry.setEntryType(LeaveLedgerEntryType.DEBIT);
                    debitEntry.setEffectiveOn(startDate);
                    debitEntry.setDays(days.negate());
                    debitEntry.setNote("Leave debit for " + startDate + " to " + endDate);
                    debitEntry.setCreatedAt(businessClock.instant());
                    debitEntry = ledgerRepository.save(debitEntry);
                    leaveRequest.setDebitedDays(days);
                }
            } else {
                leaveRequest.setStatus(LeaveStatus.PENDING);
                leaveRequest.setInsufficientBalance(insufficient);
            }

            LeaveRequest saved = leaveRequestRepository.save(leaveRequest);

            if (saved.getStatus() == LeaveStatus.APPROVED && saved.getDebitedDays().compareTo(BigDecimal.ZERO) > 0) {
                // Associate debit entry with request ID
                List<LeaveLedgerEntry> entries = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(targetUserId);
                if (!entries.isEmpty() && entries.get(0).getEntryType() == LeaveLedgerEntryType.DEBIT) {
                    entries.get(0).setRequestId(saved.getId());
                    ledgerRepository.save(entries.get(0));
                }
            }

            // Audit without private reason or attachment
            auditService.log(
                isAdminOnBehalf ? "LEAVE_ADMIN_APPLY" : "LEAVE_APPLY",
                "LEAVE",
                saved.getId().toString(),
                null,
                "{\"userId\":\"" + targetUserId + "\",\"type\":\"" + type + "\",\"days\":" + days + ",\"status\":\"" + saved.getStatus() + "\"}"
            );

            // Notify manager if pending
            if (saved.getStatus() == LeaveStatus.PENDING && user.getManagerId() != null) {
                notificationService.createNotification(
                    user.getManagerId(),
                    NotificationType.LEAVE_REQUEST,
                    "New Leave Request",
                    user.getEmail() + " requested " + workingDays + " day(s) " + type + " leave",
                    "/manager/review-leave.html"
                );
            }

            return toResponse(saved);
        } catch (Exception ex) {
            if (attachmentKey != null) {
                try {
                    storageService.delete(attachmentKey);
                } catch (Exception ignored) {}
            }
            throw ex;
        }
    }

    @Transactional
    public LeaveRequestResponse cancelLeave(CurrentUser currentUser, UUID id) {
        LeaveRequest req = leaveRequestRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Leave request not found"));

        if (!currentUser.isAdmin() && !currentUser.id().equals(req.getUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Can only cancel your own leave requests");
        }

        if (req.getStatus() != LeaveStatus.PENDING && req.getStatus() != LeaveStatus.APPROVED) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_INVALID_TRANSITION", "Cannot cancel leave with status " + req.getStatus());
        }

        LocalDate today = getToday();
        if (req.getStatus() == LeaveStatus.APPROVED && !req.getStartDate().isAfter(today)) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_CANNOT_CANCEL_PAST", "Cannot cancel past or ongoing approved leave");
        }

        // Lock user row
        userRepository.findByIdForUpdate(req.getUserId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        // Check payroll
        for (YearMonth ym = YearMonth.from(req.getStartDate()); !ym.isAfter(YearMonth.from(req.getEndDate())); ym = ym.plusMonths(1)) {
            if (payrollQueryService.existsPayrollForUserAndMonth(req.getUserId(), ym)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_EXISTS", "Cannot cancel leave for a month with generated payroll");
            }
        }

        MonthDay resetDate = leaveLedgerService.parseResetDate();
        if (req.getStatus() == LeaveStatus.APPROVED && req.getDebitedDays().compareTo(BigDecimal.ZERO) > 0) {
            LeaveLedgerEntry revEntry = new LeaveLedgerEntry();
            revEntry.setUserId(req.getUserId());
            revEntry.setLeaveYear(LeaveCalculator.getLeaveYear(req.getStartDate(), resetDate));
            revEntry.setEntryType(LeaveLedgerEntryType.REVERSAL);
            revEntry.setEffectiveOn(today);
            revEntry.setDays(req.getDebitedDays());
            revEntry.setRequestId(req.getId());
            revEntry.setNote("Reversal for cancelled leave " + req.getId());
            revEntry.setCreatedAt(businessClock.instant());
            ledgerRepository.save(revEntry);
        }

        req.setStatus(LeaveStatus.CANCELLED);
        req.setUpdatedAt(businessClock.instant());
        LeaveRequest saved = leaveRequestRepository.save(req);

        auditService.log(
            "LEAVE_CANCEL",
            "LEAVE",
            saved.getId().toString(),
            "{\"status\":\"" + req.getStatus() + "\"}",
            "{\"status\":\"CANCELLED\"}"
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestResponse> listLeaveRequests(
        CurrentUser currentUser,
        LeaveStatus status,
        LeaveType type,
        UUID departmentId,
        String month,
        UUID employeeId,
        int page,
        int size,
        String sort
    ) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot access team leave lists");
        }

        int pageSize = Math.min(Math.max(1, size), 100);

        List<UUID> allowedUserIds = null;
        if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            allowedUserIds = team.stream().map(User::getId).toList();
            if (employeeId != null) {
                if (!allowedUserIds.contains(employeeId)) {
                    throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Cannot access leave outside your team");
                }
                allowedUserIds = List.of(employeeId);
            }
        } else if (employeeId != null) {
            allowedUserIds = List.of(employeeId);
        }

        if (departmentId != null) {
            List<UUID> deptUserIds = employeeProfileRepository.findByDepartmentId(departmentId).stream()
                .map(EmployeeProfile::getId)
                .toList();
            if (allowedUserIds == null) {
                allowedUserIds = deptUserIds;
            } else {
                allowedUserIds = allowedUserIds.stream().filter(deptUserIds::contains).toList();
            }
        }

        LocalDate fromMonth = null;
        LocalDate toMonth = null;
        if (month != null && !month.isBlank()) {
            YearMonth ym = YearMonth.parse(month.trim());
            fromMonth = ym.atDay(1);
            toMonth = ym.atEndOfMonth();
        }

        final List<UUID> finalUserIds = allowedUserIds;
        final LocalDate finalFrom = fromMonth;
        final LocalDate finalTo = toMonth;

        Specification<LeaveRequest> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (finalUserIds != null) {
                if (finalUserIds.isEmpty()) predicates.add(cb.disjunction());
                else predicates.add(root.get("userId").in(finalUserIds));
            }
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (type != null) predicates.add(cb.equal(root.get("leaveType"), type));
            if (finalFrom != null && finalTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), finalTo));
                predicates.add(cb.greaterThanOrEqualTo(root.get("endDate"), finalFrom));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Sort sortOrder = Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(page, pageSize, sortOrder);

        Page<LeaveRequest> pageResult = leaveRequestRepository.findAll(spec, pageable);
        List<LeaveRequestResponse> items = pageResult.getContent().stream().map(this::toResponse).toList();

        return new PageResponse<>(
            items,
            pageResult.getNumber(),
            pageResult.getSize(),
            pageResult.getTotalElements(),
            pageResult.getTotalPages()
        );
    }

    @Transactional
    public LeaveRequestResponse approveLeave(CurrentUser currentUser, UUID id, LeaveDecisionRequest request) {
        LeaveRequest req = leaveRequestRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Leave request not found"));

        if (currentUser.id().equals(req.getUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot approve your own leave request");
        }

        if (currentUser.isManager()) {
            User targetUser = userRepository.findById(req.getUserId()).orElse(null);
            if (targetUser == null || targetUser.getDeletedAt() != null || !currentUser.id().equals(targetUser.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers can only decide leave for their own team");
            }
        }

        if (req.getStatus() != LeaveStatus.PENDING) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_ALREADY_DECIDED", "Leave request is already " + req.getStatus());
        }

        // Lock user row
        userRepository.findByIdForUpdate(req.getUserId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        // Check payroll
        for (YearMonth ym = YearMonth.from(req.getStartDate()); !ym.isAfter(YearMonth.from(req.getEndDate())); ym = ym.plusMonths(1)) {
            if (payrollQueryService.existsPayrollForUserAndMonth(req.getUserId(), ym)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_EXISTS", "Cannot approve leave for a month with generated payroll");
            }
        }

        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(req.getUserId(), today);

        BigDecimal available = ledgerRepository.getBalanceAsOf(req.getUserId(), req.getStartDate());
        boolean asUnpaid = Boolean.TRUE.equals(request != null ? request.asUnpaid() : null);

        if (req.getLeaveType() != LeaveType.UNPAID && req.getDays().compareTo(available) > 0) {
            if (!asUnpaid) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "LEAVE_INSUFFICIENT_BALANCE", "Insufficient leave balance; can only be approved as UNPAID");
            }
        }

        MonthDay resetDate = leaveLedgerService.parseResetDate();
        if (asUnpaid || req.getLeaveType() == LeaveType.UNPAID) {
            req.setLeaveType(LeaveType.UNPAID);
            req.setDebitedDays(BigDecimal.ZERO);
        } else {
            req.setDebitedDays(req.getDays());
            LeaveLedgerEntry debitEntry = new LeaveLedgerEntry();
            debitEntry.setUserId(req.getUserId());
            debitEntry.setLeaveYear(LeaveCalculator.getLeaveYear(req.getStartDate(), resetDate));
            debitEntry.setEntryType(LeaveLedgerEntryType.DEBIT);
            debitEntry.setEffectiveOn(req.getStartDate());
            debitEntry.setDays(req.getDays().negate());
            debitEntry.setRequestId(req.getId());
            debitEntry.setNote("Leave debit for " + req.getStartDate() + " to " + req.getEndDate());
            debitEntry.setCreatedAt(businessClock.instant());
            ledgerRepository.save(debitEntry);
        }

        req.setStatus(LeaveStatus.APPROVED);
        req.setDecidedBy(currentUser.id());
        req.setDecidedAt(businessClock.instant());
        req.setDecisionNote(request != null && request.note() != null ? request.note().trim() : null);
        req.setUpdatedAt(businessClock.instant());

        LeaveRequest saved = leaveRequestRepository.save(req);

        auditService.log(
            "LEAVE_APPROVE",
            "LEAVE",
            saved.getId().toString(),
            "{\"status\":\"PENDING\"}",
            "{\"status\":\"APPROVED\",\"type\":\"" + saved.getLeaveType() + "\",\"debitedDays\":" + saved.getDebitedDays() + "}"
        );

        notificationService.createNotification(
            saved.getUserId(),
            NotificationType.LEAVE_APPROVED,
            "Leave Approved",
            "Your leave request for " + saved.getStartDate() + " to " + saved.getEndDate() + " was approved",
            "/employee/leave.html"
        );

        return toResponse(saved);
    }

    @Transactional
    public LeaveRequestResponse rejectLeave(CurrentUser currentUser, UUID id, LeaveRejectRequest request) {
        LeaveRequest req = leaveRequestRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Leave request not found"));

        if (currentUser.id().equals(req.getUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot reject your own leave request");
        }

        if (currentUser.isManager()) {
            User targetUser = userRepository.findById(req.getUserId()).orElse(null);
            if (targetUser == null || targetUser.getDeletedAt() != null || !currentUser.id().equals(targetUser.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers can only decide leave for their own team");
            }
        }

        if (req.getStatus() != LeaveStatus.PENDING) {
            throw new AppException(HttpStatus.CONFLICT, "LEAVE_ALREADY_DECIDED", "Leave request is already " + req.getStatus());
        }

        // Check payroll
        for (YearMonth ym = YearMonth.from(req.getStartDate()); !ym.isAfter(YearMonth.from(req.getEndDate())); ym = ym.plusMonths(1)) {
            if (payrollQueryService.existsPayrollForUserAndMonth(req.getUserId(), ym)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_EXISTS", "Cannot reject leave for a month with generated payroll");
            }
        }

        req.setStatus(LeaveStatus.REJECTED);
        req.setDecidedBy(currentUser.id());
        req.setDecidedAt(businessClock.instant());
        req.setDecisionNote(request != null && request.note() != null ? request.note().trim() : null);
        req.setUpdatedAt(businessClock.instant());

        LeaveRequest saved = leaveRequestRepository.save(req);

        auditService.log(
            "LEAVE_REJECT",
            "LEAVE",
            saved.getId().toString(),
            "{\"status\":\"PENDING\"}",
            "{\"status\":\"REJECTED\"}"
        );

        notificationService.createNotification(
            saved.getUserId(),
            NotificationType.LEAVE_REJECTED,
            "Leave Rejected",
            "Your leave request for " + saved.getStartDate() + " to " + saved.getEndDate() + " was rejected: " + req.getDecisionNote(),
            "/employee/leave.html"
        );

        return toResponse(saved);
    }

    @Transactional
    public LeaveBulkDecisionResult bulkDecision(CurrentUser currentUser, LeaveBulkDecisionRequest request) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot perform bulk leave decisions");
        }

        if (request.ids() == null || request.ids().isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "IDs list cannot be empty");
        }
        if (request.ids().size() > 200) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Maximum 200 items per bulk decision");
        }

        String decision = request.decision().trim().toUpperCase();
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Decision must be APPROVE or REJECT");
        }

        List<LeaveBulkDecisionItemResult> itemResults = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        List<UUID> managerTeamIds = null;
        if (currentUser.isManager()) {
            managerTeamIds = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id()).stream().map(User::getId).toList();
        }

        for (UUID id : request.ids()) {
            Optional<LeaveRequest> opt = leaveRequestRepository.findById(id);
            if (opt.isEmpty()) {
                failureCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, false, "FAILED", "Leave request not found"));
                continue;
            }

            LeaveRequest req = opt.get();

            // Self-decision check
            if (currentUser.id().equals(req.getUserId())) {
                failureCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, false, "FAILED", "Cannot decide your own leave request"));
                continue;
            }

            // Scope check: uniform error without existence leak
            if (currentUser.isManager() && (managerTeamIds == null || !managerTeamIds.contains(req.getUserId()))) {
                failureCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, false, "FAILED", "Leave request not found"));
                continue;
            }

            if (req.getStatus() != LeaveStatus.PENDING) {
                failureCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, false, "FAILED", "Record is already " + req.getStatus()));
                continue;
            }

            try {
                if (decision.equals("APPROVE")) {
                    approveLeave(currentUser, id, new LeaveDecisionRequest(request.asUnpaid(), request.note()));
                } else {
                    rejectLeave(currentUser, id, new LeaveRejectRequest(request.note() != null ? request.note() : "Bulk rejection"));
                }
                successCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, true, decision, "Processed successfully"));
            } catch (Exception e) {
                failureCount++;
                itemResults.add(new LeaveBulkDecisionItemResult(id, false, "FAILED", e.getMessage()));
            }
        }

        return new LeaveBulkDecisionResult(request.ids().size(), successCount, failureCount, itemResults);
    }

    @Transactional
    public LeaveLedgerDetailsDto getEmployeeLedger(CurrentUser currentUser, UUID userId) {
        teamScope.assertCanAccess(currentUser, userId);
        LocalDate today = getToday();
        leaveLedgerService.ensureCreditsUpTo(userId, today);
        LeaveBalanceDto balance = leaveLedgerService.getBalance(userId, today);
        List<LeaveLedgerEntryDto> entries = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(userId).stream()
            .map(this::toLedgerDto)
            .toList();
        return new LeaveLedgerDetailsDto(balance, entries);
    }

    @Transactional(readOnly = true)
    public LeaveSummaryDto getSummary(CurrentUser currentUser) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot access leave summary");
        }

        List<LeaveRequest> requests;
        if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            List<UUID> teamIds = team.stream().map(User::getId).toList();
            requests = teamIds.isEmpty() ? List.of() : leaveRequestRepository.findAll((root, query, cb) -> root.get("userId").in(teamIds));
        } else {
            requests = leaveRequestRepository.findAll();
        }

        LocalDate today = getToday();
        int total = requests.size();
        int pending = 0;
        int approved = 0;
        int rejected = 0;
        int onLeaveToday = 0;

        for (LeaveRequest r : requests) {
            if (r.getStatus() == LeaveStatus.PENDING) pending++;
            else if (r.getStatus() == LeaveStatus.APPROVED) {
                approved++;
                if (!today.isBefore(r.getStartDate()) && !today.isAfter(r.getEndDate())) {
                    onLeaveToday++;
                }
            } else if (r.getStatus() == LeaveStatus.REJECTED) rejected++;
        }

        return new LeaveSummaryDto(total, pending, approved, rejected, onLeaveToday);
    }

    @Transactional(readOnly = true)
    public Resource loadAttachment(CurrentUser currentUser, UUID id) {
        LeaveRequest req = leaveRequestRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Leave request not found"));

        teamScope.assertCanAccess(currentUser, req.getUserId());

        if (req.getAttachmentKey() == null || req.getAttachmentKey().isBlank()) {
            throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found");
        }

        InputStream stream = storageService.load(req.getAttachmentKey());
        return new InputStreamResource(stream);
    }

    @Transactional(readOnly = true)
    public String exportLeaveCsv(CurrentUser currentUser, LeaveStatus status, LeaveType type, UUID departmentId, String month) {
        PageResponse<LeaveRequestResponse> page = listLeaveRequests(currentUser, status, type, departmentId, month, null, 0, 100, null);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("EmployeeCode,EmployeeName,Department,LeaveType,StartDate,EndDate,Days,DebitedDays,Status,Manager,CreatedAt");

        for (LeaveRequestResponse r : page.items()) {
            pw.printf(
                "%s,%s,%s,%s,%s,%s,%.1f,%.1f,%s,%s,%s%n",
                escapeCsv(r.userCode()),
                escapeCsv(r.userName()),
                escapeCsv(r.departmentName()),
                r.leaveType(),
                r.startDate(),
                r.endDate(),
                r.days(),
                r.debitedDays(),
                r.status(),
                escapeCsv(r.managerName()),
                r.createdAt()
            );
        }

        auditService.log("LEAVE_EXPORT", "LEAVE", null, null, "Exported " + page.items().size() + " records");
        return sw.toString();
    }

    @Transactional(readOnly = true)
    public int countUnpaidLeaveDaysInMonth(UUID userId, YearMonth month, LocalDate cutoffDate) {
        if (userId == null || month == null) return 0;

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        List<LeaveRequest> list = leaveRequestRepository.findApprovedInDateRange(userId, monthStart, monthEnd).stream()
            .filter(r -> r.getLeaveType() == LeaveType.UNPAID)
            .toList();

        Set<DayOfWeek> weeklyOffDays = parseWeeklyOffDays();
        Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(monthStart, monthEnd).stream()
            .map(Holiday::getHolidayDate)
            .collect(Collectors.toSet());

        int total = 0;
        for (LeaveRequest r : list) {
            total += LeaveCalculator.countUnpaidLeaveDaysInPeriod(
                r.getStartDate(), r.getEndDate(), month, cutoffDate, weeklyOffDays, holidays
            );
        }
        return total;
    }

    public List<PendingLeaveItemDto> findPendingLeaveInMonth(UUID userId, LocalDate from, LocalDate to) {
        if (userId == null || from == null || to == null) return List.of();
        List<LeaveRequest> list = leaveRequestRepository.findOverlapping(
            userId, from, to, List.of(LeaveStatus.PENDING)
        );
        return list.stream()
            .map(r -> new PendingLeaveItemDto(r.getId(), r.getStartDate(), r.getEndDate(), r.getLeaveType().name()))
            .toList();
    }

    // --- Helpers ---

    private LeaveRequestResponse toResponse(LeaveRequest req) {
        String userCode = null;
        String userName = null;
        String deptName = null;
        Optional<User> uOpt = userRepository.findById(req.getUserId());
        if (uOpt.isPresent()) {
            User u = uOpt.get();
            userCode = u.getCode();
            userName = u.getEmail();
            EmployeeProfile ep = employeeProfileRepository.findById(u.getId()).orElse(null);
            if (ep != null && ep.getDepartmentId() != null) {
                deptName = departmentRepository.findById(ep.getDepartmentId())
                    .map(Department::getName)
                    .orElse(null);
            }
        }

        String managerName = null;
        if (req.getManagerId() != null) {
            managerName = userRepository.findById(req.getManagerId()).map(User::getEmail).orElse(null);
        }

        String decidedByName = null;
        if (req.getDecidedBy() != null) {
            decidedByName = userRepository.findById(req.getDecidedBy()).map(User::getEmail).orElse(null);
        }

        return new LeaveRequestResponse(
            req.getId(),
            req.getUserId(),
            userCode,
            userName,
            deptName,
            req.getLeaveType(),
            req.getStartDate(),
            req.getEndDate(),
            req.getDays(),
            req.getDebitedDays(),
            req.getReason(),
            req.getAttachmentKey(),
            req.getStatus(),
            req.isInsufficientBalance(),
            req.getManagerId(),
            managerName,
            req.getDecidedBy(),
            decidedByName,
            req.getDecidedAt(),
            req.getDecisionNote(),
            req.getCreatedAt()
        );
    }

    private LeaveLedgerEntryDto toLedgerDto(LeaveLedgerEntry entry) {
        return new LeaveLedgerEntryDto(
            entry.getId(),
            entry.getUserId(),
            entry.getLeaveYear(),
            entry.getEntryType(),
            entry.getEffectiveOn(),
            entry.getDays(),
            entry.getPeriodMonth(),
            entry.getRequestId(),
            entry.getNote(),
            entry.getCreatedAt()
        );
    }

    private Set<DayOfWeek> parseWeeklyOffDays() {
        String daysStr = settingsService.getString("attendance.weeklyOffDays", "SAT,SUN");
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        if (daysStr != null) {
            for (String part : daysStr.split(",")) {
                String trimmed = part.trim().toUpperCase();
                switch (trimmed) {
                    case "MON", "MONDAY" -> set.add(DayOfWeek.MONDAY);
                    case "TUE", "TUESDAY" -> set.add(DayOfWeek.TUESDAY);
                    case "WED", "WEDNESDAY" -> set.add(DayOfWeek.WEDNESDAY);
                    case "THU", "THURSDAY" -> set.add(DayOfWeek.THURSDAY);
                    case "FRI", "FRIDAY" -> set.add(DayOfWeek.FRIDAY);
                    case "SAT", "SATURDAY" -> set.add(DayOfWeek.SATURDAY);
                    case "SUN", "SUNDAY" -> set.add(DayOfWeek.SUNDAY);
                }
            }
        }
        return set;
    }

    private LocalDate getToday() {
        return businessClock.instant().atZone(getZone()).toLocalDate();
    }

    private ZoneId getZone() {
        String tz = settingsService.getString("company.timezone", "Asia/Kolkata");
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
