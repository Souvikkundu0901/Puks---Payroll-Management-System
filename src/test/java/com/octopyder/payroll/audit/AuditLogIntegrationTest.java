package com.octopyder.payroll.audit;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuditLogIntegrationTest extends BaseIntegrationTest {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BusinessClock businessClock;

    private User admin;
    private User employee;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE users, employee_profiles CASCADE");

        admin = createUser("AUD_ADM", "admin.aud@example.com", Role.ADMIN);
        employee = createUser("AUD_EMP", "emp.aud@example.com", Role.EMPLOYEE);

        createProfile(admin.getId(), "Admin", "Auditor");
        createProfile(employee.getId(), "Normal", "Employee");

        adminToken = tokenService.generateAccessToken(admin);
        employeeToken = tokenService.generateAccessToken(employee);
    }

    private User createUser(String code, String email, Role role) {
        User u = new User();
        u.setCode(code);
        u.setEmail(email);
        u.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
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
        p.setDesignation("Auditor");
        p.setJoiningDate(LocalDate.of(2025, 1, 1));
        profileRepository.save(p);
    }

    @Test
    @DisplayName("Unauthenticated request to audit logs should return 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Non-Admin user requesting audit logs should return 403")
    void nonAdminReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can filter audit logs and see redacted/truncated payloads")
    void adminCanFilterAuditLogs() throws Exception {
        // Create an audit entry with sensitive and long data
        AuditLog log = new AuditLog();
        log.setAt(businessClock.instant());
        log.setActorId(admin.getId());
        log.setActorRole("ADMIN");
        log.setAction("UPDATE_SECURITY");
        log.setEntityType("USER");
        log.setEntityId(employee.getId().toString());
        log.setBefore("{\"password\":\"Secret123!\",\"accountNumber\":\"1234567890\"}");
        log.setAfter("{\"data\":\"" + "X".repeat(2500) + "\"}");
        auditLogRepository.save(log);

        // System-level audit entry (null actor)
        AuditLog sysLog = new AuditLog();
        sysLog.setAt(businessClock.instant().minusSeconds(10));
        sysLog.setActorId(null);
        sysLog.setActorRole(null);
        sysLog.setAction("SYSTEM_BATCH_JOB");
        sysLog.setEntityType("SYSTEM");
        sysLog.setEntityId("N/A");
        sysLog.setBefore(null);
        sysLog.setAfter("{\"status\":\"SUCCESS\"}");
        auditLogRepository.save(sysLog);

        // Filter by action UPDATE_SECURITY
        mockMvc.perform(get("/api/v1/audit-logs")
                .header("Authorization", "Bearer " + adminToken)
                .param("action", "UPDATE_SECURITY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].actorName").value("Admin Auditor"))
            .andExpect(jsonPath("$.items[0].before").value(org.hamcrest.Matchers.containsString("\"password\":\"[REDACTED]\"")))
            .andExpect(jsonPath("$.items[0].before").value(org.hamcrest.Matchers.containsString("\"accountNumber\":\"[REDACTED]\"")))
            .andExpect(jsonPath("$.items[0].after").value(org.hamcrest.Matchers.containsString("... [TRUNCATED]")));

        // Filter by entityType SYSTEM
        mockMvc.perform(get("/api/v1/audit-logs")
                .header("Authorization", "Bearer " + adminToken)
                .param("entityType", "SYSTEM"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].actorName").value("System"))
            .andExpect(jsonPath("$.items[0].actorRole").value("SYSTEM"));
    }
}
