package com.octopyder.payroll.attendance;

import com.octopyder.payroll.attendance.dto.AttendanceBulkDecisionRequest;
import com.octopyder.payroll.attendance.dto.AttendanceRejectRequest;
import com.octopyder.payroll.attendance.dto.AttendanceResponse;
import com.octopyder.payroll.attendance.dto.AttendanceSummaryDto;
import com.octopyder.payroll.attendance.dto.AttendanceTodayDto;
import com.octopyder.payroll.attendance.dto.BulkDecisionItemResult;
import com.octopyder.payroll.attendance.dto.BulkDecisionResult;
import com.octopyder.payroll.attendance.dto.CalendarDayDto;
import com.octopyder.payroll.attendance.dto.CheckInRequest;
import com.octopyder.payroll.attendance.dto.CheckOutRequest;
import com.octopyder.payroll.attendance.dto.MonthAttendanceSummaryDto;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.notification.NotificationService;
import com.octopyder.payroll.notification.NotificationType;
import com.octopyder.payroll.org.HolidayRepository;
import com.octopyder.payroll.org.OfficeLocation;
import com.octopyder.payroll.org.OfficeLocationRepository;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.TeamScope;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private static final Set<String> ALLOWED_DEVICE_PLATFORMS = Set.of(
        "WEB", "IOS", "ANDROID", "DESKTOP", "OTHER"
    );

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final GeofenceService geofenceService;
    private final SettingsService settingsService;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final DepartmentRepository departmentRepository;
    private final OfficeLocationRepository officeLocationRepository;
    private final HolidayRepository holidayRepository;
    private final LeaveQueryService leaveQueryService;
    private final PayrollQueryService payrollQueryService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TeamScope teamScope;
    private final BusinessClock businessClock;
    private final boolean appTrustedProxy;

    public AttendanceService(
        AttendanceRecordRepository attendanceRecordRepository,
        GeofenceService geofenceService,
        SettingsService settingsService,
        UserRepository userRepository,
        EmployeeProfileRepository employeeProfileRepository,
        DepartmentRepository departmentRepository,
        OfficeLocationRepository officeLocationRepository,
        HolidayRepository holidayRepository,
        LeaveQueryService leaveQueryService,
        PayrollQueryService payrollQueryService,
        AuditService auditService,
        NotificationService notificationService,
        TeamScope teamScope,
        BusinessClock businessClock,
        @Value("${app.trusted-proxy:false}") boolean appTrustedProxy
    ) {
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.geofenceService = geofenceService;
        this.settingsService = settingsService;
        this.userRepository = userRepository;
        this.employeeProfileRepository = employeeProfileRepository;
        this.departmentRepository = departmentRepository;
        this.officeLocationRepository = officeLocationRepository;
        this.holidayRepository = holidayRepository;
        this.leaveQueryService = leaveQueryService;
        this.payrollQueryService = payrollQueryService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.teamScope = teamScope;
        this.businessClock = businessClock;
        this.appTrustedProxy = appTrustedProxy;
    }

    @Transactional
    public AttendanceResponse checkIn(CurrentUser currentUser, CheckInRequest request, HttpServletRequest httpRequest) {
        // Validate coordinates
        if (request.latitude() != null) {
            double lat = request.latitude().doubleValue();
            if (lat < -90.0 || lat > 90.0) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Latitude must be between -90 and 90");
            }
        }
        if (request.longitude() != null) {
            double lon = request.longitude().doubleValue();
            if (lon < -180.0 || lon > 180.0) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Longitude must be between -180 and 180");
            }
        }

        // Settings
        boolean gpsVerification = settingsService.getBoolean("attendance.gpsVerification", true);
        boolean remoteAllowed = settingsService.getBoolean("attendance.remoteAllowed", true);
        boolean managerApproval = settingsService.getBoolean("attendance.managerApproval", true);
        String officeStartTimeStr = settingsService.getString("attendance.officeStart", "09:00");
        LocalTime officeStart = LocalTime.parse(officeStartTimeStr);
        int graceMinutes = settingsService.getInt("attendance.graceMinutes", 15);
        int lowAccuracyM = settingsService.getInt("attendance.lowAccuracyM", 200);
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);

        if (gpsVerification && (request.latitude() == null || request.longitude() == null)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "ATTENDANCE_GPS_REQUIRED", "GPS coordinates are required for attendance");
        }

        // Extract client IP and user agent server-side
        String clientIp = resolveClientIp(httpRequest);
        String userAgent = truncate(httpRequest.getHeader("User-Agent"), 500);

        // Allowlist devicePlatform
        String platform = "OTHER";
        if (request.devicePlatform() != null && !request.devicePlatform().isBlank()) {
            String norm = request.devicePlatform().trim().toUpperCase();
            if (ALLOWED_DEVICE_PLATFORMS.contains(norm)) {
                platform = norm;
            }
        }

        Instant now = businessClock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalTime localTime = now.atZone(zone).toLocalTime();

        // Check if already checked in today
        if (attendanceRecordRepository.findByUserIdAndWorkDate(currentUser.id(), today).isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_CHECKED_IN", "Attendance already marked for today");
        }

        // Evaluate geofence & work mode
        GeofenceService.GeofenceMatchResult match = geofenceService.evaluate(request.latitude(), request.longitude(), clientIp);
        if (!remoteAllowed && match.workMode() != AttendanceWorkMode.OFFICE) {
            throw new AppException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ATTENDANCE_REMOTE_NOT_ALLOWED",
                "Remote attendance is not allowed. Check-in must be within an approved office geofence."
            );
        }

        boolean isLate = AttendanceCalculator.isLate(localTime, officeStart, graceMinutes);
        boolean isLowAccuracy = request.accuracyM() != null && request.accuracyM().doubleValue() > lowAccuracyM;
        AttendanceApprovalStatus status = managerApproval ? AttendanceApprovalStatus.PENDING : AttendanceApprovalStatus.APPROVED;

        AttendanceRecord record = new AttendanceRecord();
        record.setUserId(currentUser.id());
        record.setWorkDate(today);
        record.setCheckInAt(now);
        record.setWorkMode(match.workMode());
        record.setDayType(AttendanceDayType.PRESENT);
        record.setLate(isLate);
        record.setApprovalStatus(status);
        if (status == AttendanceApprovalStatus.APPROVED) {
            record.setApprovedAt(now);
        }
        record.setLatitude(request.latitude());
        record.setLongitude(request.longitude());
        record.setAccuracyM(request.accuracyM());
        record.setLowAccuracy(isLowAccuracy);
        record.setDevicePlatform(platform);
        record.setUserAgent(userAgent);
        record.setIpAddress(clientIp);
        record.setOfficeLocationId(match.officeLocationId());
        record.setDistanceM(match.distanceM());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        try {
            record = attendanceRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_CHECKED_IN", "Attendance already marked for today");
        }

        auditService.log(
            "ATTENDANCE_CHECK_IN",
            "ATTENDANCE",
            record.getId().toString(),
            null,
            "{\"workDate\":\"" + today + "\",\"workMode\":\"" + match.workMode() + "\",\"status\":\"" + status + "\"}"
        );

        // Notify manager if pending
        if (status == AttendanceApprovalStatus.PENDING) {
            userRepository.findById(currentUser.id()).ifPresent(u -> {
                if (u.getManagerId() != null) {
                    notificationService.createNotification(
                        u.getManagerId(),
                        NotificationType.ATTENDANCE_REQUEST,
                        "Attendance Pending Approval",
                        u.getEmail() + " checked in for " + today + " (" + match.workMode() + ")",
                        "/manager/approve-attendance.html"
                    );
                }
            });
        }

        return toResponse(record);
    }

    @Transactional
    public AttendanceResponse checkOut(CurrentUser currentUser, CheckOutRequest request) {
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        Instant now = businessClock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();

        Optional<AttendanceRecord> recordOpt = attendanceRecordRepository.findByUserIdAndWorkDate(currentUser.id(), today);
        if (recordOpt.isEmpty() || recordOpt.get().getCheckInAt() == null) {
            List<AttendanceRecord> unclosed = attendanceRecordRepository.findByWorkDateBeforeAndCheckOutAtIsNullAndMissedCheckoutIsFalse(today.plusDays(1));
            Optional<AttendanceRecord> openOpt = unclosed.stream()
                .filter(r -> r.getUserId().equals(currentUser.id()) && r.getCheckInAt() != null)
                .findFirst();
            if (openOpt.isPresent()) {
                recordOpt = openOpt;
            }
        }
        AttendanceRecord record = recordOpt
            .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "ATTENDANCE_NOT_CHECKED_IN", "No check-in record found for today"));

        if (record.getCheckOutAt() != null) {
            throw new AppException(HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_CHECKED_OUT", "Already checked out for today");
        }

        int halfDayThresholdMinutes = settingsService.getInt("attendance.halfDayMinutes", 240);
        String officeStartTimeStr = settingsService.getString("attendance.officeStart", "09:00");
        String officeEndTimeStr = settingsService.getString("attendance.officeEnd", "18:00");
        int standardDayMinutes = (int) Duration.between(LocalTime.parse(officeStartTimeStr), LocalTime.parse(officeEndTimeStr)).toMinutes();

        long diffMinutes = Duration.between(record.getCheckInAt(), now).toMinutes();
        int workedMinutes = (int) Math.max(0, diffMinutes);

        record.setCheckOutAt(now);
        record.setWorkedMinutes(workedMinutes);
        record.setDayType(AttendanceCalculator.deriveDayType(workedMinutes, halfDayThresholdMinutes));
        record.setOvertimeMinutes(AttendanceCalculator.calculateOvertimeMinutes(workedMinutes, standardDayMinutes));
        record.setUpdatedAt(now);

        AttendanceRecord saved = attendanceRecordRepository.save(record);

        auditService.log(
            "ATTENDANCE_CHECK_OUT",
            "ATTENDANCE",
            saved.getId().toString(),
            "{\"checkOutAt\":null}",
            "{\"checkOutAt\":\"" + now + "\",\"workedMinutes\":" + workedMinutes + "}"
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AttendanceTodayDto getToday(CurrentUser currentUser) {
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate today = businessClock.instant().atZone(zone).toLocalDate();

        Optional<AttendanceRecord> recOpt = attendanceRecordRepository.findByUserIdAndWorkDate(currentUser.id(), today);
        if (recOpt.isEmpty() || recOpt.get().getCheckInAt() == null) {
            List<AttendanceRecord> unclosed = attendanceRecordRepository.findByWorkDateBeforeAndCheckOutAtIsNullAndMissedCheckoutIsFalse(today.plusDays(1));
            Optional<AttendanceRecord> openOpt = unclosed.stream()
                .filter(r -> r.getUserId().equals(currentUser.id()) && r.getCheckInAt() != null)
                .findFirst();
            if (openOpt.isPresent()) {
                recOpt = openOpt;
            }
        }
        if (recOpt.isEmpty()) {
            return new AttendanceTodayDto(false, false, null, 0, 0, 0);
        }

        AttendanceRecord rec = recOpt.get();
        boolean checkedIn = rec.getCheckInAt() != null;
        boolean checkedOut = rec.getCheckOutAt() != null;
        int hoursWorked = rec.getWorkedMinutes() / 60;

        return new AttendanceTodayDto(checkedIn, checkedOut, toResponse(rec), hoursWorked, rec.getWorkedMinutes(), rec.getOvertimeMinutes());
    }

    @Transactional(readOnly = true)
    public MonthAttendanceSummaryDto getMonth(CurrentUser currentUser, String monthStr) {
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate today = businessClock.instant().atZone(zone).toLocalDate();

        YearMonth ym;
        if (monthStr != null && !monthStr.isBlank()) {
            ym = YearMonth.parse(monthStr.trim());
        } else {
            ym = YearMonth.from(today);
        }

        LocalDate firstDay = ym.atDay(1);
        LocalDate lastDay = ym.atEndOfMonth();

        List<AttendanceRecord> records = attendanceRecordRepository.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(currentUser.id(), firstDay, lastDay);
        Map<LocalDate, AttendanceRecord> recordMap = records.stream().collect(Collectors.toMap(AttendanceRecord::getWorkDate, r -> r));

        Set<DayOfWeek> weeklyOffDays = parseWeeklyOffDays();
        Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(firstDay, lastDay).stream()
            .map(h -> h.getHolidayDate())
            .collect(Collectors.toSet());

        int totalWorkingDays = 0;
        int presentDays = 0;
        int halfDays = 0;
        int lateDays = 0;
        int absentDays = 0;
        int approvedLeaveDays = 0;

        List<CalendarDayDto> calendarDays = new ArrayList<>();

        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            boolean isWeekend = weeklyOffDays.contains(d.getDayOfWeek());
            boolean isHoliday = holidays.contains(d);
            boolean isFuture = d.isAfter(today);

            AttendanceRecord rec = recordMap.get(d);
            String status;
            String title = "";
            boolean isLate = false;

            if (rec != null) {
                if (rec.getApprovalStatus() == AttendanceApprovalStatus.APPROVED) {
                    if (rec.getDayType() == AttendanceDayType.HALF_DAY) {
                        status = "HALF_DAY";
                        title = "Half Day";
                        halfDays++;
                    } else {
                        status = "PRESENT";
                        title = "Present";
                        presentDays++;
                    }
                    if (rec.isLate()) {
                        lateDays++;
                        isLate = true;
                    }
                } else if (rec.getApprovalStatus() == AttendanceApprovalStatus.PENDING) {
                    status = "PENDING";
                    title = "Pending Approval";
                    if (rec.isLate()) isLate = true;
                } else {
                    status = "ABSENT";
                    title = "Rejected";
                    absentDays++;
                }
            } else if (isFuture) {
                status = "FUTURE";
                title = "";
            } else if (isHoliday) {
                status = "HOLIDAY";
                title = "Holiday";
            } else if (isWeekend) {
                status = "WEEKEND";
                title = "Weekend";
            } else if (leaveQueryService.hasApprovedLeave(currentUser.id(), d)) {
                status = "LEAVE";
                title = "Approved Leave";
                approvedLeaveDays++;
            } else {
                status = "ABSENT";
                title = "Absent";
                absentDays++;
            }

            if (!isWeekend && !isHoliday && !isFuture) {
                totalWorkingDays++;
            }

            calendarDays.add(new CalendarDayDto(
                d,
                d.getDayOfWeek().name(),
                status,
                title,
                isLate
            ));
        }

        BigDecimal attendancePct = AttendanceCalculator.calculateAttendancePercentage(
            presentDays, halfDays, approvedLeaveDays, totalWorkingDays
        );

        List<AttendanceResponse> responseRecords = records.stream().map(this::toResponse).toList();

        return new MonthAttendanceSummaryDto(
            totalWorkingDays,
            presentDays,
            absentDays,
            lateDays,
            halfDays,
            approvedLeaveDays,
            attendancePct,
            calendarDays,
            responseRecords
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> listAttendance(
        CurrentUser currentUser,
        UUID employeeId,
        UUID departmentId,
        LocalDate from,
        LocalDate to,
        String period,
        AttendanceApprovalStatus approvalStatus,
        Boolean isLate,
        int page,
        int size,
        String sort
    ) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot access team attendance lists");
        }

        // Cap page size to 100
        int pageSize = Math.min(Math.max(1, size), 100);

        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate today = businessClock.instant().atZone(zone).toLocalDate();

        // Handle period shortcut
        LocalDate effectiveFrom = from;
        LocalDate effectiveTo = to;
        if (period != null && !period.isBlank()) {
            switch (period.toLowerCase()) {
                case "today" -> {
                    effectiveFrom = today;
                    effectiveTo = today;
                }
                case "week" -> {
                    effectiveFrom = today.minusDays(6);
                    effectiveTo = today;
                }
                case "month" -> {
                    effectiveFrom = today.withDayOfMonth(1);
                    effectiveTo = today;
                }
            }
        }

        // Date range cap of 90 days
        if (effectiveFrom != null && effectiveTo != null) {
            long days = Duration.between(effectiveFrom.atStartOfDay(), effectiveTo.atStartOfDay()).toDays();
            if (days > 90) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Date range cannot exceed 90 days");
            }
        }

        // Manager scoping
        List<UUID> allowedUserIds = null;
        if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            allowedUserIds = team.stream().map(User::getId).toList();
            if (employeeId != null) {
                if (!allowedUserIds.contains(employeeId)) {
                    throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Cannot access attendance outside your team");
                }
                allowedUserIds = List.of(employeeId);
            }
        } else if (employeeId != null) {
            allowedUserIds = List.of(employeeId);
        }

        // Department filter
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

        final LocalDate finalFrom = effectiveFrom;
        final LocalDate finalTo = effectiveTo;
        final List<UUID> finalUserIds = allowedUserIds;

        Specification<AttendanceRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (finalUserIds != null) {
                if (finalUserIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(root.get("userId").in(finalUserIds));
                }
            }

            if (finalFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("workDate"), finalFrom));
            }
            if (finalTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("workDate"), finalTo));
            }
            if (approvalStatus != null) {
                predicates.add(cb.equal(root.get("approvalStatus"), approvalStatus));
            }
            if (isLate != null) {
                predicates.add(cb.equal(root.get("isLate"), isLate));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Sort sortOrder = Sort.by(Sort.Direction.DESC, "workDate", "checkInAt");
        Pageable pageable = PageRequest.of(page, pageSize, sortOrder);

        Page<AttendanceRecord> pageResult = attendanceRecordRepository.findAll(spec, pageable);
        List<AttendanceResponse> items = pageResult.getContent().stream().map(this::toResponse).toList();

        return new PageResponse<>(
            items,
            pageResult.getNumber(),
            pageResult.getSize(),
            pageResult.getTotalElements(),
            pageResult.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getPending(CurrentUser currentUser) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot access pending attendance");
        }

        List<AttendanceRecord> records;
        if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            List<UUID> memberIds = team.stream().map(User::getId).toList();
            if (memberIds.isEmpty()) {
                return List.of();
            }
            records = attendanceRecordRepository.findPendingByUserIds(memberIds);
        } else {
            records = attendanceRecordRepository.findAllPending();
        }

        return records.stream().map(this::toResponse).toList();
    }

    @Transactional
    public AttendanceResponse approveAttendance(CurrentUser currentUser, UUID id) {
        AttendanceRecord record = getRecordAndAssertScope(currentUser, id);
        assertCanModifyAttendance(record);

        record.setApprovalStatus(AttendanceApprovalStatus.APPROVED);
        record.setApprovedBy(currentUser.id());
        record.setApprovedAt(businessClock.instant());
        record.setUpdatedAt(businessClock.instant());

        AttendanceRecord saved = attendanceRecordRepository.save(record);

        auditService.log(
            "ATTENDANCE_APPROVE",
            "ATTENDANCE",
            saved.getId().toString(),
            "{\"approvalStatus\":\"PENDING\"}",
            "{\"approvalStatus\":\"APPROVED\",\"approvedBy\":\"" + currentUser.id() + "\"}"
        );

        notificationService.createNotification(
            saved.getUserId(),
            NotificationType.ATTENDANCE_APPROVED,
            "Attendance Approved",
            "Your attendance for " + saved.getWorkDate() + " was approved",
            "/employee/attendance.html"
        );

        return toResponse(saved);
    }

    @Transactional
    public AttendanceResponse rejectAttendance(CurrentUser currentUser, UUID id, AttendanceRejectRequest request) {
        AttendanceRecord record = getRecordAndAssertScope(currentUser, id);
        assertCanModifyAttendance(record);

        record.setApprovalStatus(AttendanceApprovalStatus.REJECTED);
        record.setRejectionNote(request != null && request.note() != null ? request.note().trim() : null);
        record.setUpdatedAt(businessClock.instant());

        AttendanceRecord saved = attendanceRecordRepository.save(record);

        auditService.log(
            "ATTENDANCE_REJECT",
            "ATTENDANCE",
            saved.getId().toString(),
            "{\"approvalStatus\":\"PENDING\"}",
            "{\"approvalStatus\":\"REJECTED\",\"note\":\"" + (record.getRejectionNote() != null ? record.getRejectionNote() : "") + "\"}"
        );

        notificationService.createNotification(
            saved.getUserId(),
            NotificationType.ATTENDANCE_REJECTED,
            "Attendance Rejected",
            "Your attendance for " + saved.getWorkDate() + " was rejected" + (record.getRejectionNote() != null ? ": " + record.getRejectionNote() : ""),
            "/employee/attendance.html"
        );

        return toResponse(saved);
    }

    @Transactional
    public BulkDecisionResult bulkDecision(CurrentUser currentUser, AttendanceBulkDecisionRequest request) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot perform bulk attendance decisions");
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

        List<BulkDecisionItemResult> itemResults = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        List<UUID> managerTeamIds = null;
        if (currentUser.isManager()) {
            managerTeamIds = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id()).stream().map(User::getId).toList();
        }

        for (UUID id : request.ids()) {
            Optional<AttendanceRecord> recOpt = attendanceRecordRepository.findById(id);
            if (recOpt.isEmpty()) {
                failureCount++;
                itemResults.add(new BulkDecisionItemResult(id, false, "FAILED", "Attendance record not found"));
                continue;
            }

            AttendanceRecord rec = recOpt.get();

            // Self-approval check: managers or admins cannot decide their own attendance
            if (currentUser.id().equals(rec.getUserId())) {
                failureCount++;
                itemResults.add(new BulkDecisionItemResult(id, false, "FAILED", "Cannot decide your own attendance"));
                continue;
            }

            // Scope check: uniform error without existence leak
            if (currentUser.isManager() && (managerTeamIds == null || !managerTeamIds.contains(rec.getUserId()))) {
                failureCount++;
                itemResults.add(new BulkDecisionItemResult(id, false, "FAILED", "Attendance record not found"));
                continue;
            }

            if (rec.getApprovalStatus() != AttendanceApprovalStatus.PENDING) {
                failureCount++;
                itemResults.add(new BulkDecisionItemResult(id, false, "FAILED", "Record is already " + rec.getApprovalStatus()));
                continue;
            }

            if (payrollQueryService.existsPayrollForUserAndMonth(rec.getUserId(), YearMonth.from(rec.getWorkDate()))) {
                failureCount++;
                itemResults.add(new BulkDecisionItemResult(id, false, "FAILED", "Payroll already exists for this month"));
                continue;
            }

            if (decision.equals("APPROVE")) {
                rec.setApprovalStatus(AttendanceApprovalStatus.APPROVED);
                rec.setApprovedBy(currentUser.id());
                rec.setApprovedAt(businessClock.instant());
            } else {
                rec.setApprovalStatus(AttendanceApprovalStatus.REJECTED);
                rec.setRejectionNote(request.note() != null ? request.note().trim() : null);
            }
            rec.setUpdatedAt(businessClock.instant());

            attendanceRecordRepository.save(rec);

            auditService.log(
                "ATTENDANCE_BULK_" + decision,
                "ATTENDANCE",
                rec.getId().toString(),
                "{\"approvalStatus\":\"PENDING\"}",
                "{\"approvalStatus\":\"" + decision + "\"}"
            );

            notificationService.createNotification(
                rec.getUserId(),
                decision.equals("APPROVE") ? NotificationType.ATTENDANCE_APPROVED : NotificationType.ATTENDANCE_REJECTED,
                "Attendance " + (decision.equals("APPROVE") ? "Approved" : "Rejected"),
                "Your attendance for " + rec.getWorkDate() + " was " + (decision.equals("APPROVE") ? "approved" : "rejected"),
                "/employee/attendance.html"
            );

            successCount++;
            itemResults.add(new BulkDecisionItemResult(id, true, decision, "Processed successfully"));
        }

        return new BulkDecisionResult(request.ids().size(), successCount, failureCount, itemResults);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getTimeline(CurrentUser currentUser, UUID userId, Integer days) {
        teamScope.assertCanAccess(currentUser, userId);

        int maxDays = Math.min(days != null ? Math.max(1, days) : 30, 90);
        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate today = businessClock.instant().atZone(zone).toLocalDate();
        LocalDate from = today.minusDays(maxDays);

        List<AttendanceRecord> records = attendanceRecordRepository.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, from, today);
        return records.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryDto getSummary(CurrentUser currentUser, LocalDate date) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot access attendance summary");
        }

        String timezoneStr = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zone = ZoneId.of(timezoneStr);
        LocalDate targetDate = date != null ? date : businessClock.instant().atZone(zone).toLocalDate();

        List<User> targetUsers;
        if (currentUser.isManager()) {
            targetUsers = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
        } else {
            targetUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus().name().equals("ACTIVE") && (u.getRole() == Role.EMPLOYEE || u.getRole() == Role.MANAGER))
                .toList();
        }

        int totalEmployees = targetUsers.size();
        List<UUID> userIds = targetUsers.stream().map(User::getId).toList();

        List<AttendanceRecord> records = userIds.isEmpty() ? List.of() :
            attendanceRecordRepository.findAll((root, query, cb) -> cb.and(
                root.get("userId").in(userIds),
                cb.equal(root.get("workDate"), targetDate)
            ));

        int presentCount = 0;
        int lateCount = 0;
        int remoteCount = 0;
        int pendingCount = 0;

        for (AttendanceRecord r : records) {
            if (r.getApprovalStatus() == AttendanceApprovalStatus.APPROVED) {
                presentCount++;
            } else if (r.getApprovalStatus() == AttendanceApprovalStatus.PENDING) {
                pendingCount++;
            }
            if (r.isLate()) lateCount++;
            if (r.getWorkMode() == AttendanceWorkMode.REMOTE) remoteCount++;
        }

        int absentCount = Math.max(0, totalEmployees - (presentCount + pendingCount));
        int onLeaveCount = 0; // stubbed until Phase 6

        return new AttendanceSummaryDto(
            targetDate,
            totalEmployees,
            presentCount,
            lateCount,
            remoteCount,
            pendingCount,
            absentCount,
            onLeaveCount
        );
    }

    @Transactional(readOnly = true)
    public String exportAttendanceCsv(CurrentUser currentUser, UUID departmentId, LocalDate from, LocalDate to) {
        PageResponse<AttendanceResponse> response = listAttendance(
            currentUser, null, departmentId, from, to, null, null, null, 0, 100, null
        );

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Date,EmployeeCode,EmployeeName,Department,WorkMode,CheckIn,CheckOut,WorkedMinutes,Punctuality,Status,Location");

        for (AttendanceResponse r : response.items()) {
            pw.printf(
                "%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s%n",
                r.workDate(),
                escapeCsv(r.userCode()),
                escapeCsv(r.userName()),
                escapeCsv(r.departmentName()),
                r.workMode(),
                r.checkInAt() != null ? r.checkInAt().toString() : "",
                r.checkOutAt() != null ? r.checkOutAt().toString() : "",
                r.workedMinutes(),
                r.isLate() ? "LATE" : "ON_TIME",
                r.approvalStatus(),
                escapeCsv(r.officeLocationName() != null ? r.officeLocationName() : "Remote")
            );
        }

        auditService.log("ATTENDANCE_EXPORT", "ATTENDANCE", null, null, "Exported " + response.items().size() + " records");
        return sw.toString();
    }

    @Transactional(readOnly = true)
    public int countAbsentWorkingDays(UUID userId, LocalDate from, LocalDate to) {
        return countAbsentWorkingDays(userId, from, to, false);
    }

    public int countAbsentWorkingDays(UUID userId, LocalDate from, LocalDate to, boolean treatPendingAsPresent) {
        Set<DayOfWeek> weeklyOffDays = parseWeeklyOffDays();
        Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to).stream()
            .map(h -> h.getHolidayDate())
            .collect(Collectors.toSet());

        List<AttendanceRecord> records = attendanceRecordRepository.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, from, to);
        Set<LocalDate> presentDates = records.stream()
            .filter(r -> r.getApprovalStatus() == AttendanceApprovalStatus.APPROVED ||
                (treatPendingAsPresent && r.getApprovalStatus() == AttendanceApprovalStatus.PENDING))
            .map(AttendanceRecord::getWorkDate)
            .collect(Collectors.toSet());

        int absentDays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (weeklyOffDays.contains(d.getDayOfWeek())) continue;
            if (holidays.contains(d)) continue;
            if (presentDates.contains(d)) continue;
            if (leaveQueryService.hasApprovedLeave(userId, d)) continue;

            absentDays++;
        }

        return absentDays;
    }

    public List<LocalDate> findPendingAttendanceInMonth(UUID userId, LocalDate from, LocalDate to) {
        return attendanceRecordRepository.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, from, to)
            .stream()
            .filter(r -> r.getApprovalStatus() == AttendanceApprovalStatus.PENDING)
            .map(AttendanceRecord::getWorkDate)
            .toList();
    }

    // --- Helper Methods ---

    private AttendanceRecord getRecordAndAssertScope(CurrentUser currentUser, UUID id) {
        AttendanceRecord record = attendanceRecordRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attendance record not found"));

        if (currentUser.id().equals(record.getUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SELF_APPROVAL", "Cannot approve or reject your own attendance");
        }

        if (currentUser.isManager()) {
            User targetUser = userRepository.findById(record.getUserId()).orElse(null);
            if (targetUser == null || !currentUser.id().equals(targetUser.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers can only decide attendance for their own team");
            }
        }
        return record;
    }

    private void assertCanModifyAttendance(AttendanceRecord record) {
        if (record.getApprovalStatus() != AttendanceApprovalStatus.PENDING) {
            throw new AppException(HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_DECIDED", "Cannot modify attendance with status " + record.getApprovalStatus());
        }

        if (payrollQueryService.existsPayrollForUserAndMonth(record.getUserId(), YearMonth.from(record.getWorkDate()))) {
            throw new AppException(HttpStatus.CONFLICT, "PAYROLL_ALREADY_EXISTS", "Cannot modify attendance for a month with generated payroll");
        }
    }

    private AttendanceResponse toResponse(AttendanceRecord record) {
        String userCode = null;
        String userName = null;
        String deptName = null;
        Optional<User> uOpt = userRepository.findById(record.getUserId());
        if (uOpt.isPresent()) {
            User u = uOpt.get();
            userCode = u.getCode();
            userName = u.getEmail();
        }

        String approverName = null;
        if (record.getApprovedBy() != null) {
            approverName = userRepository.findById(record.getApprovedBy())
                .map(User::getEmail)
                .orElse(null);
        }

        String locationName = null;
        if (record.getOfficeLocationId() != null) {
            locationName = officeLocationRepository.findById(record.getOfficeLocationId())
                .map(OfficeLocation::getName)
                .orElse(null);
        }

        return new AttendanceResponse(
            record.getId(),
            record.getUserId(),
            userCode,
            userName,
            deptName,
            record.getWorkDate(),
            record.getCheckInAt(),
            record.getCheckOutAt(),
            record.getWorkedMinutes(),
            record.getOvertimeMinutes(),
            record.getWorkMode(),
            record.getDayType(),
            record.isLate(),
            record.getApprovalStatus(),
            record.getApprovedBy(),
            approverName,
            record.getApprovedAt(),
            record.getRejectionNote(),
            record.isMissedCheckout(),
            record.getLatitude(),
            record.getLongitude(),
            record.getAccuracyM(),
            record.isLowAccuracy(),
            record.getDevicePlatform(),
            record.getUserAgent(),
            record.getIpAddress(),
            record.getOfficeLocationId(),
            locationName,
            record.getDistanceM(),
            record.getVersion()
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (appTrustedProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private Set<DayOfWeek> parseWeeklyOffDays() {
        String weeklyOffStr = settingsService.getString("attendance.weeklyOffDays", "SAT,SUN");
        Set<DayOfWeek> days = new HashSet<>();
        for (String part : weeklyOffStr.split(",")) {
            String clean = part.trim().toUpperCase();
            switch (clean) {
                case "MON", "MONDAY" -> days.add(DayOfWeek.MONDAY);
                case "TUE", "TUESDAY" -> days.add(DayOfWeek.TUESDAY);
                case "WED", "WEDNESDAY" -> days.add(DayOfWeek.WEDNESDAY);
                case "THU", "THURSDAY" -> days.add(DayOfWeek.THURSDAY);
                case "FRI", "FRIDAY" -> days.add(DayOfWeek.FRIDAY);
                case "SAT", "SATURDAY" -> days.add(DayOfWeek.SATURDAY);
                case "SUN", "SUNDAY" -> days.add(DayOfWeek.SUNDAY);
            }
        }
        return days;
    }

    private String truncate(String val, int maxLen) {
        if (val == null) return null;
        String trimmed = val.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@")) {
            val = "'" + val;
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
