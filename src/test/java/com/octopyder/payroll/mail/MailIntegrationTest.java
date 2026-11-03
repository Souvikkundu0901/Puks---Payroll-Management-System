package com.octopyder.payroll.mail;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MailIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @MockitoBean
    private MailService mailService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(mailService);

        UUID adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' LIMIT 1",
                UUID.class
        );
        Integer tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM users WHERE id = ?",
                Integer.class,
                adminId
        );
        User admin = new User();
        admin.setId(adminId);
        admin.setRole(Role.ADMIN);
        admin.setTokenVersion(tokenVersion != null ? tokenVersion : 1);
        adminToken = tokenService.generateAccessToken(admin);
    }

    @Test
    void testCreateEmployeeSuccessfulEmailDelivery() throws Exception {
        when(mailService.sendLoginCredentials(anyString(), anyString())).thenReturn(true);

        String uniqueEmail = "mail_emp_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String payload = """
                {
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "%s",
                    "phone": "9876543210",
                    "designation": "Software Engineer",
                    "baseSalary": 60000,
                    "role": "EMPLOYEE"
                }
                """.formatted(uniqueEmail);

        MvcResult result = mockMvc.perform(post("/api/v1/employees")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.email").value(uniqueEmail))
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn();

        // Verify mail service was called
        verify(mailService, times(1)).sendLoginCredentials(org.mockito.ArgumentMatchers.eq(uniqueEmail), anyString());

        // Verify audit log has emailSent and NOT the temporary password
        String tempPass = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.temporaryPassword");
        List<Map<String, Object>> auditLogs = jdbcTemplate.queryForList(
                "SELECT action, after::text as details FROM audit_logs WHERE after::text LIKE ? ORDER BY at DESC",
                "%" + uniqueEmail + "%"
        );
        assertThat(auditLogs).isNotEmpty();
        for (Map<String, Object> log : auditLogs) {
            String details = (String) log.get("details");
            if (details != null) {
                assertThat(details).doesNotContain(tempPass);
            }
        }

        // Verify USER_EMAIL_NOTIFICATION audit entry
        List<Map<String, Object>> emailAudits = jdbcTemplate.queryForList(
                "SELECT after::text as details FROM audit_logs WHERE action = 'USER_EMAIL_NOTIFICATION' ORDER BY at DESC LIMIT 1"
        );
        assertThat(emailAudits).isNotEmpty();
        String auditDetails = (String) emailAudits.get(0).get("details");
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(auditDetails, "$.emailSent")).isTrue();
        assertThat(auditDetails).doesNotContain(tempPass);
    }

    @Test
    void testCreateEmployeeFailingEmailLeavesUserCreated() throws Exception {
        // Mail service returns false (e.g. SMTP unavailable)
        when(mailService.sendLoginCredentials(anyString(), anyString())).thenReturn(false);

        String uniqueEmail = "mail_fail_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String payload = """
                {
                    "firstName": "Jane",
                    "lastName": "Smith",
                    "email": "%s",
                    "phone": "9876543211",
                    "designation": "Product Manager",
                    "baseSalary": 75000,
                    "role": "EMPLOYEE"
                }
                """.formatted(uniqueEmail);

        mockMvc.perform(post("/api/v1/employees")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.email").value(uniqueEmail))
                .andExpect(jsonPath("$.emailSent").value(false))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());

        // Crucial invariant: Database was NOT rolled back! User exists and is ACTIVE.
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ? AND status = 'ACTIVE'",
                Integer.class,
                uniqueEmail
        );
        assertThat(userCount).isEqualTo(1);

        // USER_EMAIL_NOTIFICATION audit entry logged with emailSent=false
        List<Map<String, Object>> emailAudits = jdbcTemplate.queryForList(
                "SELECT after::text as details FROM audit_logs WHERE action = 'USER_EMAIL_NOTIFICATION' ORDER BY at DESC LIMIT 1"
        );
        assertThat(emailAudits).isNotEmpty();
        String failAuditDetails = (String) emailAudits.get(0).get("details");
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(failAuditDetails, "$.emailSent")).isFalse();
    }

    @Test
    void testCreateManagerSendsEmailExactlyOnce() throws Exception {
        when(mailService.sendLoginCredentials(anyString(), anyString())).thenReturn(true);

        String uniqueEmail = "mail_mgr_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String payload = """
                {
                    "firstName": "Sarah",
                    "lastName": "Connor",
                    "email": "%s",
                    "phone": "9876543212",
                    "designation": "Engineering Manager"
                }
                """.formatted(uniqueEmail);

        mockMvc.perform(post("/api/v1/managers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.email").value(uniqueEmail))
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());

        // Verify sent EXACTLY once
        verify(mailService, times(1)).sendLoginCredentials(org.mockito.ArgumentMatchers.eq(uniqueEmail), anyString());
    }

    @Test
    void testResetPasswordDispatchesEmail() throws Exception {
        when(mailService.sendLoginCredentials(anyString(), anyString())).thenReturn(true);

        // 1. Create employee
        String uniqueEmail = "mail_reset_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String createPayload = """
                {
                    "firstName": "Reset",
                    "lastName": "User",
                    "email": "%s",
                    "phone": "9876543213",
                    "designation": "QA Analyst",
                    "baseSalary": 50000,
                    "role": "EMPLOYEE"
                }
                """.formatted(uniqueEmail);

        MvcResult createResult = mockMvc.perform(post("/api/v1/employees")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String empId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        reset(mailService);
        when(mailService.sendLoginCredentials(anyString(), anyString())).thenReturn(true);

        // 2. Reset password
        mockMvc.perform(post("/api/v1/employees/" + empId + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());

        // Verify mail service was called with reset email
        verify(mailService, times(1)).sendLoginCredentials(org.mockito.ArgumentMatchers.eq(uniqueEmail), anyString());

        // Verify USER_EMAIL_NOTIFICATION audit entry
        List<Map<String, Object>> emailAudits = jdbcTemplate.queryForList(
                "SELECT after::text as details FROM audit_logs WHERE action = 'USER_EMAIL_NOTIFICATION' AND entity_id = ? ORDER BY at DESC LIMIT 1",
                empId
        );
        assertThat(emailAudits).isNotEmpty();
        String resetAuditDetails = (String) emailAudits.get(0).get("details");
        assertThat((Boolean) com.jayway.jsonpath.JsonPath.read(resetAuditDetails, "$.emailSent")).isTrue();
    }

    @Test
    void testImportCsvSendsNoEmails() throws Exception {
        String email1 = "csv1_" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String email2 = "csv2_" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String csvContent = "Name,Email,Designation\nAlice In," + email1 + ",Engineer\nBob In," + email2 + ",Designer\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "employees.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/employees/import")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(2));

        // Mail service must NOT be called for CSV imports
        verify(mailService, times(0)).sendLoginCredentials(anyString(), anyString());
    }
}
