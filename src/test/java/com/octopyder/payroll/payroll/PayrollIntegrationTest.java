package com.octopyder.payroll.payroll;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.AttendanceWorkMode;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.EmployeeDeductionRepository;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import com.octopyder.payroll.task.TaskPriority;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PayrollIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private com.octopyder.payroll.payroll.service.PayrollService payrollService;

    private User adminUser;
    private User managerUser;
    private User employeeUser;
    private String suffix;

    private String adminToken;
    private String managerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        payrollService.clearRateLimits();
        jdbcTemplate.update("DELETE FROM task_activities");
        jdbcTemplate.update("DELETE FROM task_comments");
        jdbcTemplate.update("DELETE FROM task_attachments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM leave_requests");
        jdbcTemplate.update("DELETE FROM attendance_records");
        jdbcTemplate.update("DELETE FROM employee_deductions");
        jdbcTemplate.update("DELETE FROM employee_profiles WHERE user_id IN (SELECT id FROM users WHERE code LIKE '%_PAY_%')");
        jdbcTemplate.update("DELETE FROM refresh_tokens");

        suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = createTestUser("ADM_PAY_" + suffix, "adm_pay_" + suffix + "@octopyder.com", Role.ADMIN, null, new BigDecimal("100000.00"), LocalDate.of(2025, 1, 1), null);
        adminToken = tokenService.generateAccessToken(adminUser);

        managerUser = createTestUser("MGR_PAY_" + suffix, "mgr_pay_" + suffix + "@octopyder.com", Role.MANAGER, null, new BigDecimal("80000.00"), LocalDate.of(2025, 1, 1), null);
        managerToken = tokenService.generateAccessToken(managerUser);

        employeeUser = createTestUser("EMP_PAY_" + suffix, "emp_pay_" + suffix + "@octopyder.com", Role.EMPLOYEE, managerUser.getId(), new BigDecimal("60000.00"), LocalDate.of(2026, 1, 1), null);
        employeeToken = tokenService.generateAccessToken(employeeUser);
    }

    @AfterEach
    void tearDown() {
        businessClock.reset();
        payrollService.clearRateLimits();
    }

    private User createTestUser(String code, String email, Role role, UUID managerId, BigDecimal baseSalary, LocalDate joiningDate, LocalDate exitDate) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)",
            userId, code, email, "$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq", role.name(), managerId
        );
        jdbcTemplate.update(
            "INSERT INTO employee_profiles (user_id, first_name, last_name, designation, employment_type, joining_date, exit_date, base_salary, created_at, updated_at) " +
            "VALUES (?, 'Test', 'User', 'Engineer', 'FULL_TIME', ?, ?, ?, now(), now())",
            userId, joiningDate, exitDate, baseSalary
        );
        User user = new User();
        user.setId(userId);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setManagerId(managerId);
        return user;
    }

    private void createTestTask(String code, String title, UUID assigneeId, UUID managerId, Instant deadlineAt, TaskStatus status, int progress) {
        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO tasks (id, code, title, description, priority, assignee_id, manager_id, created_by, deadline_at, status, progress, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, 'Test Description', 'MEDIUM', ?, ?, ?, ?, ?, ?, 0, now(), now())",
            taskId, code, title, assigneeId, managerId, managerId, Timestamp.from(deadlineAt), status.name(), progress
        );
    }

    private void createTestAttendance(UUID userId, LocalDate workDate, Instant checkIn, Instant checkOut, AttendanceApprovalStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO attendance_records (id, user_id, work_date, check_in_at, check_out_at, work_mode, is_late, day_type, approval_status, missed_checkout, low_accuracy, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, 'OFFICE', false, 'PRESENT', ?, false, false, 0, now(), now())",
            id, userId, Date.valueOf(workDate), Timestamp.from(checkIn), checkOut != null ? Timestamp.from(checkOut) : null, status.name()
        );
    }

    private void createTestLeave(UUID userId, UUID managerId, LeaveType type, LocalDate start, LocalDate end, int days, LeaveStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO leave_requests (id, user_id, manager_id, leave_type, start_date, end_date, days, reason, status, version, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'Leave Reason', ?, 0, now(), now())",
            id, userId, managerId, type.name(), Date.valueOf(start), Date.valueOf(end), days, status.name()
        );
    }

    private void createTestDeduction(UUID userId, String type, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO employee_deductions (id, user_id, type, monthly_amount, effective_from, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, '2026-01-01', now(), now())",
            id, userId, type, amount
        );
    }

    @Test
    @DisplayName("Security: Only Admin can preview payroll; others get 401 or 403")
    void testPayrollPreviewSecurity() throws Exception {
        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        // 1. Unauthenticated -> 401
        mockMvc.perform(post("/api/v1/payroll/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());

        // 2. Employee -> 403
        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + employeeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        // 3. Manager -> 403
        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Month validation: Invalid month format returns 400 PAYROLL_INVALID_MONTH")
    void testPayrollInvalidMonthFormat() throws Exception {
        String body = """
            {
                "employeeId": "%s",
                "month": "invalid-month",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAYROLL_INVALID_MONTH"));

        // Year outside 2000-2100
        String pastYear = """
            {
                "employeeId": "%s",
                "month": "1999-12",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());
        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pastYear))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PAYROLL_INVALID_MONTH"));
    }

    @Test
    @DisplayName("Eligibility: Employee joining after payroll month or exited before month returns 422")
    void testEmploymentPeriodEligibility() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Employee joined in August 2026, requested for July 2026
        jdbcTemplate.update("UPDATE employee_profiles SET joining_date = '2026-08-01', exit_date = NULL WHERE user_id = ?", employeeUser.getId());
        String bodyJuly = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJuly))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_USER_NOT_ELIGIBLE"));

        // Employee exited in June 2026, requested for July 2026
        jdbcTemplate.update("UPDATE employee_profiles SET joining_date = '2026-01-01', exit_date = '2026-06-30' WHERE user_id = ?", employeeUser.getId());
        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJuly))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_USER_NOT_ELIGIBLE"));

        // Restore joining date
        jdbcTemplate.update("UPDATE employee_profiles SET joining_date = '2026-01-01', exit_date = NULL WHERE user_id = ?", employeeUser.getId());
    }

    @Test
    @DisplayName("Audit: Generating preview records PAYROLL_PREVIEW audit log entry")
    void testPayrollPreviewAuditLogging() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": true
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'PAYROLL_PREVIEW' AND entity_type = 'PAYROLL' AND entity_id = ?",
            Integer.class,
            employeeUser.getId().toString()
        );
        org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Rate limit: Exceeding 60 preview requests in a minute triggers 429 RATE_LIMITED")
    void testPayrollPreviewRateLimiting() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": true
            }
            """.formatted(employeeUser.getId());

        // Consume all 60 tokens
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(post("/api/v1/payroll/preview")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk());
        }

        // 61st request triggers rate limit
        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Cutoff rule: Running before month end returns 422 PAYROLL_MONTH_NOT_ENDED unless overridden")
    void testPayrollMonthNotEnded() throws Exception {
        // Today is July 15, 2026
        businessClock.setFixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        settingsService.updateGroup("payroll", Map.of("payroll.allowMidMonthGeneration", false), adminUser.getId());

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_MONTH_NOT_ENDED"));

        // When allowMidMonthGeneration is enabled, returns 200 with cutoffDate = today (July 15)
        settingsService.updateGroup("payroll", Map.of("payroll.allowMidMonthGeneration", true), adminUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cutoffDate").value("2026-07-15"));
    }

    @Test
    @DisplayName("Pending items: Block generation with 422 PAYROLL_PENDING_ITEMS unless treatPendingAsPresent=true")
    void testPayrollPendingItemsBlockGeneration() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Add a pending attendance record in July
        createTestAttendance(
            employeeUser.getId(),
            LocalDate.of(2026, 7, 20),
            Instant.parse("2026-07-20T09:00:00Z"),
            Instant.parse("2026-07-20T18:00:00Z"),
            AttendanceApprovalStatus.PENDING
        );

        // Add a pending leave request overlapping July
        createTestLeave(
            employeeUser.getId(),
            managerUser.getId(),
            LeaveType.CASUAL,
            LocalDate.of(2026, 7, 25),
            LocalDate.of(2026, 7, 26),
            2,
            LeaveStatus.PENDING
        );

        String bodyWithoutOverride = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithoutOverride))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_PENDING_ITEMS"))
            .andExpect(jsonPath("$.pendingAttendance", hasSize(1)))
            .andExpect(jsonPath("$.pendingLeave", hasSize(1)));

        // With treatPendingAsPresent = true, passes cleanly
        String bodyWithOverride = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": true
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithOverride))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Eligibility: Inactive user or user without positive salary returns 422")
    void testEligibilityChecks() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // User without positive salary
        User noSalaryUser = createTestUser("EMP_PAY_NOSAL", "nosalary@octopyder.com", Role.EMPLOYEE, managerUser.getId(), BigDecimal.ZERO, LocalDate.of(2026, 1, 1), null);

        String bodyNoSal = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(noSalaryUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyNoSal))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_NO_SALARY"));

        // Soft-deleted user
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", noSalaryUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyNoSal))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYROLL_USER_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("Assembler Integration Test: Seeded month with 10 tasks (7 approved), 1 unpaid leave day, 1 absent day gives hand-computed numbers")
    void testSeededMonthHandComputedResult() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // 1. Statutory deductions: TDS 1000, PF 2100, PT 200 = 3300
        createTestDeduction(employeeUser.getId(), "TDS", new BigDecimal("1000.00"));
        createTestDeduction(employeeUser.getId(), "PF", new BigDecimal("2100.00"));
        createTestDeduction(employeeUser.getId(), "PT", new BigDecimal("200.00"));

        // 2. Seed 10 tasks due in July: 7 APPROVED, 3 IN_PROGRESS
        for (int i = 1; i <= 10; i++) {
            Instant deadline = LocalDate.of(2026, 7, 10 + i).atTime(17, 0).atZone(ist).toInstant();
            if (i <= 7) {
                createTestTask("TSK_PAY_" + suffix + "_" + i, "July Task " + i, employeeUser.getId(), managerUser.getId(), deadline, TaskStatus.APPROVED, 100);
            } else {
                createTestTask("TSK_PAY_" + suffix + "_" + i, "July Task " + i, employeeUser.getId(), managerUser.getId(), deadline, TaskStatus.IN_PROGRESS, 50);
            }
        }

        // 3. Seed 1 approved UNPAID leave day on July 10 (Friday)
        createTestLeave(employeeUser.getId(), managerUser.getId(), LeaveType.UNPAID, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 10), 1, LeaveStatus.APPROVED);

        // 4. Seed Attendance:
        // July 2026 has 31 days.
        // We leave July 14 (Tuesday) as completely absent (no record, no leave).
        // For all other weekdays in July (except July 10 which is approved leave, and July 14 which is absent),
        // we add an APPROVED attendance record!
        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 7, 31);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            if (d.equals(LocalDate.of(2026, 7, 10)) || d.equals(LocalDate.of(2026, 7, 14))) {
                continue; // July 10 is unpaid leave; July 14 is the 1 absent day!
            }
            createTestAttendance(
                employeeUser.getId(),
                d,
                d.atTime(9, 30).atZone(ist).toInstant(),
                d.atTime(18, 0).atZone(ist).toInstant(),
                AttendanceApprovalStatus.APPROVED
            );
        }

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": false
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            // Inputs & Tasks
            .andExpect(jsonPath("$.gross").value(60000.00))
            .andExpect(jsonPath("$.tasksTotal").value(10))
            .andExpect(jsonPath("$.tasksApproved").value(7))
            .andExpect(jsonPath("$.completionPct").value(70.00))
            .andExpect(jsonPath("$.retentionDays").value(0))
            .andExpect(jsonPath("$.retentionAmount").value(0.00))
            // Absent & Unpaid
            .andExpect(jsonPath("$.absentDays").value(1))
            .andExpect(jsonPath("$.absentDeduction").value(2000.00))
            .andExpect(jsonPath("$.unpaidLeaveDays").value(1))
            .andExpect(jsonPath("$.unpaidLeaveDeduction").value(2000.00))
            .andExpect(jsonPath("$.totalUnpaidDeduction").value(4000.00))
            // Statutory
            .andExpect(jsonPath("$.tds").value(1000.00))
            .andExpect(jsonPath("$.pf").value(2100.00))
            .andExpect(jsonPath("$.pt").value(200.00))
            .andExpect(jsonPath("$.statutoryTotal").value(3300.00))
            .andExpect(jsonPath("$.totalDeductions").value(7300.00)) // 4000 + 3300 = 7300
            // Net = 60,000 - 7,300 = 52,700.00
            .andExpect(jsonPath("$.net").value(52700.00))
            // Tranches & Released
            .andExpect(jsonPath("$.defaultReleaseAmount").value(30000.00))
            .andExpect(jsonPath("$.taskLinkedAmount").value(30000.00))
            .andExpect(jsonPath("$.taskUnlockedAmount").value(30000.00))
            .andExpect(jsonPath("$.releasedPct").value(100.00))
            .andExpect(jsonPath("$.tranche1").value(30000.00))
            .andExpect(jsonPath("$.tranche2").value(22700.00))
            .andExpect(jsonPath("$.isNetNegativeClamped").value(false))
            // D24 Breakup
            .andExpect(jsonPath("$.earningsBreakup.Basic").value(30000.00))
            .andExpect(jsonPath("$.earningsBreakup.HRA").value(12000.00));
    }

    @Test
    @DisplayName("Timezone edge: Task deadlines right at 23:59:59 IST belong to month; 00:00:01 next day belong to next month")
    void testTaskDeadlineTimezoneEdge() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Task 1: Deadline 2026-07-31T18:29:59Z -> IST = 2026-07-31T23:59:59 (In July)
        createTestTask("TSK_TZ_1_" + suffix, "Task July Edge", employeeUser.getId(), managerUser.getId(), Instant.parse("2026-07-31T18:29:59Z"), TaskStatus.APPROVED, 100);

        // Task 2: Deadline 2026-07-31T18:30:01Z -> IST = 2026-08-01T00:00:01 (In August!)
        createTestTask("TSK_TZ_2_" + suffix, "Task August Edge", employeeUser.getId(), managerUser.getId(), Instant.parse("2026-07-31T18:30:01Z"), TaskStatus.APPROVED, 100);

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": true
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            // Only taskJuly should be counted in July!
            .andExpect(jsonPath("$.tasksTotal").value(1))
            .andExpect(jsonPath("$.tasksApproved").value(1));
    }

    @Test
    @DisplayName("D27 Proration: Employee joined mid-month has non-employed working days counted as unpaid days")
    void testD27MidMonthJoinerProration() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // Employee joined on July 20, 2026.
        jdbcTemplate.update("UPDATE employee_profiles SET joining_date = '2026-07-20' WHERE user_id = ?", employeeUser.getId());

        // July 1 to July 19 working days:
        // July 1 (Wed) to July 17 (Fri) has 13 weekdays (July 1,2,3, 6,7,8,9,10, 13,14,15,16,17).
        // Plus they attend all days after joining (July 20 to July 31).
        LocalDate start = LocalDate.of(2026, 7, 20);
        LocalDate end = LocalDate.of(2026, 7, 31);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            createTestAttendance(
                employeeUser.getId(),
                d,
                d.atTime(9, 30).atZone(ist).toInstant(),
                d.atTime(18, 0).atZone(ist).toInstant(),
                AttendanceApprovalStatus.APPROVED
            );
        }

        String body = """
            {
                "employeeId": "%s",
                "month": "2026-07",
                "treatPendingAsPresent": true
            }
            """.formatted(employeeUser.getId());

        mockMvc.perform(post("/api/v1/payroll/preview")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            // Exactly 13 non-employed working days counted as unpaid absent days per D27!
            .andExpect(jsonPath("$.absentDays").value(13))
            // 60000 * 13 / 30 = 26,000.00 deduction
            .andExpect(jsonPath("$.absentDeduction").value(26000.00));
    }
}
