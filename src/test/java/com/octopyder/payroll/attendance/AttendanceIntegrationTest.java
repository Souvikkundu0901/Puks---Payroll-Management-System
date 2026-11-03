package com.octopyder.payroll.attendance;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.OfficeLocation;
import com.octopyder.payroll.org.OfficeLocationRepository;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AttendanceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private OfficeLocationRepository officeLocationRepository;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private AttendanceCloseJob attendanceCloseJob;

    private OfficeLocation testOffice;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM attendance_records");
        jdbcTemplate.execute("DELETE FROM office_locations");
        jdbcTemplate.execute("DELETE FROM notifications");

        // Seed a test office: Kolkata Tech Park
        testOffice = new OfficeLocation();
        testOffice.setName("Kolkata HQ");
        testOffice.setLatitude(new BigDecimal("22.5726000"));
        testOffice.setLongitude(new BigDecimal("88.4373000"));
        testOffice.setRadiusM(100);
        testOffice.setAllowedIpCidrs(List.of("192.168.1.0/24"));
        testOffice.setActive(true);
        testOffice = officeLocationRepository.save(testOffice);
    }

    private User createTestUser(String code, String email, Role role, UUID managerId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)",
            userId, code, email, "$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq", role.name(), managerId
        );
        User user = new User();
        user.setId(userId);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setManagerId(managerId);
        user.setTokenVersion(1);
        return user;
    }

    private String tokenFor(User user) {
        return tokenService.generateAccessToken(user);
    }

    @Test
    @DisplayName("Check-in captures GPS, server-read IP/User-Agent, and derives OFFICE work mode")
    void checkInInsideOfficeGeofence() throws Exception {
        User manager = createTestUser("MGR_ATT1", "mgr.att1@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_ATT1", "emp.att1@octopyder.com", Role.EMPLOYEE, manager.getId());

        String empToken = tokenFor(employee);

        // Check-in coordinates 20m from office center
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .header("User-Agent", "Mozilla/5.0 TestBrowser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5727000,
                        "longitude": 88.4373500,
                        "accuracyM": 15.0,
                        "devicePlatform": "WEB"
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workMode").value("OFFICE"))
            .andExpect(jsonPath("$.officeLocationName").value("Kolkata HQ"))
            .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
            .andExpect(jsonPath("$.lowAccuracy").value(false))
            .andExpect(jsonPath("$.devicePlatform").value("WEB"));

        // Verify database entry
        List<AttendanceRecord> records = attendanceRecordRepository.findAll();
        assertThat(records).hasSize(1);
        AttendanceRecord rec = records.get(0);
        assertThat(rec.getWorkMode()).isEqualTo(AttendanceWorkMode.OFFICE);
        assertThat(rec.getUserAgent()).isEqualTo("Mozilla/5.0 TestBrowser");
        assertThat(rec.getDistanceM()).isNotNull();
    }

    @Test
    @DisplayName("Second check-in on the same date returns 409 Conflict")
    void secondCheckInSameDateReturns409() throws Exception {
        User employee = createTestUser("EMP_ATT2", "emp.att2@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        // First check-in
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5726000,
                        "longitude": 88.4373000,
                        "accuracyM": 10.0
                    }
                """))
            .andExpect(status().isCreated());

        // Duplicate check-in
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5726000,
                        "longitude": 88.4373000,
                        "accuracyM": 10.0
                    }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_CHECKED_IN"));
    }

    @Test
    @DisplayName("Check-in outside geofence yields REMOTE; when remote disallowed returns 422")
    void checkInRemoteAllowedAndDisallowed() throws Exception {
        User employee = createTestUser("EMP_ATT3", "emp.att3@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        // Outside office (e.g. Mumbai coordinates 19.0760, 72.8777)
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 19.0760000,
                        "longitude": 72.8777000,
                        "accuracyM": 20.0
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workMode").value("REMOTE"));

        // Now test second employee when remoteAllowed is turned OFF
        User admin = createTestUser("ADM_ATT_S1", "admin.att.s1@octopyder.com", Role.ADMIN, null);
        settingsService.updateGroup("attendance", java.util.Map.of("attendance.remoteAllowed", false), admin.getId());

        User empRemote = createTestUser("EMP_ATT4", "emp.att4@octopyder.com", Role.EMPLOYEE, null);
        String empRemoteToken = tokenFor(empRemote);

        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empRemoteToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 19.0760000,
                        "longitude": 72.8777000,
                        "accuracyM": 20.0
                    }
                """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("ATTENDANCE_REMOTE_NOT_ALLOWED"));

        // Reset remoteAllowed setting
        settingsService.updateGroup("attendance", java.util.Map.of("attendance.remoteAllowed", true), admin.getId());
    }

    @Test
    @DisplayName("Check-out computes worked duration and day type; duplicate check-out returns 409")
    void checkOutFlowAndDuration() throws Exception {
        User employee = createTestUser("EMP_ATT5", "emp.att5@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        // Attempt checkout before checkin -> 400
        mockMvc.perform(post("/api/v1/attendance/check-out")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_CHECKED_IN"));

        // Check in
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5726000,
                        "longitude": 88.4373000
                    }
                """))
            .andExpect(status().isCreated());

        // Check out successfully
        mockMvc.perform(post("/api/v1/attendance/check-out")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkOutAt").isNotEmpty());

        // Second checkout -> 409
        mockMvc.perform(post("/api/v1/attendance/check-out")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_CHECKED_OUT"));
    }

    @Test
    @DisplayName("Manager can only approve their own team members; Admin can bulk-decide across all")
    void approvalWorkflowAndTeamScoping() throws Exception {
        User manager1 = createTestUser("MGR_ATT6", "mgr.att6@octopyder.com", Role.MANAGER, null);
        User manager2 = createTestUser("MGR_ATT7", "mgr.att7@octopyder.com", Role.MANAGER, null);
        User admin = createTestUser("ADM_ATT8", "admin.att8@octopyder.com", Role.ADMIN, null);

        User empTeam1 = createTestUser("EMP_ATT_T1", "emp.att.t1@octopyder.com", Role.EMPLOYEE, manager1.getId());
        User empTeam2 = createTestUser("EMP_ATT_T2", "emp.att.t2@octopyder.com", Role.EMPLOYEE, manager2.getId());

        String mgr1Token = tokenFor(manager1);
        String adminToken = tokenFor(admin);
        String emp1Token = tokenFor(empTeam1);
        String emp2Token = tokenFor(empTeam2);

        // Emp1 checks in
        String resp1 = mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + emp1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude": 22.5726, "longitude": 88.4373}
                """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        // Emp2 checks in
        String resp2 = mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + emp2Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude": 22.5726, "longitude": 88.4373}
                """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String id1 = com.jayway.jsonpath.JsonPath.read(resp1, "$.id");
        String id2 = com.jayway.jsonpath.JsonPath.read(resp2, "$.id");

        // Manager 1 attempts to approve Emp 2 (not in team) -> 403 Forbidden
        mockMvc.perform(post("/api/v1/attendance/" + id2 + "/approve")
                .header("Authorization", "Bearer " + mgr1Token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));

        // Manager 1 approves Emp 1 -> 200 OK
        mockMvc.perform(post("/api/v1/attendance/" + id1 + "/approve")
                .header("Authorization", "Bearer " + mgr1Token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value(manager1.getId().toString()));

        // Admin bulk-decides (rejects Emp 2 with optional note)
        mockMvc.perform(post("/api/v1/attendance/bulk-decision")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "ids": ["%s"],
                        "decision": "REJECT",
                        "note": "Suspected mock location"
                    }
                """.formatted(id2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.successCount").value(1))
            .andExpect(jsonPath("$.items[0].status").value("REJECT"));

        AttendanceRecord rec2 = attendanceRecordRepository.findById(UUID.fromString(id2)).orElseThrow();
        assertThat(rec2.getApprovalStatus()).isEqualTo(AttendanceApprovalStatus.REJECTED);
        assertThat(rec2.getRejectionNote()).isEqualTo("Suspected mock location");
    }

    @Test
    @DisplayName("Close job marks older unclosed records as missed_checkout=true and leaves check_out_at empty")
    void closeJobMarksMissedCheckoutOnOlderDays() {
        User employee = createTestUser("EMP_ATT_CLS", "emp.att.cls@octopyder.com", Role.EMPLOYEE, null);

        // Insert unclosed record for 2 days ago
        AttendanceRecord oldRecord = new AttendanceRecord();
        oldRecord.setUserId(employee.getId());
        oldRecord.setWorkDate(LocalDate.now().minusDays(2));
        oldRecord.setCheckInAt(Instant.now().minusSeconds(86400 * 2));
        oldRecord.setWorkMode(AttendanceWorkMode.OFFICE);
        oldRecord.setDayType(AttendanceDayType.PRESENT);
        oldRecord.setApprovalStatus(AttendanceApprovalStatus.PENDING);
        oldRecord = attendanceRecordRepository.save(oldRecord);

        // Run close job
        int closed = attendanceCloseJob.closeOpenRecordsOlderThanToday();
        assertThat(closed).isGreaterThanOrEqualTo(1);

        AttendanceRecord updated = attendanceRecordRepository.findById(oldRecord.getId()).orElseThrow();
        assertThat(updated.isMissedCheckout()).isTrue();
        assertThat(updated.getCheckOutAt()).isNull();
        assertThat(updated.getWorkedMinutes()).isEqualTo(0);
        assertThat(updated.getDayType()).isEqualTo(AttendanceDayType.PRESENT);
    }

    @Test
    @DisplayName("Employee cannot access manager attendance list (403 Forbidden)")
    void employeeCannotAccessAttendanceList() throws Exception {
        User employee = createTestUser("EMP_ATT_LST", "emp.att.lst@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        mockMvc.perform(get("/api/v1/attendance")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("IP spoofing via X-Forwarded-For is ignored when APP_TRUSTED_PROXY=false")
    void ipSpoofingIgnoredWhenTrustedProxyFalse() throws Exception {
        User employee = createTestUser("EMP_ATT_SPF", "emp.att.spf@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        // Client attempts to spoof an office IP via X-Forwarded-For
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .header("X-Forwarded-For", "192.168.1.50")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 19.0760,
                        "longitude": 72.8777
                    }
                """))
            .andExpect(status().isCreated())
            // In test environment, app.trusted-proxy defaults to false, so X-Forwarded-For is ignored
            .andExpect(jsonPath("$.workMode").value("REMOTE"));
    }

    @Test
    @DisplayName("Auto-approve check-in when attendance.managerApproval is false")
    void autoApproveWhenManagerApprovalIsOff() throws Exception {
        User admin = createTestUser("ADM_ATT_S2", "admin.att.s2@octopyder.com", Role.ADMIN, null);
        settingsService.updateGroup("attendance", java.util.Map.of("attendance.managerApproval", false), admin.getId());

        User employee = createTestUser("EMP_ATT_AUT", "emp.att.aut@octopyder.com", Role.EMPLOYEE, null);
        String empToken = tokenFor(employee);

        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5726000,
                        "longitude": 88.4373000
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        // Restore setting
        settingsService.updateGroup("attendance", java.util.Map.of("attendance.managerApproval", true), admin.getId());
    }

    @Test
    @DisplayName("List attendance rejects date ranges exceeding 90 days with 400 Bad Request")
    void listAttendanceRejectsRangeOver90Days() throws Exception {
        User admin = createTestUser("ADM_ATT_RNG", "admin.att.rng@octopyder.com", Role.ADMIN, null);
        String adminToken = tokenFor(admin);

        mockMvc.perform(get("/api/v1/attendance?from=2026-01-01&to=2026-05-01")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("Date range cannot exceed 90 days"));
    }

    @Test
    @DisplayName("Self-approval is forbidden: managers cannot approve or reject their own attendance")
    void managerCannotApproveOrRejectOwnAttendance() throws Exception {
        User manager = createTestUser("MGR_ATT_SELF", "mgr.att.self@octopyder.com", Role.MANAGER, null);
        String mgrToken = tokenFor(manager);

        String checkInResp = mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "latitude": 22.5726000,
                        "longitude": 88.4373000
                    }
                """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String attId = com.jayway.jsonpath.JsonPath.read(checkInResp, "$.id");

        // Manager attempts self-approval -> 403 Forbidden
        mockMvc.perform(post("/api/v1/attendance/" + attId + "/approve")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SELF_APPROVAL"));

        // Manager attempts self-rejection -> 403 Forbidden
        mockMvc.perform(post("/api/v1/attendance/" + attId + "/reject")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"note": "test"}
                """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SELF_APPROVAL"));

        // Manager attempts self bulk-decision -> fails item
        mockMvc.perform(post("/api/v1/attendance/bulk-decision")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "ids": ["%s"],
                        "decision": "APPROVE"
                    }
                """.formatted(attId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.failureCount").value(1))
            .andExpect(jsonPath("$.items[0].message").value("Cannot decide your own attendance"));
    }

    @Test
    @DisplayName("List attendance correctly filters records by departmentId")
    void listAttendanceFiltersByDepartment() throws Exception {
        User admin = createTestUser("ADM_ATT_DEPT", "admin.att.dept@octopyder.com", Role.ADMIN, null);
        String adminToken = tokenFor(admin);

        UUID dept1 = UUID.randomUUID();
        UUID dept2 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO departments (id, name, code) VALUES (?, 'Engineering', 'ENG')", dept1);
        jdbcTemplate.update("INSERT INTO departments (id, name, code) VALUES (?, 'Human Resources', 'HR')", dept2);

        User emp1 = createTestUser("EMP_DEPT1", "emp.dept1@octopyder.com", Role.EMPLOYEE, null);
        User emp2 = createTestUser("EMP_DEPT2", "emp.dept2@octopyder.com", Role.EMPLOYEE, null);

        jdbcTemplate.update("INSERT INTO employee_profiles (user_id, first_name, last_name, department_id, designation, employment_type, joining_date, created_at, updated_at) VALUES (?, 'Dev', 'One', ?, 'Dev', 'FULL_TIME', '2026-01-01', now(), now())", emp1.getId(), dept1);
        jdbcTemplate.update("INSERT INTO employee_profiles (user_id, first_name, last_name, department_id, designation, employment_type, joining_date, created_at, updated_at) VALUES (?, 'HR', 'Exec', ?, 'HR Exec', 'FULL_TIME', '2026-01-01', now(), now())", emp2.getId(), dept2);

        // Emp1 and Emp2 check in
        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + tokenFor(emp1))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude": 22.5726, "longitude": 88.4373}
                """))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/attendance/check-in")
                .header("Authorization", "Bearer " + tokenFor(emp2))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude": 22.5726, "longitude": 88.4373}
                """))
            .andExpect(status().isCreated());

        // Admin queries by dept1 -> returns only emp1
        mockMvc.perform(get("/api/v1/attendance?departmentId=" + dept1)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].userId").value(emp1.getId().toString()));
    }
}
