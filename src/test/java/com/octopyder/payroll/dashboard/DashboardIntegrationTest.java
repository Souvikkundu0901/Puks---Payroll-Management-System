package com.octopyder.payroll.dashboard;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceDayType;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.AttendanceWorkMode;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.leave.LeaveRequest;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import com.octopyder.payroll.task.Task;
import com.octopyder.payroll.task.TaskPriority;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DashboardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeProfileRepository profileRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BusinessClock businessClock;

    private User admin;
    private User manager;
    private User emp1;
    private User emp2;
    private String adminToken;
    private String managerToken;
    private String emp1Token;
    private Department engDept;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE tasks, task_activities, task_comments, task_attachments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE leave_requests, leave_ledger_entries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payroll_records, payroll_runs, payroll_lines, payroll_retentions CASCADE");
        jdbcTemplate.execute("DELETE FROM attendance_records");
        jdbcTemplate.execute("DELETE FROM holidays");
        jdbcTemplate.execute("TRUNCATE TABLE users, employee_profiles, departments CASCADE");

        engDept = new Department();
        engDept.setCode("ENG");
        engDept.setName("Engineering");
        engDept = departmentRepository.save(engDept);

        admin = createUser("DSH_ADM", "admin.dsh@example.com", Role.ADMIN, null);
        manager = createUser("DSH_MGR", "mgr.dsh@example.com", Role.MANAGER, null);
        emp1 = createUser("DSH_E01", "emp1.dsh@example.com", Role.EMPLOYEE, manager.getId());
        emp2 = createUser("DSH_E02", "emp2.dsh@example.com", Role.EMPLOYEE, manager.getId());

        createProfile(admin.getId(), "Admin", "Boss", engDept.getId());
        createProfile(manager.getId(), "Manager", "Lead", engDept.getId());
        createProfile(emp1.getId(), "Alice", "Dev", engDept.getId());
        createProfile(emp2.getId(), "Bob", "Tester", engDept.getId());

        adminToken = tokenService.generateAccessToken(admin);
        managerToken = tokenService.generateAccessToken(manager);
        emp1Token = tokenService.generateAccessToken(emp1);
    }

    private User createUser(String code, String email, Role role, UUID managerId) {
        User u = new User();
        u.setCode(code);
        u.setEmail(email);
        u.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setManagerId(managerId);
        u.setTokenVersion(1);
        u.setMustChangePassword(false);
        u.setCreatedAt(businessClock.instant());
        return userRepository.save(u);
    }

    private void createProfile(UUID userId, String first, String last, UUID deptId) {
        EmployeeProfile p = new EmployeeProfile();
        p.setUserId(userId);
        p.setFirstName(first);
        p.setLastName(last);
        p.setDesignation("Software Engineer");
        p.setDepartmentId(deptId);
        p.setJoiningDate(LocalDate.of(2025, 1, 1));
        profileRepository.save(p);
    }

    @Test
    @DisplayName("Admin dashboard numbers should match underlying data")
    void adminDashboardIntegrity() throws Exception {
        LocalDate today = businessClock.today();

        // 1. Mark attendance for emp1
        AttendanceRecord att = new AttendanceRecord();
        att.setUserId(emp1.getId());
        att.setWorkDate(today);
        att.setDayType(AttendanceDayType.PRESENT);
        att.setWorkMode(AttendanceWorkMode.OFFICE);
        att.setApprovalStatus(AttendanceApprovalStatus.APPROVED);
        att.setCheckInAt(businessClock.instant());
        attendanceRecordRepository.save(att);

        // 2. Create tasks
        Task t0 = createTask("TSK-D00", "Task 0", emp1.getId(), manager.getId(), TaskStatus.ASSIGNED);
        Task t1 = createTask("TSK-D01", "Task 1", emp1.getId(), manager.getId(), TaskStatus.IN_PROGRESS);
        Task t2 = createTask("TSK-D02", "Task 2", emp2.getId(), manager.getId(), TaskStatus.PENDING_APPROVAL);
        Task t3 = createTask("TSK-D03", "Task 3", emp1.getId(), manager.getId(), TaskStatus.APPROVED);

        // 3. Create leave
        LeaveRequest l1 = new LeaveRequest();
        l1.setUserId(emp2.getId());
        l1.setLeaveType(LeaveType.CASUAL);
        l1.setStartDate(today);
        l1.setEndDate(today);
        l1.setDays(BigDecimal.ONE);
        l1.setStatus(LeaveStatus.PENDING);
        l1.setReason("Medical leave");
        l1.setCreatedAt(businessClock.instant());
        leaveRequestRepository.save(l1);

        mockMvc.perform(get("/api/v1/dashboard/admin")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalEmployees").value(2))
            .andExpect(jsonPath("$.attendancePresent").value(1))
            .andExpect(jsonPath("$.activeTasks").value(2)) // ASSIGNED, IN_PROGRESS
            .andExpect(jsonPath("$.pendingLeaves").value(1));
    }

    @Test
    @DisplayName("Manager dashboard numbers should be scoped to team members")
    void managerDashboardScoped() throws Exception {
        LocalDate today = businessClock.today();

        // Create tasks: emp1 (in team), emp2 (in team)
        createTask("TSK-DM0", "Team Task 0", emp1.getId(), manager.getId(), TaskStatus.ASSIGNED);
        createTask("TSK-DM1", "Team Task 1", emp1.getId(), manager.getId(), TaskStatus.IN_PROGRESS);
        createTask("TSK-DM2", "Team Task 2", emp2.getId(), manager.getId(), TaskStatus.PENDING_APPROVAL);

        mockMvc.perform(get("/api/v1/dashboard/manager")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.teamMembersCount").value(2))
            .andExpect(jsonPath("$.activeTasksCount").value(2))
            .andExpect(jsonPath("$.tasksAwaitingCount").value(1));
    }

    @Test
    @DisplayName("Employee dashboard numbers should be scoped to currentUser")
    void employeeDashboardScoped() throws Exception {
        createTask("TSK-DE1", "My Task 1", emp1.getId(), manager.getId(), TaskStatus.IN_PROGRESS);
        createTask("TSK-DE2", "My Task 2", emp1.getId(), manager.getId(), TaskStatus.PENDING_APPROVAL);
        createTask("TSK-DE3", "My Task 3", emp1.getId(), manager.getId(), TaskStatus.APPROVED);
        createTask("TSK-DE4", "Other Task", emp2.getId(), manager.getId(), TaskStatus.APPROVED);

        mockMvc.perform(get("/api/v1/dashboard/employee")
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedTasksCount").value(1))
            .andExpect(jsonPath("$.pendingTasksCount").value(1))
            .andExpect(jsonPath("$.completedTasksCount").value(1));
    }

    @Test
    @DisplayName("Performance test: 125 employees and 245 tasks response under 500ms")
    void performanceTestWith125EmployeesAnd245Tasks() throws Exception {
        List<User> bulkUsers = new ArrayList<>();
        for (int i = 0; i < 125; i++) {
            User u = new User();
            u.setCode("PF_EMP" + String.format("%03d", i));
            u.setEmail("perf" + i + "@example.com");
            u.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
            u.setRole(Role.EMPLOYEE);
            u.setStatus(UserStatus.ACTIVE);
            u.setManagerId(manager.getId());
            u.setTokenVersion(1);
            u.setCreatedAt(businessClock.instant());
            bulkUsers.add(u);
        }
        bulkUsers = userRepository.saveAll(bulkUsers);

        List<Task> bulkTasks = new ArrayList<>();
        for (int i = 0; i < 245; i++) {
            User assignee = bulkUsers.get(i % bulkUsers.size());
            Task t = new Task();
            t.setCode("PF_TSK" + String.format("%03d", i));
            t.setTitle("Perf Task " + i);
            t.setDescription("Performance test task " + i);
            t.setAssigneeId(assignee.getId());
            t.setCreatedBy(manager.getId());
            t.setManagerId(manager.getId());
            t.setStatus(i % 3 == 0 ? TaskStatus.IN_PROGRESS : (i % 3 == 1 ? TaskStatus.PENDING_APPROVAL : TaskStatus.APPROVED));
            t.setPriority(TaskPriority.MEDIUM);
            t.setDeadlineAt(businessClock.instant().plusSeconds(86400 * (i % 10 + 1)));
            t.setCreatedAt(businessClock.instant());
            bulkTasks.add(t);
        }
        taskRepository.saveAll(bulkTasks);

        // Warm up
        mockMvc.perform(get("/api/v1/dashboard/admin").header("Authorization", "Bearer " + adminToken));

        // Timed run
        long start = System.currentTimeMillis();
        mockMvc.perform(get("/api/v1/dashboard/admin").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(500);
    }

    private Task createTask(String code, String title, UUID assigneeId, UUID managerId, TaskStatus status) {
        Task t = new Task();
        t.setCode(code);
        t.setTitle(title);
        t.setDescription("Task desc");
        t.setAssigneeId(assigneeId);
        t.setCreatedBy(managerId);
        t.setManagerId(managerId);
        t.setStatus(status);
        t.setPriority(TaskPriority.HIGH);
        t.setDeadlineAt(businessClock.instant().plusSeconds(86400));
        t.setCreatedAt(businessClock.instant());
        return taskRepository.save(t);
    }
}
