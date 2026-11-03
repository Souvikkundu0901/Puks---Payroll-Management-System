package com.octopyder.payroll.dashboard;

import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.audit.AuditLog;
import com.octopyder.payroll.audit.AuditLogRepository;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.dashboard.dto.AdminDashboardDto;
import com.octopyder.payroll.dashboard.dto.DailyAttendanceBarDto;
import com.octopyder.payroll.dashboard.dto.DepartmentTaskDto;
import com.octopyder.payroll.dashboard.dto.EmployeeDashboardDto;
import com.octopyder.payroll.dashboard.dto.EmployeeSalaryBriefDto;
import com.octopyder.payroll.dashboard.dto.ManagerDashboardDto;
import com.octopyder.payroll.dashboard.dto.PendingTaskApprovalDto;
import com.octopyder.payroll.dashboard.dto.SafeActivityDto;
import com.octopyder.payroll.dashboard.dto.SalaryDonutDto;
import com.octopyder.payroll.dashboard.dto.TeamMemberPerformanceDto;
import com.octopyder.payroll.dashboard.dto.UpcomingDeadlineDto;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.leave.LeaveService;
import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;
import com.octopyder.payroll.leave.dto.LeaveBalanceDto;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.payroll.PayrollRecord;
import com.octopyder.payroll.payroll.PayrollRecordRepository;
import com.octopyder.payroll.payroll.PayrollRecordStatus;
import com.octopyder.payroll.payroll.PayrollRun;
import com.octopyder.payroll.payroll.PayrollRunRepository;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import com.octopyder.payroll.task.Task;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final TaskRepository taskRepository;
    private final PayrollRecordRepository payrollRecordRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final AuditLogRepository auditLogRepository;
    private final LeaveService leaveService;
    private final com.octopyder.payroll.leave.LeaveLedgerService leaveLedgerService;
    private final SettingsService settingsService;
    private final BusinessClock businessClock;

    public DashboardService(
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        DepartmentRepository departmentRepository,
        AttendanceRecordRepository attendanceRecordRepository,
        LeaveRequestRepository leaveRequestRepository,
        TaskRepository taskRepository,
        PayrollRecordRepository payrollRecordRepository,
        PayrollRunRepository payrollRunRepository,
        AuditLogRepository auditLogRepository,
        LeaveService leaveService,
        com.octopyder.payroll.leave.LeaveLedgerService leaveLedgerService,
        SettingsService settingsService,
        BusinessClock businessClock
    ) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.departmentRepository = departmentRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.taskRepository = taskRepository;
        this.payrollRecordRepository = payrollRecordRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.auditLogRepository = auditLogRepository;
        this.leaveService = leaveService;
        this.leaveLedgerService = leaveLedgerService;
        this.settingsService = settingsService;
        this.businessClock = businessClock;
    }

    private ZoneId getCompanyZone() {
        String tz = settingsService.getString("company.timezone", "Asia/Kolkata");
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return BusinessClock.ZONE;
        }
    }

    public AdminDashboardDto getAdminDashboard(CurrentUser currentUser) {
        ZoneId zone = getCompanyZone();
        LocalDate today = businessClock.today();
        YearMonth ym = YearMonth.from(today);

        // 1. Employee counters
        long totalEmployees = userRepository.countByRoleAndDeletedAtIsNull(Role.EMPLOYEE);
        long activeEmployees = userRepository.countByRoleAndStatusAndDeletedAtIsNull(Role.EMPLOYEE, UserStatus.ACTIVE);
        long totalManagers = userRepository.countByRoleAndDeletedAtIsNull(Role.MANAGER);

        Instant startOfMonth = ym.atDay(1).atStartOfDay(zone).toInstant();
        long joinedThisMonth = userRepository.countByRoleAndCreatedAtAfterAndDeletedAtIsNull(Role.EMPLOYEE, startOfMonth);
        long priorTotal = totalEmployees - joinedThisMonth;
        String employeeGrowth = priorTotal > 0
            ? "+" + Math.round(((double) joinedThisMonth / priorTotal) * 100) + "%"
            : "+0%";

        // 2. Attendance counters
        long attendancePresent = attendanceRecordRepository.countByWorkDateAndApprovalStatus(today, AttendanceApprovalStatus.APPROVED);
        long attendancePending = attendanceRecordRepository.countByWorkDateAndApprovalStatus(today, AttendanceApprovalStatus.PENDING);
        long attendanceNotCheckedIn = Math.max(0, activeEmployees - (attendancePresent + attendancePending));

        // Closed absent days in month up to yesterday
        long attendanceAbsent = 0;
        if (today.getDayOfMonth() > 1) {
            LocalDate yesterday = today.minusDays(1);
            long pastApprovedPresent = attendanceRecordRepository.countByUserIdAndWorkDateBetweenAndApprovalStatus(null, ym.atDay(1), yesterday, AttendanceApprovalStatus.APPROVED);
            attendanceAbsent = Math.max(0, (activeEmployees * (today.getDayOfMonth() - 1)) - pastApprovedPresent);
        }

        // 3. Leave counters & stats by type (PAID, CASUAL, SICK, EMERGENCY, UNPAID)
        long pendingLeaves = leaveRequestRepository.countByStatus(LeaveStatus.PENDING);
        Map<String, Long> leaveStats = new LinkedHashMap<>();
        for (LeaveType lt : LeaveType.values()) {
            leaveStats.put(lt.name(), 0L);
        }
        LocalDate startOfYear = ym.atDay(1).withDayOfYear(1);
        LocalDate endOfYear = ym.atDay(1).withDayOfYear(ym.atDay(1).lengthOfYear());
        List<Object[]> typeCounts = leaveRequestRepository.countApprovedByTypeInDateRange(startOfYear, endOfYear);
        for (Object[] row : typeCounts) {
            if (row[0] instanceof LeaveType lt && row[1] instanceof Number count) {
                leaveStats.put(lt.name(), count.longValue());
            }
        }

        // 4. Task counters
        long activeTasks = taskRepository.countByStatusIn(List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        long completedTasks = taskRepository.countByStatus(TaskStatus.APPROVED);
        long pendingTasks = taskRepository.countByStatus(TaskStatus.PENDING_APPROVAL);

        // 5. Payroll status & donut
        Optional<PayrollRun> latestRun = payrollRunRepository.findFirstByOrderByCreatedAtDesc();
        String payrollStatus = latestRun.map(r -> r.getStatus().name()).orElse("NONE");
        BigDecimal payrollAmount = BigDecimal.ZERO;
        SalaryDonutDto salaryDonut;

        if (latestRun.isPresent()) {
            List<PayrollRecord> records = payrollRecordRepository.findByRunId(latestRun.get().getId());
            BigDecimal grossSum = BigDecimal.ZERO;
            BigDecimal releasedSum = BigDecimal.ZERO;
            BigDecimal pendingSum = BigDecimal.ZERO;
            BigDecimal retainedSum = BigDecimal.ZERO;

            for (PayrollRecord r : records) {
                grossSum = grossSum.add(r.getGross());
                releasedSum = releasedSum.add(r.getPaidAmount());
                if (r.getStatus() == PayrollRecordStatus.GENERATED) {
                    pendingSum = pendingSum.add(r.getNetPayable().subtract(r.getPaidAmount()));
                }
                retainedSum = retainedSum.add(r.getRetentionAmount());
            }
            payrollAmount = grossSum;

            double netTotalDouble = grossSum.doubleValue();
            double releasedPct = netTotalDouble > 0 ? (releasedSum.doubleValue() / netTotalDouble) * 100 : 0.0;
            double pendingPct = netTotalDouble > 0 ? (pendingSum.doubleValue() / netTotalDouble) * 100 : 0.0;
            double retainedPct = netTotalDouble > 0 ? (retainedSum.doubleValue() / netTotalDouble) * 100 : 0.0;

            salaryDonut = new SalaryDonutDto(
                grossSum,
                releasedSum,
                pendingSum,
                retainedSum,
                Math.round(releasedPct * 100.0) / 100.0,
                Math.round(pendingPct * 100.0) / 100.0,
                Math.round(retainedPct * 100.0) / 100.0
            );
        } else {
            salaryDonut = new SalaryDonutDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0);
        }

        // 6. Weekly attendance bars (past 7 days Mon-Sun)
        List<DailyAttendanceBarDto> weeklyBars = new ArrayList<>();
        LocalDate startOfWeek = today.with(DayOfWeek.MONDAY);
        for (int i = 0; i < 7; i++) {
            LocalDate d = startOfWeek.plusDays(i);
            long pres = attendanceRecordRepository.countByWorkDateAndApprovalStatus(d, AttendanceApprovalStatus.APPROVED);
            String dayName = d.getDayOfWeek().name().substring(0, 3);
            weeklyBars.add(new DailyAttendanceBarDto(dayName, d, pres, activeEmployees));
        }

        // 7. Department task completion
        List<DepartmentTaskDto> deptTasks = new ArrayList<>();
        List<Department> depts = departmentRepository.findByActiveTrue();
        for (Department dept : depts) {
            List<User> deptUsers = userRepository.findByRoleAndDeletedAtIsNull(Role.EMPLOYEE);
            List<UUID> deptUserIds = new ArrayList<>();
            for (User u : deptUsers) {
                profileRepository.findById(u.getId()).ifPresent(p -> {
                    if (dept.getId().equals(p.getDepartmentId())) {
                        deptUserIds.add(u.getId());
                    }
                });
            }
            if (!deptUserIds.isEmpty()) {
                long total = taskRepository.countByAssigneeIdInAndStatusIn(
                    deptUserIds,
                    List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.PENDING_APPROVAL, TaskStatus.APPROVED, TaskStatus.REJECTED)
                );
                long completed = taskRepository.countByAssigneeIdInAndStatus(deptUserIds, TaskStatus.APPROVED);
                double pct = total > 0 ? ((double) completed / total) * 100.0 : 100.0;
                deptTasks.add(new DepartmentTaskDto(dept.getName(), total, completed, Math.round(pct * 10.0) / 10.0));
            }
        }

        // 8. Recent safe activities
        List<SafeActivityDto> recentActivities = getSafeActivities(null);

        return new AdminDashboardDto(
            totalEmployees,
            activeEmployees,
            employeeGrowth,
            totalManagers,
            attendancePresent,
            attendanceNotCheckedIn,
            attendancePending,
            attendanceAbsent,
            pendingLeaves,
            activeTasks,
            completedTasks,
            pendingTasks,
            payrollStatus,
            payrollAmount,
            weeklyBars,
            salaryDonut,
            deptTasks,
            leaveStats,
            recentActivities
        );
    }

    public ManagerDashboardDto getManagerDashboard(CurrentUser currentUser, UUID managerIdParam) {
        UUID managerId = currentUser.isManager() ? currentUser.id() : (managerIdParam != null ? managerIdParam : currentUser.id());
        LocalDate today = businessClock.today();
        YearMonth ym = YearMonth.from(today);
        ZoneId zone = getCompanyZone();

        // Team members: users with manager_id == managerId excluding self
        List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(managerId).stream()
            .filter(u -> !u.getId().equals(managerId))
            .toList();

        long teamMembersCount = team.size();
        if (teamMembersCount == 0) {
            return new ManagerDashboardDto(
                0, 0, 0, 0, 0, 0, 0, 0, 0.0,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
            );
        }

        List<UUID> teamIds = team.stream().map(User::getId).toList();

        long activeTasksCount = taskRepository.countByAssigneeIdInAndStatusIn(teamIds, List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        long pendingAttendanceCount = attendanceRecordRepository.countByUserIdInAndApprovalStatus(teamIds, AttendanceApprovalStatus.PENDING);
        long pendingLeaveCount = leaveRequestRepository.countByUserIdInAndStatus(teamIds, LeaveStatus.PENDING);
        long pendingSalaryApprovalsCount = payrollRecordRepository.countByUserIdInAndStatus(teamIds, PayrollRecordStatus.GENERATED);
        long tasksAwaitingCount = taskRepository.countByAssigneeIdInAndStatus(teamIds, TaskStatus.PENDING_APPROVAL);

        long presentTodayCount = attendanceRecordRepository.countByUserIdInAndWorkDateAndApprovalStatus(teamIds, today, AttendanceApprovalStatus.APPROVED);
        double attendancePercentage = (teamMembersCount > 0)
            ? Math.round(((double) presentTodayCount / teamMembersCount) * 1000.0) / 10.0
            : 0.0;

        // Team performance: per BR-P3 (approved / due in month; 100% if none due)
        Instant monthStart = ym.atDay(1).atStartOfDay(zone).toInstant();
        Instant monthEnd = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();

        List<TeamMemberPerformanceDto> performanceList = new ArrayList<>();
        for (User u : team) {
            EmployeeProfile prof = profileRepository.findById(u.getId()).orElse(null);
            String name = prof != null && prof.getFirstName() != null
                ? prof.getFirstName() + " " + (prof.getLastName() != null ? prof.getLastName() : "")
                : u.getCode();
            String desig = prof != null && prof.getDesignation() != null ? prof.getDesignation() : "Member";

            List<Task> memberMonthTasks = taskRepository.findByAssigneeIdAndDeadlineAtBetween(u.getId(), monthStart, monthEnd).stream()
                .filter(t -> t.getStatus() != TaskStatus.CANCELLED)
                .toList();

            double pct = 100.0;
            if (!memberMonthTasks.isEmpty()) {
                long approved = memberMonthTasks.stream().filter(t -> t.getStatus() == TaskStatus.APPROVED).count();
                pct = Math.round(((double) approved / memberMonthTasks.size()) * 1000.0) / 10.0;
            }
            performanceList.add(new TeamMemberPerformanceDto(u.getId(), u.getCode(), name, desig, pct));
        }

        // Pending task approvals
        List<Task> pendingTasks = taskRepository.findByAssigneeIdInAndStatus(teamIds, TaskStatus.PENDING_APPROVAL);
        List<PendingTaskApprovalDto> pendingApprovalDtos = pendingTasks.stream().map(t -> {
            String assigneeName = team.stream()
                .filter(u -> u.getId().equals(t.getAssigneeId()))
                .findFirst()
                .map(u -> profileRepository.findById(u.getId())
                    .map(p -> p.getFirstName() + " " + (p.getLastName() != null ? p.getLastName() : ""))
                    .orElse(u.getCode()))
                .orElse("Assignee");
            return new PendingTaskApprovalDto(
                t.getId(),
                t.getCode(),
                t.getTitle(),
                assigneeName,
                t.getDeadlineAt(),
                t.getPriority().name(),
                t.getProgress()
            );
        }).toList();

        // Upcoming deadlines in next 7 days
        Instant now = businessClock.instant();
        Instant nextWeek = now.plus(7, ChronoUnit.DAYS);
        List<Task> upcoming = taskRepository.findByAssigneeIdInAndDeadlineAtBetween(teamIds, now, nextWeek).stream()
            .filter(t -> t.getStatus() != TaskStatus.APPROVED && t.getStatus() != TaskStatus.CANCELLED)
            .toList();

        List<UpcomingDeadlineDto> upcomingDtos = upcoming.stream().map(t -> {
            String assigneeName = team.stream()
                .filter(u -> u.getId().equals(t.getAssigneeId()))
                .findFirst()
                .map(u -> u.getCode())
                .orElse("Assignee");
            return new UpcomingDeadlineDto(t.getId(), t.getCode(), t.getTitle(), assigneeName, t.getDeadlineAt(), t.getPriority().name());
        }).toList();

        List<SafeActivityDto> recentActivities = getSafeActivities(teamIds);

        return new ManagerDashboardDto(
            teamMembersCount,
            activeTasksCount,
            pendingAttendanceCount,
            pendingLeaveCount,
            pendingSalaryApprovalsCount,
            tasksAwaitingCount,
            presentTodayCount,
            teamMembersCount,
            attendancePercentage,
            performanceList,
            pendingApprovalDtos,
            recentActivities,
            upcomingDtos
        );
    }

    public EmployeeDashboardDto getEmployeeDashboard(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id()).orElseThrow();
        EmployeeProfile profile = profileRepository.findById(user.getId()).orElse(null);
        LocalDate today = businessClock.today();
        YearMonth ym = YearMonth.from(today);

        String employeeCode = user.getCode();
        String employeeName = profile != null && profile.getFirstName() != null
            ? profile.getFirstName() + " " + (profile.getLastName() != null ? profile.getLastName() : "")
            : user.getCode();
        String department = profile != null && profile.getDepartmentId() != null
            ? departmentRepository.findById(profile.getDepartmentId()).map(Department::getName).orElse("General")
            : "General";
        String designation = profile != null && profile.getDesignation() != null ? profile.getDesignation() : "Employee";

        // Today attendance
        Optional<AttendanceRecord> todayAtt = attendanceRecordRepository.findByUserIdAndWorkDate(user.getId(), today);
        String todayAttendanceStatus = todayAtt.map(a -> a.getDayType().name()).orElse("NOT_MARKED");
        Instant checkInAt = todayAtt.map(AttendanceRecord::getCheckInAt).orElse(null);
        Instant checkOutAt = todayAtt.map(AttendanceRecord::getCheckOutAt).orElse(null);

        // Task counts
        long assignedTasksCount = taskRepository.countByAssigneeIdAndStatusIn(user.getId(), List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        long pendingTasksCount = taskRepository.countByAssigneeIdAndStatus(user.getId(), TaskStatus.PENDING_APPROVAL);
        long completedTasksCount = taskRepository.countByAssigneeIdAndStatus(user.getId(), TaskStatus.APPROVED);

        // Cycle attendance percentage
        long presentDays = attendanceRecordRepository.countByUserIdAndWorkDateBetweenAndApprovalStatus(
            user.getId(), ym.atDay(1), today, AttendanceApprovalStatus.APPROVED
        );
        int dayOfMonth = today.getDayOfMonth();
        double attendancePct = dayOfMonth > 0 ? Math.round(((double) presentDays / dayOfMonth) * 1000.0) / 10.0 : 100.0;

        // Leave usage
        LeaveBalanceDto balance = leaveLedgerService.getBalance(user.getId(), today);
        BigDecimal usedLeaves = balance.used() != null ? balance.used() : BigDecimal.ZERO;
        BigDecimal allowance = balance.annualAllowance() != null ? balance.annualAllowance() : BigDecimal.ZERO;
        String leaveUsage = usedLeaves.stripTrailingZeros().toPlainString() + " / " + allowance.stripTrailingZeros().toPlainString() + " Days";

        // Salary slip summary
        Optional<PayrollRecord> latestRecord = payrollRecordRepository.findFirstByUserIdOrderByMonthDesc(user.getId());
        EmployeeSalaryBriefDto salarySummary = latestRecord.map(r -> new EmployeeSalaryBriefDto(
            r.getMonth().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")),
            r.getGross(),
            r.getTranche1Amount(),
            r.getRetentionAmount(),
            r.getNetPayable(),
            r.getStatus().name()
        )).orElse(new EmployeeSalaryBriefDto(ym.toString(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NONE"));

        return new EmployeeDashboardDto(
            employeeCode,
            employeeName,
            department,
            designation,
            todayAttendanceStatus,
            checkInAt,
            checkOutAt,
            assignedTasksCount,
            pendingTasksCount,
            completedTasksCount,
            attendancePct,
            leaveUsage,
            balance.available() != null ? balance.available().doubleValue() : 0.0,
            salarySummary
        );
    }

    private List<SafeActivityDto> getSafeActivities(List<UUID> scopedUserIds) {
        List<AuditLog> logs = auditLogRepository.findTop10ByOrderByAtDesc();
        List<SafeActivityDto> list = new ArrayList<>();
        Instant now = businessClock.instant();

        for (AuditLog l : logs) {
            if (scopedUserIds != null && l.getActorId() != null && !scopedUserIds.contains(l.getActorId())) {
                continue;
            }

            String action = l.getAction();
            String title = switch (action) {
                case "ATTENDANCE_CHECK_IN" -> "Attendance marked";
                case "ATTENDANCE_APPROVE" -> "Attendance approved";
                case "ATTENDANCE_REJECT" -> "Attendance rejected";
                case "LEAVE_APPLY" -> "Leave request submitted";
                case "LEAVE_APPROVE" -> "Leave request approved";
                case "LEAVE_REJECT" -> "Leave request rejected";
                case "TASK_CREATE" -> "New task assigned";
                case "TASK_SUBMIT" -> "Task submitted for review";
                case "TASK_APPROVE" -> "Task approved";
                case "TASK_REJECT" -> "Task rejected";
                case "PAYROLL_RUN_GENERATE" -> "Payroll run generated";
                case "PAYROLL_APPROVE" -> "Salary approved";
                case "PAYROLL_RELEASE", "PAYROLL_RELEASE_DEFAULT" -> "Salary payment released";
                default -> "System activity: " + action.toLowerCase().replace('_', ' ');
            };

            String type = switch (action) {
                case "ATTENDANCE_APPROVE", "LEAVE_APPROVE", "TASK_APPROVE", "PAYROLL_RELEASE" -> "success";
                case "ATTENDANCE_REJECT", "LEAVE_REJECT", "TASK_REJECT" -> "danger";
                case "TASK_SUBMIT", "LEAVE_APPLY" -> "warning";
                default -> "primary";
            };

            Duration diff = Duration.between(l.getAt(), now);
            String timeAgo;
            if (diff.toMinutes() < 1) {
                timeAgo = "Just now";
            } else if (diff.toMinutes() < 60) {
                timeAgo = diff.toMinutes() + " mins ago";
            } else if (diff.toHours() < 24) {
                timeAgo = diff.toHours() + " hours ago";
            } else if (diff.toDays() == 1) {
                timeAgo = "Yesterday";
            } else {
                timeAgo = diff.toDays() + " days ago";
            }

            list.add(new SafeActivityDto(title, timeAgo, type, l.getAt()));
            if (list.size() >= 5) {
                break;
            }
        }

        return list;
    }
}
