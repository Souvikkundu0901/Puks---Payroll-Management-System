package com.octopyder.payroll.leave;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceRecord;
import com.octopyder.payroll.attendance.AttendanceRecordRepository;
import com.octopyder.payroll.attendance.AttendanceWorkMode;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.Holiday;
import com.octopyder.payroll.org.HolidayRepository;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LeaveIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private LeaveLedgerEntryRepository ledgerRepository;

    @Autowired
    private LeaveLedgerService leaveLedgerService;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private BusinessClock businessClock;

    @BeforeEach
    void setUp() {
        businessClock.setFixed(LocalDate.of(2026, 10, 31));
        jdbcTemplate.execute("TRUNCATE TABLE leave_requests, leave_ledger_entries CASCADE");
        jdbcTemplate.execute("DELETE FROM attendance_records");
        jdbcTemplate.execute("DELETE FROM holidays");
        jdbcTemplate.execute("DELETE FROM notifications");
    }

    @AfterEach
    void tearDown() {
        businessClock.reset();
    }

    private User createTestUser(String code, String email, Role role, UUID managerId, LocalDate joiningDate, LocalDate exitDate) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)",
            userId, code, email, "$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq", role.name(), managerId
        );
        jdbcTemplate.update(
            "INSERT INTO employee_profiles (user_id, first_name, last_name, designation, employment_type, joining_date, exit_date, created_at, updated_at) " +
            "VALUES (?, 'Test', 'User', 'Engineer', 'FULL_TIME', ?, ?, now(), now())",
            userId, joiningDate, exitDate
        );
        User user = new User();
        user.setId(userId);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setManagerId(managerId);
        return user;
    }

    private String tokenFor(User user) {
        return tokenService.generateAccessToken(user);
    }

    @Test
    @DisplayName("Idempotent monthly credits and back-fill from joining month")
    void idempotentMonthlyCreditsAndBackfill() throws Exception {
        User emp = createTestUser("EMP_LV_01", "emp.lv.01@octopyder.com", Role.EMPLOYEE, null, LocalDate.of(2026, 8, 1), null);

        LocalDate today = businessClock.instant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), today);
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), today);

        // August, September, October = 3 credits
        var entries = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(emp.getId());
        assertThat(entries).hasSize(3);

        BigDecimal balance = ledgerRepository.getBalanceAsOf(emp.getId(), today);
        assertThat(balance).isEqualByComparingTo("3.0");

        // Verify via API GET /api/v1/leave/me/balance
        mockMvc.perform(get("/api/v1/leave/me/balance")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(3.0))
            .andExpect(jsonPath("$.earned").value(3.0));
    }

    @Test
    @DisplayName("No credits issued after employee exit date")
    void noCreditsAfterExitDate() {
        User emp = createTestUser("EMP_LV_02", "emp.lv.02@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 25));

        leaveLedgerService.ensureCreditsUpTo(emp.getId(), LocalDate.of(2026, 8, 15));

        // Should only have credits for May and June (exit date was June 25)
        var entries = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(emp.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(e -> e.getPeriodMonth().toString()).toList())
            .containsExactlyInAnyOrder("2026-05-01", "2026-06-01");
    }

    @Test
    @DisplayName("Annual reset on 1 April zeroes balance and carries forward up to limit")
    void annualResetOnFirstAprilWithCarryForward() {
        // Employee joined in 2025-05-01
        User emp = createTestUser("EMP_LV_03", "emp.lv.03@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2025, 5, 1), null);

        // Set carry forward limit = 2
        settingsService.updateGroup("leave", java.util.Map.of("leave.carryForwardOnReset", 2, "leave.resetDate", "04-01"), emp.getId());

        // Fast-forward to 2026-04-05 (new leave year 2026)
        LocalDate newYearToday = LocalDate.of(2026, 4, 5);
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), newYearToday);

        // Verification:
        // FY 2025 (May 2025 to Mar 2026 = 11 months): 11 credits
        // On 1 April 2026:
        // RESET entry = -11.0
        // CARRY_FORWARD entry = 2.0 (min(11, 2))
        // April 2026 credit = 1.0
        // Total balance = 2 + 1 = 3.0
        BigDecimal balance = ledgerRepository.getBalanceAsOf(emp.getId(), newYearToday);
        assertThat(balance).isEqualByComparingTo("3.0");

        var resetOpt = ledgerRepository.findByUserIdAndEntryTypeAndLeaveYear(emp.getId(), LeaveLedgerEntryType.RESET, 2025);
        assertThat(resetOpt).isPresent();
        assertThat(resetOpt.get().getDays()).isEqualByComparingTo("-11.0");

        var cfOpt = ledgerRepository.findByUserIdAndEntryTypeAndLeaveYear(emp.getId(), LeaveLedgerEntryType.CARRY_FORWARD, 2026);
        assertThat(cfOpt).isPresent();
        assertThat(cfOpt.get().getDays()).isEqualByComparingTo("2.0");
    }

    @Test
    @DisplayName("Apply leave excludes weekends and holidays from days count")
    void applyLeaveExcludesWeekendsAndHolidays() throws Exception {
        User emp = createTestUser("EMP_LV_04", "emp.lv.04@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2026, 1, 1), null);

        // Add a holiday on 2026-07-08 (Wednesday)
        Holiday holiday = new Holiday(LocalDate.of(2026, 7, 8), "Special Holiday");
        holidayRepository.save(holiday);

        // Employee applies for 2026-07-06 (Mon) to 2026-07-12 (Sun) -> 5 weekdays - 1 holiday = 4 working days
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-06")
                .param("endDate", "2026-07-12")
                .param("reason", "Family vacation")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.days").value(4.0))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Leave request crossing annual reset date is rejected")
    void leaveCrossingResetDateRejected() throws Exception {
        User emp = createTestUser("EMP_LV_05", "emp.lv.05@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2025, 1, 1), null);

        // Spanning 2026-03-30 to 2026-04-02 crosses 1 April
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "PAID")
                .param("startDate", "2026-03-30")
                .param("endDate", "2026-04-02")
                .param("reason", "Cross year break")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LEAVE_CROSSES_YEAR_BOUNDARY"));
    }

    @Test
    @DisplayName("Overlapping leave request returns 409 LEAVE_OVERLAP")
    void overlappingLeaveRequestReturns409() throws Exception {
        User emp = createTestUser("EMP_LV_06", "emp.lv.06@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2026, 1, 1), null);

        // 1st request: 2026-07-13 to 2026-07-15
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-13")
                .param("endDate", "2026-07-15")
                .param("reason", "First request")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated());

        // 2nd request overlapping: 2026-07-14 to 2026-07-17
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "SICK")
                .param("startDate", "2026-07-14")
                .param("endDate", "2026-07-17")
                .param("reason", "Overlapping request")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEAVE_OVERLAP"));
    }

    @Test
    @DisplayName("Leave dates conflicting with existing attendance return 409 LEAVE_ATTENDANCE_CONFLICT")
    void leaveDatesConflictingWithAttendanceReturn409() throws Exception {
        User emp = createTestUser("EMP_LV_07", "emp.lv.07@octopyder.com", Role.EMPLOYEE, null,
            LocalDate.of(2026, 1, 1), null);

        // Create an attendance record for 2026-07-21
        AttendanceRecord att = new AttendanceRecord();
        att.setUserId(emp.getId());
        att.setWorkDate(LocalDate.of(2026, 7, 21));
        att.setCheckInAt(Instant.now());
        att.setWorkMode(AttendanceWorkMode.OFFICE);
        att.setApprovalStatus(AttendanceApprovalStatus.APPROVED);
        attendanceRecordRepository.save(att);

        // Apply leave including 2026-07-21
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-20")
                .param("endDate", "2026-07-22")
                .param("reason", "Conflict test")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEAVE_ATTENDANCE_CONFLICT"));
    }

    @Test
    @DisplayName("Insufficient balance: normal approval returns 422; asUnpaid succeeds with 0 debit")
    void insufficientBalanceFlow() throws Exception {
        User mgr = createTestUser("MGR_LV_08", "mgr.lv.08@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        User emp = createTestUser("EMP_LV_08", "emp.lv.08@octopyder.com", Role.EMPLOYEE, mgr.getId(), LocalDate.of(2026, 7, 1), null);

        // Employee joined in July 2026 -> has 1 credit only
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), LocalDate.of(2026, 7, 15));

        // Employee applies for 3 days (Mon 2026-07-20 to Wed 2026-07-22)
        String resp = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "PAID")
                .param("startDate", "2026-07-20")
                .param("endDate", "2026-07-22")
                .param("reason", "Needs 3 days")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.insufficientBalance").value(true))
            .andReturn().getResponse().getContentAsString();

        String reqId = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        // Manager attempts normal approval -> 422 LEAVE_INSUFFICIENT_BALANCE
        mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/approve")
                .header("Authorization", "Bearer " + tokenFor(mgr))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("LEAVE_INSUFFICIENT_BALANCE"));

        // Manager approves as UNPAID -> 200 OK, debitedDays = 0, type = UNPAID
        mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/approve")
                .header("Authorization", "Bearer " + tokenFor(mgr))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"asUnpaid": true, "note": "Approved as loss of pay"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.leaveType").value("UNPAID"))
            .andExpect(jsonPath("$.debitedDays").value(0.0));

        // Verify balance was not debited
        BigDecimal balance = ledgerRepository.getBalanceAsOf(emp.getId(), LocalDate.of(2026, 7, 20));
        assertThat(balance).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("Cancel approved future leave writes REVERSAL and restores balance")
    void cancelApprovedFutureLeaveWritesReversal() throws Exception {
        User mgr = createTestUser("MGR_LV_09", "mgr.lv.09@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        User emp = createTestUser("EMP_LV_09", "emp.lv.09@octopyder.com", Role.EMPLOYEE, mgr.getId(), LocalDate.of(2026, 1, 1), null);

        // Employee has ample balance
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), LocalDate.of(2026, 7, 1));

        // Employee applies for future leave (2026-11-09 to 2026-11-10 = 2 days)
        String resp = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-11-09")
                .param("endDate", "2026-11-10")
                .param("reason", "Future conference")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String reqId = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        // Manager approves
        mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/approve")
                .header("Authorization", "Bearer " + tokenFor(mgr))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"note": "Approved"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.debitedDays").value(2.0));

        // Employee cancels approved future leave
        mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/cancel")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify REVERSAL entry exists
        var reversals = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(emp.getId()).stream()
            .filter(e -> e.getEntryType() == LeaveLedgerEntryType.REVERSAL)
            .toList();
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getDays()).isEqualByComparingTo("2.0");
    }

    @Test
    @DisplayName("Self approval and cross team approval are strictly forbidden")
    void selfApprovalAndCrossTeamForbidden() throws Exception {
        User mgrA = createTestUser("MGR_LV_10A", "mgr.lv.10a@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        User mgrB = createTestUser("MGR_LV_10B", "mgr.lv.10b@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        User empA = createTestUser("EMP_LV_10A", "emp.lv.10a@octopyder.com", Role.EMPLOYEE, mgrA.getId(), LocalDate.of(2026, 1, 1), null);

        leaveLedgerService.ensureCreditsUpTo(empA.getId(), LocalDate.of(2026, 7, 1));
        leaveLedgerService.ensureCreditsUpTo(mgrA.getId(), LocalDate.of(2026, 7, 1));

        // EmpA applies
        String resp = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-20")
                .param("endDate", "2026-07-21")
                .param("reason", "Test")
                .header("Authorization", "Bearer " + tokenFor(empA)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String reqId = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        // Manager B attempts approval of Manager A's employee -> 403 FORBIDDEN_SCOPE
        mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/approve")
                .header("Authorization", "Bearer " + tokenFor(mgrB))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));

        // Manager A applies for own leave
        String mgrResp = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-22")
                .param("endDate", "2026-07-23")
                .param("reason", "Mgr leave")
                .header("Authorization", "Bearer " + tokenFor(mgrA)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String mgrReqId = com.jayway.jsonpath.JsonPath.read(mgrResp, "$.id");

        // Manager A attempts self-approval -> 403 FORBIDDEN_SELF_APPROVAL
        mockMvc.perform(post("/api/v1/leave/requests/" + mgrReqId + "/approve")
                .header("Authorization", "Bearer " + tokenFor(mgrA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SELF_APPROVAL"));
    }

    @Test
    @DisplayName("Admin on-behalf apply succeeds")
    void adminOnBehalfApplySucceeds() throws Exception {
        User admin = createTestUser("ADM_LV_11", "adm.lv.11@octopyder.com", Role.ADMIN, null, LocalDate.of(2026, 1, 1), null);
        User emp = createTestUser("EMP_LV_11", "emp.lv.11@octopyder.com", Role.EMPLOYEE, null, LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(post("/api/v1/leave/admin-apply")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "userId": "%s",
                        "type": "CASUAL",
                        "startDate": "2026-07-27",
                        "endDate": "2026-07-28",
                        "reason": "Applied by HR Admin"
                    }
                """.formatted(emp.getId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value(emp.getId().toString()))
            .andExpect(jsonPath("$.days").value(2.0));
    }

    @Test
    @DisplayName("Auto-approve debits when balance covers it, otherwise stays PENDING (never silent unpaid)")
    void autoApproveRulesWhenManagerApprovalOff() throws Exception {
        User emp = createTestUser("EMP_LV_12", "emp.lv.12@octopyder.com", Role.EMPLOYEE, null, LocalDate.of(2026, 7, 1), null);

        // Turn off manager approval
        settingsService.updateGroup("leave", java.util.Map.of("leave.managerApproval", false), emp.getId());

        // Employee joined July 2026 -> 1 credit by July 15
        leaveLedgerService.ensureCreditsUpTo(emp.getId(), LocalDate.of(2026, 7, 15));

        // 1. Apply for 1 day -> covered by balance -> auto-approved with 1 debit
        String resp1 = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-20")
                .param("endDate", "2026-07-20")
                .param("reason", "1 day covered")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.debitedDays").value(1.0))
            .andReturn().getResponse().getContentAsString();

        // 2. Apply for another day (balance is now 0) -> not covered -> stays PENDING, NOT silent unpaid
        mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-21")
                .param("endDate", "2026-07-21")
                .param("reason", "Not covered")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.insufficientBalance").value(true));

        // Reset setting
        settingsService.updateGroup("leave", java.util.Map.of("leave.managerApproval", true), emp.getId());
    }

    @Test
    @DisplayName("Concurrent approval creates exactly one debit and no duplicate")
    void concurrentApprovalYieldsExactlyOneDebit() throws Exception {
        User mgr = createTestUser("MGR_LV_13", "mgr.lv.13@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        User emp = createTestUser("EMP_LV_13", "emp.lv.13@octopyder.com", Role.EMPLOYEE, mgr.getId(), LocalDate.of(2026, 1, 1), null);

        leaveLedgerService.ensureCreditsUpTo(emp.getId(), LocalDate.of(2026, 7, 1));

        String resp = mockMvc.perform(multipart("/api/v1/leave/requests")
                .param("type", "CASUAL")
                .param("startDate", "2026-07-20")
                .param("endDate", "2026-07-21")
                .param("reason", "Concurrent test")
                .header("Authorization", "Bearer " + tokenFor(emp)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String reqId = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflictCount = new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.Callable<Void> task = () -> {
            try {
                var result = mockMvc.perform(post("/api/v1/leave/requests/" + reqId + "/approve")
                        .header("Authorization", "Bearer " + tokenFor(mgr))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                    .andReturn().getResponse();

                if (result.getStatus() == 200) {
                    successCount.incrementAndGet();
                } else if (result.getStatus() == 409) {
                    conflictCount.incrementAndGet();
                }
            } catch (Exception ignored) {}
            return null;
        };

        executor.invokeAll(java.util.List.of(task, task));
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify exactly one debit row was created
        var debits = ledgerRepository.findByUserIdOrderByEffectiveOnDescCreatedAtDesc(emp.getId()).stream()
            .filter(e -> e.getEntryType() == LeaveLedgerEntryType.DEBIT)
            .toList();
        assertThat(debits).hasSize(1);
    }

    @Test
    @DisplayName("Security: Manager cannot access unprofiled or off-team user ledger")
    void managerCannotAccessUnprofiledOrOffTeamUserLedger() throws Exception {
        User mgr = createTestUser("MGR_SEC_1", "mgr.sec.1@octopyder.com", Role.MANAGER, null, LocalDate.of(2026, 1, 1), null);
        // Create an unprofiled admin user without employee_profiles entry
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, token_version) VALUES (?, 'ADM_SEC_1', 'adm.sec.1@octopyder.com', '$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq', 'ADMIN', 'ACTIVE', 1)",
            adminId
        );

        mockMvc.perform(get("/api/v1/leave/ledger/" + adminId)
                .header("Authorization", "Bearer " + tokenFor(mgr)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Admin cannot apply leave on behalf of soft-deleted user")
    void adminCannotApplyLeaveForSoftDeletedUser() throws Exception {
        User admin = createTestUser("ADM_SEC_2", "adm.sec.2@octopyder.com", Role.ADMIN, null, LocalDate.of(2026, 1, 1), null);
        User emp = createTestUser("EMP_SEC_2", "emp.sec.2@octopyder.com", Role.EMPLOYEE, null, LocalDate.of(2026, 1, 1), null);

        // Soft-delete employee
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", emp.getId());

        String body = """
            {
                "userId": "%s",
                "type": "PAID",
                "startDate": "2026-07-20",
                "endDate": "2026-07-21",
                "reason": "Admin on-behalf for deleted user"
            }
            """.formatted(emp.getId());

        mockMvc.perform(post("/api/v1/leave/admin-apply")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }
}
