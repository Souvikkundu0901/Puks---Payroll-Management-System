package com.octopyder.payroll.people;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PeopleSecurityVulnerabilitiesTest extends BaseIntegrationTest {

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

    private String createTestUserToken(String code, String email, Role role, UUID managerId) {
        List<UUID> existingIds = jdbcTemplate.query(
            "SELECT id FROM users WHERE email = ? OR code = ?",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            email, code
        );

        UUID id;
        int tokenVersion = 1;
        if (!existingIds.isEmpty()) {
            id = existingIds.getFirst();
            jdbcTemplate.update("""
                UPDATE users SET code = ?, email = ?, role = ?, status = 'ACTIVE', manager_id = ?, deleted_at = NULL
                WHERE id = ?
            """, code, email, role.name(), managerId, id);
            tokenVersion = jdbcTemplate.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, id);
        } else {
            id = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version)
                VALUES (?, ?, ?, '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', ?, 'ACTIVE', ?, 1)
            """, id, code, email, role.name(), managerId);
        }

        jdbcTemplate.update("""
            INSERT INTO employee_profiles (user_id, first_name, last_name, designation, base_salary)
            VALUES (?, 'Test', 'User', 'Engineer', 50000.00)
            ON CONFLICT (user_id) DO NOTHING
        """, id);

        User user = new User();
        user.setId(id);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setManagerId(managerId);
        user.setTokenVersion(tokenVersion);
        return tokenService.generateAccessToken(user);
    }

    @Test
    void idorDocumentStreamBlockedForUnauthorizedUser() throws Exception {
        String emp1Token = createTestUserToken("SEC_EMP1", "sec.emp1@octopyder.com", Role.EMPLOYEE, null);
        String emp2Token = createTestUserToken("SEC_EMP2", "sec.emp2@octopyder.com", Role.EMPLOYEE, null);
        String adminToken = getAdminToken();

        // 1. Employee 1 uploads personal PDF document
        byte[] pdfBytes = "%PDF-1.4 test document content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile docFile = new MockMultipartFile("file", "confidential.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/api/v1/me/documents")
                .file(docFile)
                .param("docType", "RESUME")
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isCreated());

        String storageKey = jdbcTemplate.queryForObject(
            "SELECT storage_key FROM employee_documents WHERE original_name = 'confidential.pdf' ORDER BY uploaded_at DESC LIMIT 1",
            String.class
        );
        assertThat(storageKey).isNotNull();

        // 2. Employee 2 attempts to stream Employee 1's document -> MUST BE 403 FORBIDDEN
        mockMvc.perform(get("/api/v1/documents/stream/" + storageKey)
                .header("Authorization", "Bearer " + emp2Token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 3. Employee 1 (owner) streams document -> 200 OK
        mockMvc.perform(get("/api/v1/documents/stream/" + storageKey)
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isOk());

        // 4. Admin streams document -> 200 OK
        mockMvc.perform(get("/api/v1/documents/stream/" + storageKey)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // 5. Employee 1 uploads profile photo (avatar)
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile photoFile = new MockMultipartFile("file", "avatar.png", "image/png", pngHeader);

        mockMvc.perform(multipart("/api/v1/me/photo")
                .file(photoFile)
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isOk());

        String photoKey = jdbcTemplate.queryForObject(
            "SELECT photo_key FROM employee_profiles p JOIN users u ON p.user_id = u.id WHERE u.email = 'sec.emp1@octopyder.com'",
            String.class
        );
        assertThat(photoKey).isNotNull();

        // 6. Other employees CAN stream avatar photo (avatars are public within the org)
        mockMvc.perform(get("/api/v1/documents/stream/" + photoKey)
                .header("Authorization", "Bearer " + emp2Token))
            .andExpect(status().isOk());

        // 7. Non-existent storage key returns 404 NOT_FOUND
        mockMvc.perform(get("/api/v1/documents/stream/nonexistent-key-12345.pdf")
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void uploadDocumentRejectsExcessivelyLongDocTypeWithBadRequest() throws Exception {
        String emp1Token = createTestUserToken("SEC_EMP3", "sec.emp3@octopyder.com", Role.EMPLOYEE, null);

        byte[] pdfBytes = "%PDF-1.4 test document content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile docFile = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes);

        String tooLongDocType = "A".repeat(60);

        // Must reject with 400 Bad Request and stable code INVALID_DOC_TYPE (NOT 500 DB truncation)
        mockMvc.perform(multipart("/api/v1/me/documents")
                .file(docFile)
                .param("docType", tooLongDocType)
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DOC_TYPE"));
    }

    @Test
    void importEmployeesCsvRejectsLargeFileAndInvalidFileType() throws Exception {
        String adminToken = getAdminToken();

        // 1. File > 5 MB
        byte[] oversizedBytes = new byte[5 * 1024 * 1024 + 1024]; // 5 MB + 1 KB
        MockMultipartFile oversizedFile = new MockMultipartFile("file", "huge.csv", "text/csv", oversizedBytes);

        mockMvc.perform(multipart("/api/v1/employees/import")
                .file(oversizedFile)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("STORAGE_FILE_TOO_LARGE"));

        // 2. Non-CSV file extension (e.g. evil.exe)
        byte[] exeBytes = "MZ...executable".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile exeFile = new MockMultipartFile("file", "evil.exe", "application/octet-stream", exeBytes);

        mockMvc.perform(multipart("/api/v1/employees/import")
                .file(exeFile)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"));
    }

    @Test
    void managerDeletionWithActiveTeamMembersFails() throws Exception {
        String adminToken = getAdminToken();

        // Create manager
        String mgrToken = createTestUserToken("SEC_MGR1", "sec.mgr1@octopyder.com", Role.MANAGER, null);
        UUID mgrId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'sec.mgr1@octopyder.com'", UUID.class);

        // Assign employee to this manager
        createTestUserToken("SEC_SUB1", "sec.sub1@octopyder.com", Role.EMPLOYEE, mgrId);

        // Attempt to delete manager via /api/v1/managers/{id} -> 409 CONFLICT MANAGER_HAS_TEAM
        mockMvc.perform(delete("/api/v1/managers/" + mgrId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MANAGER_HAS_TEAM"));

        // Attempt to delete manager via /api/v1/employees/{id} -> 409 CONFLICT MANAGER_HAS_TEAM
        mockMvc.perform(delete("/api/v1/employees/" + mgrId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MANAGER_HAS_TEAM"));
    }
}
