package com.octopyder.payroll.org;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrgIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    private String getAdminToken() {
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
        return tokenService.generateAccessToken(admin);
    }

    private String createTestUserToken(String code, String email, Role role) {
        List<UUID> existingIds = jdbcTemplate.query(
            "SELECT id FROM users WHERE email = ? OR code = ?",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            email, code
        );

        UUID id;
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("TestPassword123!");
        int tokenVersion = 1;
        if (!existingIds.isEmpty()) {
            id = existingIds.getFirst();
            jdbcTemplate.update("""
                UPDATE users SET code = ?, email = ?, password_hash = ?, role = ?, status = 'ACTIVE', deleted_at = NULL
                WHERE id = ?
            """, code, email, hash, role.name(), id);
            tokenVersion = jdbcTemplate.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, id);
        } else {
            id = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO users (id, code, email, password_hash, role, status, token_version)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1)
            """, id, code, email, hash, role.name());
        }

        User user = new User();
        user.setId(id);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setTokenVersion(tokenVersion);
        return tokenService.generateAccessToken(user);
    }

    @Test
    @Order(1)
    void adminCanGetAndPatchSettingsGroup() throws Exception {
        String adminToken = getAdminToken();

        // 1. Get company settings
        mockMvc.perform(get("/api/v1/settings/company")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['company.name']").value("Puk's"));

        // 2. Patch company name
        mockMvc.perform(put("/api/v1/settings/company")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "company.name": "Octopyder Innovations Pvt. Ltd."
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['company.name']").value("Octopyder Innovations Pvt. Ltd."))
            // Unchanged keys must remain intact
            .andExpect(jsonPath("$.['company.email']").value("admin@octopyder.com"));

        // Verify audit log has before and after
        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'UPDATE_SETTINGS' AND entity_id = 'company'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void appConfigAccessibleByEmployeeAndContainsSafeSettings() throws Exception {
        String empToken = createTestUserToken("ORG_EMP1", "org.emp1@octopyder.com", Role.EMPLOYEE);

        mockMvc.perform(get("/api/v1/app-config")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['company.name']").exists())
            .andExpect(jsonPath("$.['attendance.officeStart']").value("09:00"))
            .andExpect(jsonPath("$.['attendance.graceMinutes']").value(15))
            // Security sensitive settings MUST NOT be leaked to employees
            .andExpect(jsonPath("$.['security.maxLoginAttempts']").doesNotExist())
            .andExpect(jsonPath("$.['security.autoLockMinutes']").doesNotExist());
    }

    @Test
    @Order(3)
    void nonAdminGetsForbiddenOnSettingsAndBackups() throws Exception {
        String empToken = createTestUserToken("ORG_EMP2", "org.emp2@octopyder.com", Role.EMPLOYEE);
        String mgrToken = createTestUserToken("ORG_MGR1", "org.mgr1@octopyder.com", Role.MANAGER);

        // Employee forbidden on settings
        mockMvc.perform(get("/api/v1/settings")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/settings/company")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company.name\":\"Hacked\"}"))
            .andExpect(status().isForbidden());

        // Manager forbidden on backups
        mockMvc.perform(get("/api/v1/backups")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isForbidden());

        // Manager forbidden on mutating office locations
        mockMvc.perform(post("/api/v1/settings/office-locations")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Branch Office",
                        "latitude": 22.5726,
                        "longitude": 88.3639,
                        "radiusM": 150
                    }
                """))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void officeLocationCrudAndDeactivation() throws Exception {
        String adminToken = getAdminToken();

        // 1. Create office location
        String resp = mockMvc.perform(post("/api/v1/settings/office-locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Kolkata HQ",
                        "latitude": 22.5726460,
                        "longitude": 88.3638950,
                        "radiusM": 100,
                        "allowedIpCidrs": ["192.168.1.0/24"]
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Kolkata HQ"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn().getResponse().getContentAsString();

        String uuidStr = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        // 2. Reject negative radius
        mockMvc.perform(post("/api/v1/settings/office-locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Bad Radius Office",
                        "latitude": 22.5,
                        "longitude": 88.3,
                        "radiusM": -10
                    }
                """))
            .andExpect(status().isBadRequest());

        // 3. Delete office location -> must deactivate (active = false), NOT hard delete
        mockMvc.perform(delete("/api/v1/settings/office-locations/" + uuidStr)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        Boolean isActive = jdbcTemplate.queryForObject(
            "SELECT active FROM office_locations WHERE id = ?",
            Boolean.class,
            UUID.fromString(uuidStr)
        );
        assertThat(isActive).isFalse();
    }

    @Test
    @Order(5)
    void holidaysCrudAndDuplicateConflict() throws Exception {
        String adminToken = getAdminToken();
        String empToken = createTestUserToken("ORG_EMP3", "org.emp3@octopyder.com", Role.EMPLOYEE);

        // 1. Admin creates holiday
        mockMvc.perform(post("/api/v1/holidays")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "holidayDate": "2026-10-02",
                        "name": "Gandhi Jayanti"
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Gandhi Jayanti"));

        // 2. Duplicate holiday returns 409 Conflict
        mockMvc.perform(post("/api/v1/holidays")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "holidayDate": "2026-10-02",
                        "name": "Gandhi Jayanti Duplicate"
                    }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("HOLIDAY_ALREADY_EXISTS"));

        // 3. Employee can list holidays
        mockMvc.perform(get("/api/v1/holidays?year=2026")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.holidayDate == '2026-10-02')].name").value("Gandhi Jayanti"));

        // 4. Admin deletes holiday
        mockMvc.perform(delete("/api/v1/holidays/2026-10-02")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());
    }

    @Test
    @Order(6)
    void backupWebhookSecretVerificationAndExecution() throws Exception {
        String secret = "test-backup-webhook-secret-xyz";
        System.setProperty("BACKUP_WEBHOOK_SECRET", secret);

        // 1. Missing secret -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/backups/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "status": "SUCCESS",
                        "sizeBytes": 47185920,
                        "location": "s3://backups/2026-10-27.sql.gz",
                        "startedAt": "2026-10-27T10:00:00Z",
                        "finishedAt": "2026-10-27T10:05:00Z",
                        "triggeredBy": "GITHUB_ACTION"
                    }
                """))
            .andExpect(status().isUnauthorized());

        // 2. Wrong secret -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/backups/webhook")
                .header("X-Backup-Secret", "wrong-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "status": "SUCCESS",
                        "sizeBytes": 47185920,
                        "location": "s3://backups/2026-10-27.sql.gz",
                        "startedAt": "2026-10-27T10:00:00Z",
                        "finishedAt": "2026-10-27T10:05:00Z",
                        "triggeredBy": "GITHUB_ACTION"
                    }
                """))
            .andExpect(status().isUnauthorized());

        // 3. Correct secret -> 201 Created
        mockMvc.perform(post("/api/v1/backups/webhook")
                .header("X-Backup-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "status": "SUCCESS",
                        "sizeBytes": 47185920,
                        "location": "s3://backups/2026-10-27.sql.gz",
                        "startedAt": "2026-10-27T10:00:00Z",
                        "finishedAt": "2026-10-27T10:05:00Z",
                        "triggeredBy": "GITHUB_ACTION"
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 4. Admin can list backups
        String adminToken = getAdminToken();
        mockMvc.perform(get("/api/v1/backups")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    @Order(7)
    void logoUploadValidationAndStorage() throws Exception {
        String adminToken = getAdminToken();

        // 1. Valid PNG upload
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        MockMultipartFile validLogo = new MockMultipartFile("file", "company_logo.png", "image/png", pngHeader);

        mockMvc.perform(multipart("/api/v1/settings/logo")
                .file(validLogo)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.logoUrl").exists());

        // 2. Reject file > 2 MB
        byte[] oversizedBytes = new byte[2 * 1024 * 1024 + 1024];
        MockMultipartFile oversized = new MockMultipartFile("file", "huge.png", "image/png", oversizedBytes);

        mockMvc.perform(multipart("/api/v1/settings/logo")
                .file(oversized)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("STORAGE_FILE_TOO_LARGE"));

        // 3. Reject non-image (.pdf)
        byte[] pdfBytes = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile pdfFile = new MockMultipartFile("file", "document.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/api/v1/settings/logo")
                .file(pdfFile)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("STORAGE_INVALID_FILE_TYPE"));
    }

    @Test
    @Order(8)
    void passwordMinLengthChangeTakesImmediateEffect() throws Exception {
        String adminToken = getAdminToken();
        String userToken = createTestUserToken("PWD_USER", "pwd.user@octopyder.com", Role.EMPLOYEE);

        // Update password min length to 12
        mockMvc.perform(put("/api/v1/settings/security")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "security.passwordMinLength": 12
                    }
                """))
            .andExpect(status().isOk());

        // User tries changing password to 8-character password ("Abc!1234") -> MUST FAIL with 422 AUTH_PASSWORD_POLICY (now min is 12)
        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "currentPassword": "TestPassword123!",
                        "newPassword": "Abc!1234"
                    }
                """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_POLICY"));

        // User changes password to 14-character password -> MUST SUCCEED (>= 12)
        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "currentPassword": "TestPassword123!",
                        "newPassword": "NewStrongPass123!"
                    }
                """))
            .andExpect(status().isOk());

        // Reset back to 8
        mockMvc.perform(put("/api/v1/settings/security")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "security.passwordMinLength": 8
                    }
                """))
            .andExpect(status().isOk());
    }

    @Test
    @Order(9)
    void uploadedLogoCanBeStreamedByAnyAuthenticatedUser() throws Exception {
        String adminToken = getAdminToken();
        String userToken = createTestUserToken("LOGO_USER", "logo.user@octopyder.com", Role.EMPLOYEE);

        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        MockMultipartFile logoFile = new MockMultipartFile(
            "file",
            "company_brand.png",
            "image/png",
            pngBytes
        );

        String uploadResp = mockMvc.perform(multipart("/api/v1/settings/logo")
                .file(logoFile)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String logoUrl = com.jayway.jsonpath.JsonPath.read(uploadResp, "$.logoUrl");
        assertThat(logoUrl).isNotBlank();

        // Any authenticated user should be able to stream the company logo
        mockMvc.perform(get(logoUrl)
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @Order(10)
    void backupWebhookRejectsInvalidStatusWith400() throws Exception {
        mockMvc.perform(post("/api/v1/backups/webhook")
                .header("X-Backup-Secret", "test-backup-webhook-secret-xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "startedAt": "2026-10-28T01:00:00Z",
                        "status": "COMPLETED"
                    }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.status").value("Status must be RUNNING, SUCCESS, or FAILED"));
    }

    @Test
    @Order(11)
    void officeLocationRejectsMalformedCidrWith400() throws Exception {
        String adminToken = getAdminToken();

        mockMvc.perform(post("/api/v1/settings/office-locations")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Invalid CIDR Office",
                        "latitude": 22.5,
                        "longitude": 88.5,
                        "radiusM": 100,
                        "allowedIpCidrs": ["invalid-cidr-string/999"]
                    }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CIDR"));
    }

    @Test
    @Order(12)
    void holidayServiceRejectsInvalidNameAndDuplicateWith409() throws Exception {
        String adminToken = getAdminToken();

        // Over 100 chars
        String longName = "A".repeat(101);
        mockMvc.perform(post("/api/v1/holidays")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "holidayDate": "2026-12-25",
                        "name": "%s"
                    }
                """.formatted(longName)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }
}
