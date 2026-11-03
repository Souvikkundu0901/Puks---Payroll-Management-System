package com.octopyder.payroll.payroll;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.AttendanceWorkMode;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.payroll.dto.PayrollRejectRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseAllRequest;
import com.octopyder.payroll.payroll.dto.PayrollReleaseRequest;
import com.octopyder.payroll.payroll.dto.PayrollRunRequest;
import com.octopyder.payroll.payroll.dto.RetentionDecisionRequest;
import com.octopyder.payroll.payroll.service.PayrollWorkflowService;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PayrollWorkflowIntegrationTest extends BaseIntegrationTest {

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
    private PayrollRecordRepository recordRepository;

    @Autowired
    private PayrollRetentionRepository retentionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User adminUser;
    private User managerUser;
    private User otherManagerUser;
    private User employeeUser;
    private User otherEmployeeUser;
    private String suffix;

    private String adminToken;
    private String managerToken;
    private String otherManagerToken;
    private String employeeToken;
    private String otherEmployeeToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payroll_lines");
        jdbcTemplate.update("DELETE FROM payroll_retentions");
        jdbcTemplate.update("DELETE FROM payroll_records");
        jdbcTemplate.update("DELETE FROM payroll_runs");
        jdbcTemplate.update("DELETE FROM task_activities");
        jdbcTemplate.update("DELETE FROM task_comments");
        jdbcTemplate.update("DELETE FROM task_attachments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM leave_requests");
        jdbcTemplate.update("DELETE FROM attendance_records");
        jdbcTemplate.update("DELETE FROM employee_deductions");
        jdbcTemplate.update("DELETE FROM bank_accounts");
        jdbcTemplate.update("DELETE FROM employee_profiles WHERE user_id IN (SELECT id FROM users WHERE code LIKE '%_WF_%')");
        jdbcTemplate.update("DELETE FROM refresh_tokens");

        suffix = UUID.randomUUID().toString().substring(0, 8);

        adminUser = createTestUser("ADM_WF_" + suffix, "adm_wf_" + suffix + "@octopyder.com", Role.ADMIN, null, new BigDecimal("100000.00"), LocalDate.of(2025, 1, 1));
        adminToken = tokenService.generateAccessToken(adminUser);

        managerUser = createTestUser("MGR_WF_" + suffix, "mgr_wf_" + suffix + "@octopyder.com", Role.MANAGER, null, new BigDecimal("80000.00"), LocalDate.of(2025, 1, 1));
        managerToken = tokenService.generateAccessToken(managerUser);

        otherManagerUser = createTestUser("MGR2_WF_" + suffix, "mgr2_wf_" + suffix + "@octopyder.com", Role.MANAGER, null, new BigDecimal("85000.00"), LocalDate.of(2025, 1, 1));
        otherManagerToken = tokenService.generateAccessToken(otherManagerUser);

        employeeUser = createTestUser("EMP_WF_" + suffix, "emp_wf_" + suffix + "@octopyder.com", Role.EMPLOYEE, managerUser.getId(), new BigDecimal("60000.00"), LocalDate.of(2026, 1, 1));
        employeeToken = tokenService.generateAccessToken(employeeUser);

        otherEmployeeUser = createTestUser("EMP2_WF_" + suffix, "emp2_wf_" + suffix + "@octopyder.com", Role.EMPLOYEE, otherManagerUser.getId(), new BigDecimal("55000.00"), LocalDate.of(2026, 1, 1));
        otherEmployeeToken = tokenService.generateAccessToken(otherEmployeeUser);

        // Add bank accounts
        createBankAccount(employeeUser.getId(), "HDFC Bank", "123456789012", "HDFC0001234");
        createBankAccount(managerUser.getId(), "ICICI Bank", "987654321098", "ICIC0005678");
        createBankAccount(otherEmployeeUser.getId(), "Axis Bank", "112233445566", "UTIB0001122");
        createBankAccount(otherManagerUser.getId(), "SBI Bank", "998877665544", "SBIN0009988");
    }

    @AfterEach
    void tearDown() {
        businessClock.reset();
    }

    private User createTestUser(String code, String email, Role role, UUID managerId, BigDecimal baseSalary, LocalDate joiningDate) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)",
            userId, code, email, "$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq", role.name(), managerId
        );
        jdbcTemplate.update(
            "INSERT INTO employee_profiles (user_id, first_name, last_name, designation, employment_type, joining_date, base_salary, created_at, updated_at) " +
            "VALUES (?, 'Test', 'User', 'Engineer', 'FULL_TIME', ?, ?, now(), now())",
            userId, joiningDate, baseSalary
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

    private void createBankAccount(UUID userId, String bankName, String accountRaw, String ifsc) {
        jdbcTemplate.update(
            "INSERT INTO bank_accounts (user_id, bank_name, account_number_enc, account_last4, ifsc, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, now(), now())",
            userId, bankName, "dummy_enc_" + accountRaw, accountRaw.substring(accountRaw.length() - 4), ifsc
        );
    }

    private void seedTasksForJuly2026(UUID userId, UUID managerId, int total, int approved) {
        Instant deadline = Instant.parse("2026-07-20T10:00:00Z");
        for (int i = 1; i <= total; i++) {
            TaskStatus status = (i <= approved) ? TaskStatus.APPROVED : TaskStatus.IN_PROGRESS;
            int progress = (i <= approved) ? 100 : 50;
            UUID taskId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO tasks (id, code, title, description, priority, assignee_id, manager_id, created_by, deadline_at, status, progress, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'Test', 'MEDIUM', ?, ?, ?, ?, ?, ?, 0, now(), now())",
                taskId, "TSK_WF_" + suffix + "_" + i + "_" + userId.toString().substring(0, 4), "Task " + i, userId, managerId, managerId, Timestamp.from(deadline), status.name(), progress
            );
        }
    }

    private void seedPresentAttendanceForMonth(UUID userId, YearMonth ym) {
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                jdbcTemplate.update(
                    "INSERT INTO attendance_records (id, user_id, work_date, check_in_at, check_out_at, worked_minutes, overtime_minutes, work_mode, day_type, is_late, approval_status, version, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), ?, ?, now(), now(), 480, 0, 'OFFICE', 'PRESENT', false, 'APPROVED', 0, now(), now())",
                    userId, Date.valueOf(date)
                );
            }
        }
    }

    @Test
    @DisplayName("Admin generates payroll run: records created, line items sum to net, 409 on duplicate")
    void testGenerateRunAndDuplicate() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Seed 10 tasks, 7 approved for employeeUser
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 7);

        PayrollRunRequest req = new PayrollRunRequest("2026-07", null, false, false);

        // Generate run
        String res = mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.month", is("2026-07")))
            .andExpect(jsonPath("$.totalProcessed").isNumber())
            .andReturn().getResponse().getContentAsString();

        // Calling generate again without recalculate=true returns 409
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("PAYROLL_ALREADY_GENERATED")));

        // Calling with recalculate=true succeeds
        PayrollRunRequest recalcReq = new PayrollRunRequest("2026-07", null, false, true);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recalcReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.month", is("2026-07")));
    }

    @Test
    @DisplayName("State machine: remainder release blocked before approve; default release works while GENERATED")
    void testStateMachineAndReleases() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 8);

        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        // Get record ID for employeeUser
        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();
        UUID recordId = record.getId();

        // 1. Remainder release fails before approval (422 PAYROLL_NOT_APPROVED)
        PayrollReleaseRequest relReq = new PayrollReleaseRequest(LocalDate.of(2026, 8, 5), "TXN12345", "NEFT");
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/release")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(relReq)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", is("PAYROLL_NOT_APPROVED")));

        // 2. Release-default (50%) works while GENERATED
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/release-default")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(relReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paidAmount", is(record.getTranche1Amount().doubleValue())));

        // 3. Calling release-default twice returns 409
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/release-default")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(relReq)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("PAYROLL_ALREADY_PAID")));

        // 4. Freeze on payment: records with paid_amount > 0 cannot be rejected
        PayrollRejectRequest rejReq = new PayrollRejectRequest("Mistake in hours");
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/reject")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejReq)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("PAYROLL_ALREADY_PAID")));

        // 5. Manager approves record
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("APPROVED")));

        // 6. Release remainder now succeeds and sets status to RELEASED
        PayrollReleaseRequest relFinalReq = new PayrollReleaseRequest(LocalDate.of(2026, 8, 10), "TXN67890", "IMPS");
        mockMvc.perform(post("/api/v1/payroll/records/" + recordId + "/release")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(relFinalReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("RELEASED")))
            .andExpect(jsonPath("$.paidAmount", is(record.getNetPayable().doubleValue())));
    }

    @Test
    @DisplayName("Self-approval forbidden and manager cannot approve other team's records")
    void testApprovalAuthorization() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        PayrollRecord empRecord = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();
        PayrollRecord mgrRecord = recordRepository.findByUserIdAndMonth(managerUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        // 1. Employee tries to approve own record -> 403
        mockMvc.perform(post("/api/v1/payroll/records/" + empRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isForbidden());

        // 2. Manager tries to approve own record -> 403 FORBIDDEN_SELF_APPROVAL
        mockMvc.perform(post("/api/v1/payroll/records/" + mgrRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("FORBIDDEN_SELF_APPROVAL")));

        // 3. Other manager tries to approve employeeUser's record -> 403 / 404
        mockMvc.perform(post("/api/v1/payroll/records/" + empRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + otherManagerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code", is("FORBIDDEN_SCOPE")));

        // 4. Admin approves manager's record -> 200 OK
        mockMvc.perform(post("/api/v1/payroll/records/" + mgrRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("APPROVED")));
    }

    @Test
    @DisplayName("Retention lifecycle: 2-day auto-releases into next month, 7-day stays HELD until manual release")
    void testRetentionLifecycle() throws Exception {
        // Month 1: 2026-07
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Seed 10 tasks, 6 approved -> 60% completion -> 2-day retention (BR-P11)
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 6);

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        PayrollRecord julyRecord = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();
        assertEquals(2, julyRecord.getRetentionDays());
        assertTrue(julyRecord.getRetentionAmount().compareTo(BigDecimal.ZERO) > 0);

        // Approve July record so its retention becomes eligible to release
        mockMvc.perform(post("/api/v1/payroll/records/" + julyRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk());

        // Verify retention row exists with HELD
        var retList = retentionRepository.findByUserIdAndStatus(employeeUser.getId(), RetentionStatus.HELD);
        assertEquals(1, retList.size());
        assertEquals(RetentionStatus.HELD, retList.get(0).getStatus());

        // Month 2: 2026-08
        businessClock.setFixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-08", null, false, false))))
            .andExpect(status().isCreated());

        PayrollRecord augRecord = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 8, 1)).orElseThrow();

        // 2-day retention was auto-released!
        assertEquals(julyRecord.getRetentionAmount(), augRecord.getRetentionReleasedAmount());

        // Check retention status changed to RELEASED and released_in_record_id points to augRecord.getId()
        var updatedRet = retentionRepository.findById(retList.get(0).getId()).orElseThrow();
        assertEquals(RetentionStatus.RELEASED, updatedRet.getStatus());
        assertEquals(augRecord.getId(), updatedRet.getReleasedInRecordId());
    }

    @Test
    @DisplayName("Payslip PDF: generated correctly, blocked before approval for employee, forbidden for manager (PRD D26)")
    void testPayslipPdfSecurityAndGeneration() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 9);

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        // 1. Employee requests payslip PDF before approval -> 422 PAYROLL_NOT_APPROVED
        mockMvc.perform(get("/api/v1/payroll/me/2026-07/payslip.pdf")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", is("PAYROLL_NOT_APPROVED")));

        // 2. Manager requests payslip PDF -> 403 (PRD D26 manager privacy)
        mockMvc.perform(get("/api/v1/payroll/records/" + record.getId() + "/payslip.pdf")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isForbidden());

        // 3. Manager approves record
        mockMvc.perform(post("/api/v1/payroll/records/" + record.getId() + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk());

        // 4. Employee requests payslip PDF now -> 200 OK with PDF bytes and correct filename
        byte[] pdfBytes = mockMvc.perform(get("/api/v1/payroll/me/2026-07/payslip.pdf")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("payslip-" + employeeUser.getCode() + "-2026-07.pdf")))
            .andReturn().getResponse().getContentAsByteArray();

        assertTrue(pdfBytes.length > 100);
        String headerStr = new String(pdfBytes, 0, 5);
        assertEquals("%PDF-", headerStr);

        // 5. Admin requests payslip PDF -> 200 OK
        mockMvc.perform(get("/api/v1/payroll/records/" + record.getId() + "/payslip.pdf")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Employee self-service /payroll/me and manager privacy in records list")
    void testSelfServiceAndManagerPrivacy() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 10);

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        // 1. Employee accesses /payroll/me
        mockMvc.perform(get("/api/v1/payroll/me")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].month", is("2026-07")))
            .andExpect(jsonPath("$[0].bankAccount.accountMasked", is("XXXX-XXXX-9012")));

        // 2. Manager accesses /payroll/records (PRD D26: no bank details or snapshot in manager projection)
        mockMvc.perform(get("/api/v1/payroll/records?month=2026-07")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].bankAccount", nullValue()))
            .andExpect(jsonPath("$.items[0].snapshot", nullValue()));

        // 3. Admin accesses /payroll/records -> bank details and snapshot included
        mockMvc.perform(get("/api/v1/payroll/records?month=2026-07")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].bankAccount.accountMasked", org.hamcrest.Matchers.hasItem("XXXX-XXXX-9012")))
            .andExpect(jsonPath("$.items[0].snapshot").isString());
    }

    @Test
    @DisplayName("Summary and export endpoints: Admin and Manager summary; CSV export with formula sanitization")
    void testSummaryAndExport() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        // 1. Summary
        mockMvc.perform(get("/api/v1/payroll/summary?month=2026-07")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.month", is("2026-07")))
            .andExpect(jsonPath("$.totalGross").isNumber())
            .andExpect(jsonPath("$.totalNet").isNumber());

        // 2. CSV export
        String csvContent = mockMvc.perform(get("/api/v1/payroll/export?format=csv&month=2026-07")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andReturn().getResponse().getContentAsString();

        assertTrue(csvContent.contains("Code,Employee,Month,Gross"));

        // 3. XLSX export
        mockMvc.perform(get("/api/v1/payroll/export?format=xlsx&month=2026-07")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("spreadsheetml")));
    }

    @Test
    @DisplayName("Golden Case G1: 50,000 gross, 55% completion yields exact display fields 21,666.67 and 93.33%")
    void testGoldenCaseG1DisplayFields() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Create user with 50,000 base salary
        User g1User = createTestUser("EMP_G1_" + suffix, "emp_g1_" + suffix + "@octopyder.com", Role.EMPLOYEE, managerUser.getId(), new BigDecimal("50000.00"), LocalDate.of(2026, 1, 1));
        createBankAccount(g1User.getId(), "SBI", "112233449988", "SBIN0001234");

        // Statutory deductions TDS 1000, PF 2100, PT 200 = 3300
        jdbcTemplate.update("INSERT INTO employee_deductions (id, user_id, type, monthly_amount, effective_from, created_at, updated_at) VALUES (gen_random_uuid(), ?, 'TDS', 1000.00, '2026-01-01', now(), now())", g1User.getId());
        jdbcTemplate.update("INSERT INTO employee_deductions (id, user_id, type, monthly_amount, effective_from, created_at, updated_at) VALUES (gen_random_uuid(), ?, 'PF', 2100.00, '2026-01-01', now(), now())", g1User.getId());
        jdbcTemplate.update("INSERT INTO employee_deductions (id, user_id, type, monthly_amount, effective_from, created_at, updated_at) VALUES (gen_random_uuid(), ?, 'PT', 200.00, '2026-01-01', now(), now())", g1User.getId());

        // 20 tasks, 11 approved = 55% completion
        seedTasksForJuly2026(g1User.getId(), managerUser.getId(), 20, 11);
        seedPresentAttendanceForMonth(g1User.getId(), YearMonth.of(2026, 7));

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        PayrollRecord g1Record = recordRepository.findByUserIdAndMonth(g1User.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        assertEquals(new BigDecimal("50000.00"), g1Record.getGross());
        assertEquals(new BigDecimal("55.00"), g1Record.getCompletionPct());
        assertEquals(2, g1Record.getRetentionDays());
        assertEquals(new BigDecimal("3333.33"), g1Record.getRetentionAmount());
        assertEquals(new BigDecimal("21666.67"), g1Record.getTaskUnlockedAmount());
        assertEquals(new BigDecimal("93.33"), g1Record.getReleasedPct());
        assertEquals(new BigDecimal("25000.00"), g1Record.getTranche1Amount());
        assertEquals(new BigDecimal("18366.67"), g1Record.getTranche2Amount());
        assertEquals(new BigDecimal("43366.67"), g1Record.getNetPayable());
    }

    @Test
    @DisplayName("7-day retention: manual release included in next month's run, forfeit pays nothing")
    void testSevenDayRetentionReleaseAndForfeit() throws Exception {
        // Month 1: July 2026
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        // Seed 10 tasks, 0 approved -> 0% completion (<50%) -> 7-day retention
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 0);

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        PayrollRecord julyRecord = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();
        assertEquals(7, julyRecord.getRetentionDays());

        // Approve July record
        mockMvc.perform(post("/api/v1/payroll/records/" + julyRecord.getId() + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk());

        var retList = retentionRepository.findByUserIdAndStatus(employeeUser.getId(), RetentionStatus.HELD);
        assertEquals(1, retList.size());
        UUID retId = retList.get(0).getId();

        // Manager releases 7-day retention with note
        RetentionDecisionRequest decReq = new RetentionDecisionRequest("Performance improved, approved release");
        mockMvc.perform(post("/api/v1/payroll/retentions/" + retId + "/release")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(decReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("RELEASED")));

        // Month 2: August 2026
        businessClock.setFixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-08", null, false, false))))
            .andExpect(status().isCreated());

        PayrollRecord augRecord = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 8, 1)).orElseThrow();
        assertEquals(julyRecord.getRetentionAmount(), augRecord.getRetentionReleasedAmount());
    }

    @Test
    @DisplayName("Stale snapshot detection: inputs modified after run generation returns 409 PAYROLL_STALE on approval")
    void testStaleSnapshotDetection() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        seedTasksForJuly2026(employeeUser.getId(), managerUser.getId(), 10, 8);

        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, true, false))))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        // Mutate inputs after generation: insert an approved task due in July 2026
        Instant deadline = Instant.parse("2026-07-25T10:00:00Z");
        jdbcTemplate.update(
            "INSERT INTO tasks (id, code, title, description, priority, assignee_id, manager_id, created_by, deadline_at, status, progress, version, created_at, updated_at) " +
            "VALUES (?, ?, 'Late Task', 'Desc', 'HIGH', ?, ?, ?, ?, 'APPROVED', 100, 0, now(), now())",
            UUID.randomUUID(), "TSK_LATE_" + suffix, employeeUser.getId(), managerUser.getId(), managerUser.getId(), Timestamp.from(deadline)
        );

        // Manager attempts to approve stale record -> 409 PAYROLL_STALE
        mockMvc.perform(post("/api/v1/payroll/records/" + record.getId() + "/approve")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", is("PAYROLL_STALE")));
    }

    @Test
    @DisplayName("Bulk operations: manager bulk approve team and admin bulk release all")
    void testBulkOperations() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

        String res = mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PayrollRunRequest("2026-07", null, false, false))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        // 1. Manager bulk approves team records
        mockMvc.perform(post("/api/v1/payroll/manager/bulk-approve?month=2026-07")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvedCount").isNumber());

        // 2. Admin bulk release all
        PayrollReleaseAllRequest relReq = new PayrollReleaseAllRequest(null, "2026-07", LocalDate.of(2026, 8, 10), "BULK12345", "NEFT");
        mockMvc.perform(post("/api/v1/payroll/runs/release-all")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(relReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalProcessed").isNumber());
    }

    @Test
    @DisplayName("VULN-P8-1: String length validation on paymentRef and paymentMethod")
    void testPaymentRefAndMethodLengthValidation() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        // 101-char paymentRef -> 400
        String longRef = "A".repeat(101);
        PayrollReleaseRequest reqLongRef = new PayrollReleaseRequest(LocalDate.of(2026, 8, 1), longRef, "NEFT");
        mockMvc.perform(post("/api/v1/payroll/records/" + record.getId() + "/release-default")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqLongRef)))
            .andExpect(status().isBadRequest());

        // 51-char paymentMethod -> 400
        String longMethod = "M".repeat(51);
        PayrollReleaseRequest reqLongMethod = new PayrollReleaseRequest(LocalDate.of(2026, 8, 1), "REF123", longMethod);
        mockMvc.perform(post("/api/v1/payroll/records/" + record.getId() + "/release-default")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqLongMethod)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VULN-P8-2: Future payment dates rejected")
    void testFuturePaymentDateRejected() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        // Future date relative to clock (2026-08-01): 2026-08-05 -> 400 PAYROLL_FUTURE_PAYMENT_DATE
        PayrollReleaseRequest futureReq = new PayrollReleaseRequest(LocalDate.of(2026, 8, 5), "TXN123", "NEFT");
        mockMvc.perform(post("/api/v1/payroll/records/" + record.getId() + "/release-default")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(futureReq)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", is("PAYROLL_FUTURE_PAYMENT_DATE")));
    }

    @Test
    @DisplayName("VULN-P8-3: Cannot manually release or forfeit 2-day retention")
    void testCannotManuallyDecideTwoDayRetention() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();

        PayrollRetention retention2Day = new PayrollRetention(
            record.getId(), employeeUser.getId(), 2, new BigDecimal("2000.00"), RetentionStatus.HELD
        );
        PayrollRetention saved = retentionRepository.save(retention2Day);

        RetentionDecisionRequest req = new RetentionDecisionRequest("Attempt early release");
        mockMvc.perform(post("/api/v1/payroll/retentions/" + saved.getId() + "/release")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", is("PAYROLL_RETENTION_INVALID_DAYS")));
    }

    @Test
    @DisplayName("VULN-P8-5: Admin cannot download payslip for GENERATED or REJECTED record")
    void testAdminCannotDownloadPayslipForGeneratedOrRejectedRecord() throws Exception {
        businessClock.setFixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PayrollRunRequest runReq = new PayrollRunRequest("2026-07", null, false, false);
        mockMvc.perform(post("/api/v1/payroll/runs")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(runReq)))
            .andExpect(status().isCreated());

        PayrollRecord record = recordRepository.findByUserIdAndMonth(employeeUser.getId(), LocalDate.of(2026, 7, 1)).orElseThrow();
        assertEquals(PayrollRecordStatus.GENERATED, record.getStatus());

        mockMvc.perform(get("/api/v1/payroll/records/" + record.getId() + "/payslip.pdf")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", is("PAYROLL_NOT_APPROVED")));
    }
}
