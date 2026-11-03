package com.octopyder.payroll.calendar;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.leave.LeaveRequest;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;
import com.octopyder.payroll.org.Holiday;
import com.octopyder.payroll.org.HolidayRepository;
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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CalendarIntegrationTest extends BaseIntegrationTest {

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
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private BusinessClock businessClock;

    private User admin;
    private User manager;
    private User employee1;
    private User employee2;
    private String adminToken;
    private String managerToken;
    private String employee1Token;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE tasks, task_activities, task_comments, task_attachments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE leave_requests, leave_ledger_entries CASCADE");
        jdbcTemplate.execute("DELETE FROM holidays");
        jdbcTemplate.execute("TRUNCATE TABLE users, employee_profiles CASCADE");

        admin = createUser("CAL_ADM", "admin.cal@example.com", Role.ADMIN, null);
        manager = createUser("CAL_MGR", "mgr.cal@example.com", Role.MANAGER, null);
        employee1 = createUser("CAL_EMP1", "emp1.cal@example.com", Role.EMPLOYEE, manager.getId());
        employee2 = createUser("CAL_EMP2", "emp2.cal@example.com", Role.EMPLOYEE, null); // Other manager / unassigned

        createProfile(admin.getId(), "Admin", "User");
        createProfile(manager.getId(), "Manager", "One");
        createProfile(employee1.getId(), "Alice", "Smith");
        createProfile(employee2.getId(), "Bob", "Jones");

        adminToken = tokenService.generateAccessToken(admin);
        managerToken = tokenService.generateAccessToken(manager);
        employee1Token = tokenService.generateAccessToken(employee1);
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

    private void createProfile(UUID userId, String first, String last) {
        EmployeeProfile p = new EmployeeProfile();
        p.setUserId(userId);
        p.setFirstName(first);
        p.setLastName(last);
        p.setDesignation("Engineer");
        p.setJoiningDate(LocalDate.of(2025, 1, 1));
        profileRepository.save(p);
    }

    @Test
    @DisplayName("Unauthenticated request should return 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/events")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Range exceeding 93 days should return 400 CALENDAR_RANGE_EXCEEDED")
    void rangeExceeding93DaysReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("from", "2026-01-01")
                .param("to", "2026-04-15")) // 105 days
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CALENDAR_RANGE_EXCEEDED"));
    }

    @Test
    @DisplayName("Calendar events should be properly scoped and hide private leave reasons")
    void calendarEventsScopingAndPrivacy() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 7, 10);
        LocalDate d2 = LocalDate.of(2026, 7, 12);

        // 1. Holiday
        Holiday holiday = new Holiday(LocalDate.of(2026, 7, 15), "Independence Day Prep");
        holiday.setCreatedAt(businessClock.instant());
        holidayRepository.save(holiday);

        // 2. Leave for Employee1 (Approved) with private reason
        LeaveRequest l1 = new LeaveRequest();
        l1.setUserId(employee1.getId());
        l1.setLeaveType(LeaveType.CASUAL);
        l1.setStartDate(d1);
        l1.setEndDate(d2);
        l1.setDays(BigDecimal.valueOf(3));
        l1.setReason("Confidential medical surgery");
        l1.setStatus(LeaveStatus.APPROVED);
        l1.setCreatedAt(businessClock.instant());
        leaveRequestRepository.save(l1);

        // 3. Leave for Employee2 (Approved)
        LeaveRequest l2 = new LeaveRequest();
        l2.setUserId(employee2.getId());
        l2.setLeaveType(LeaveType.SICK);
        l2.setStartDate(LocalDate.of(2026, 7, 20));
        l2.setEndDate(LocalDate.of(2026, 7, 21));
        l2.setDays(BigDecimal.valueOf(2));
        l2.setReason("Flu");
        l2.setStatus(LeaveStatus.APPROVED);
        l2.setCreatedAt(businessClock.instant());
        leaveRequestRepository.save(l2);

        // 4. Task for Employee1
        Task t1 = new Task();
        t1.setCode("TSK-C01");
        t1.setTitle("Team Task Alpha");
        t1.setDescription("Alpha task desc");
        t1.setAssigneeId(employee1.getId());
        t1.setCreatedBy(manager.getId());
        t1.setManagerId(manager.getId());
        t1.setStatus(TaskStatus.IN_PROGRESS);
        t1.setPriority(TaskPriority.HIGH);
        t1.setDeadlineAt(Instant.parse("2026-07-14T18:29:59Z")); // in IST: 2026-07-14 23:59:59
        t1.setCreatedAt(businessClock.instant());
        taskRepository.save(t1);

        // 5. Task for Employee2
        Task t2 = new Task();
        t2.setCode("TSK-C02");
        t2.setTitle("Other Task Beta");
        t2.setDescription("Beta task desc");
        t2.setAssigneeId(employee2.getId());
        t2.setCreatedBy(admin.getId());
        t2.setManagerId(admin.getId());
        t2.setStatus(TaskStatus.ASSIGNED);
        t2.setPriority(TaskPriority.MEDIUM);
        t2.setDeadlineAt(Instant.parse("2026-07-18T18:29:59Z"));
        t2.setCreatedAt(businessClock.instant());
        taskRepository.save(t2);

        // ADMIN sees all 5 events: holiday, l1, l2, t1, t2
        mockMvc.perform(get("/api/v1/calendar/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("from", "2026-07-01")
                .param("to", "2026-07-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(5)));

        // MANAGER sees team events: holiday, l1, t1 (3 events), NOT employee2's leave or task
        mockMvc.perform(get("/api/v1/calendar/events")
                .header("Authorization", "Bearer " + managerToken)
                .param("from", "2026-07-01")
                .param("to", "2026-07-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[?(@.type == 'LEAVE')].title").value("On leave: Alice Smith"));

        // EMPLOYEE1 sees own events: holiday, l1, t1 (3 events)
        mockMvc.perform(get("/api/v1/calendar/events")
                .header("Authorization", "Bearer " + employee1Token)
                .param("from", "2026-07-01")
                .param("to", "2026-07-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[?(@.type == 'LEAVE')].title").value("On leave: Alice Smith"));
    }
}
