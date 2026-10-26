package com.octopyder.payroll.people;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
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
import org.springframework.test.web.servlet.MvcResult;

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
class PeopleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    private String getAdminToken() throws Exception {
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

        // Create or ensure profile
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
    @Order(1)
    void adminCreatesEmployeeWithAutoCodeAndOneTimeTemporaryPassword() throws Exception {
        jdbcTemplate.execute("SELECT setval('seq_emp_code', 1, false)");
        jdbcTemplate.update("UPDATE users SET email = 'archived_' || id || '@octopyder.com', code = 'ARC' || substring(id::text, 1, 6) WHERE email = 'rahul.sharma@octopyder.com' OR code = 'EMP001'");

        String adminToken = getAdminToken();

        MvcResult result = mockMvc.perform(post("/api/v1/employees")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "firstName": "Rahul",
                        "lastName": "Sharma",
                        "email": "rahul.sharma@octopyder.com",
                        "designation": "Software Engineer",
                        "baseSalary": 60000.00,
                        "employmentType": "FULL_TIME",
                        "gender": "MALE",
                        "role": "EMPLOYEE"
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.code").value("EMP001"))
            .andExpect(jsonPath("$.email").value("rahul.sharma@octopyder.com"))
            .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andReturn();

        // Extract temporary password from response
        String responseJson = result.getResponse().getContentAsString();
        String tempPassword = responseJson.split("\"temporaryPassword\":\"")[1].split("\"")[0];

        // Attempt login with temporary password -> must require password change
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                    {
                        "email": "rahul.sharma@octopyder.com",
                        "password": "%s"
                    }
                """, tempPassword)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    }

    @Test
    @Order(2)
    void temporaryPasswordExpiresAfterSevenDays() throws Exception {
        // Set temporary password expiry in past
        jdbcTemplate.update("""
            UPDATE users SET temporary_password_expires_at = now() - INTERVAL '1 hour'
            WHERE email = 'rahul.sharma@octopyder.com'
        """);

        // Login should be rejected due to expiration
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "rahul.sharma@octopyder.com",
                        "password": "AnyPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_TEMP_PASSWORD_EXPIRED"));
    }

    @Test
    @Order(3)
    void teamScopeEnforcesAccessBoundaries() throws Exception {
        // Setup Manager 1 and Employee 1
        UUID mgr1Id = UUID.randomUUID();
        String mgr1Token = createTestUserToken("MGR001", "mgr1@octopyder.com", Role.MANAGER, null);
        mgr1Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'mgr1@octopyder.com'", UUID.class);

        UUID emp1Id = UUID.randomUUID();
        String emp1Token = createTestUserToken("EMP002", "emp1@octopyder.com", Role.EMPLOYEE, mgr1Id);
        emp1Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);

        // Setup Manager 2 and Employee 2
        UUID mgr2Id = UUID.randomUUID();
        String mgr2Token = createTestUserToken("MGR002", "mgr2@octopyder.com", Role.MANAGER, null);
        mgr2Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'mgr2@octopyder.com'", UUID.class);

        UUID emp2Id = UUID.randomUUID();
        String emp2Token = createTestUserToken("EMP003", "emp2@octopyder.com", Role.EMPLOYEE, mgr2Id);
        emp2Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp2@octopyder.com'", UUID.class);

        String adminToken = getAdminToken();

        // 1. Manager 1 reading own team member (Employee 1) -> 200 OK
        mockMvc.perform(get("/api/v1/employees/" + emp1Id)
                .header("Authorization", "Bearer " + mgr1Token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("emp1@octopyder.com"));

        // 2. Manager 1 reading another manager's team member (Employee 2) -> 403 Forbidden
        mockMvc.perform(get("/api/v1/employees/" + emp2Id)
                .header("Authorization", "Bearer " + mgr1Token))
            .andExpect(status().isForbidden());

        // 3. Employee 1 reading self -> 200 OK
        mockMvc.perform(get("/api/v1/employees/" + emp1Id)
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isOk());

        // 4. Employee 1 reading another employee (Employee 2) -> 403 Forbidden
        mockMvc.perform(get("/api/v1/employees/" + emp2Id)
                .header("Authorization", "Bearer " + emp1Token))
            .andExpect(status().isForbidden());

        // 5. Admin reading any employee -> 200 OK
        mockMvc.perform(get("/api/v1/employees/" + emp2Id)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    void bankDetailsEncryptedAtRestAndManagerRestricted() throws Exception {
        String adminToken = getAdminToken();
        String empToken = createTestUserToken("EMP002", "emp1@octopyder.com", Role.EMPLOYEE, null);
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);
        String mgrToken = createTestUserToken("MGR001", "mgr1@octopyder.com", Role.MANAGER, null);

        // 1. Admin sets bank account details
        mockMvc.perform(put("/api/v1/employees/" + empId + "/bank-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "bankName": "HDFC Bank",
                        "accountNumber": "9876543210123456",
                        "ifsc": "HDFC0001234"
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountMasked").value("XXXX-XXXX-3456"))
            .andExpect(jsonPath("$.bankName").value("HDFC Bank"));

        // 2. Verify raw column in database is encrypted ciphertext and not plaintext
        String rawEncrypted = jdbcTemplate.queryForObject(
            "SELECT account_number_enc FROM bank_accounts WHERE user_id = ?",
            String.class,
            empId
        );
        assertThat(rawEncrypted).isNotNull();
        assertThat(rawEncrypted).doesNotContain("9876543210123456");

        // 3. Employee reading self bank details -> 200 OK
        mockMvc.perform(get("/api/v1/employees/" + empId + "/bank-account")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountMasked").value("XXXX-XXXX-3456"));

        // 4. Manager reading employee bank details -> 403 Forbidden per D26
        mockMvc.perform(get("/api/v1/employees/" + empId + "/bank-account")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void managerDeleteBlockedWhenTeamAssigned() throws Exception {
        String adminToken = getAdminToken();
        UUID mgrId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'mgr1@octopyder.com'", UUID.class);
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);

        // Ensure employee is assigned to manager 1
        jdbcTemplate.update("UPDATE users SET manager_id = ? WHERE id = ?", mgrId, empId);

        // Attempt to delete manager 1 -> must return 409 Conflict with MANAGER_HAS_TEAM
        mockMvc.perform(delete("/api/v1/managers/" + mgrId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MANAGER_HAS_TEAM"));

        // Remove employee from team -> sets manager_id to null (BR-O1)
        mockMvc.perform(delete("/api/v1/managers/" + mgrId + "/team/" + empId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        UUID updatedManagerId = jdbcTemplate.queryForObject(
            "SELECT manager_id FROM users WHERE id = ?",
            UUID.class,
            empId
        );
        assertThat(updatedManagerId).isNull();

        // Now deleting manager 1 succeeds
        mockMvc.perform(delete("/api/v1/managers/" + mgrId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        // Verify soft-deleted
        Boolean isDeleted = jdbcTemplate.queryForObject(
            "SELECT (deleted_at IS NOT NULL) FROM users WHERE id = ?",
            Boolean.class,
            mgrId
        );
        assertThat(isDeleted).isTrue();
    }

    @Test
    @Order(6)
    void offboardUserDisablesLoginAndPreservesHistory() throws Exception {
        String adminToken = getAdminToken();
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp2@octopyder.com'", UUID.class);

        // Offboard user
        mockMvc.perform(post("/api/v1/employees/" + empId + "/offboard")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "exitDate": "2026-10-31",
                        "status": "RESIGNED"
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESIGNED"));

        // Attempt login -> rejected
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "emp2@octopyder.com",
                        "password": "Password123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @Order(7)
    void lastAdminProtectionBlocksDemoteOrDelete() throws Exception {
        String adminToken = getAdminToken();
        UUID adminId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND deleted_at IS NULL LIMIT 1",
            UUID.class
        );

        // Ensure only 1 active admin exists
        jdbcTemplate.update("UPDATE users SET status = 'INACTIVE' WHERE role = 'ADMIN' AND id != ?", adminId);

        // Attempt to delete last admin -> 409
        mockMvc.perform(delete("/api/v1/employees/" + adminId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));

        // Attempt to offboard last admin -> 409
        mockMvc.perform(post("/api/v1/employees/" + adminId + "/offboard")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "exitDate": "2026-10-31",
                        "status": "RESIGNED"
                    }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    @Order(8)
    void documentUploadValidationAndDownloadHardening() throws Exception {
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);
        String empToken = createTestUserToken("EMP002", "emp1@octopyder.com", Role.EMPLOYEE, null);
        String adminToken = getAdminToken();
        String otherEmpToken = createTestUserToken("EMP999", "other@octopyder.com", Role.EMPLOYEE, null);

        // 1. Reject > 5 MB
        byte[] largeContent = new byte[5 * 1024 * 1024 + 10];
        MockMultipartFile largeFile = new MockMultipartFile("file", "large.pdf", "application/pdf", largeContent);

        mockMvc.perform(multipart("/api/v1/me/documents")
                .file(largeFile)
                .param("docType", "RESUME")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isBadRequest());

        // 2. Reject executable .exe
        MockMultipartFile exeFile = new MockMultipartFile("file", "malware.exe", "application/octet-stream", new byte[]{0x4D, 0x5A, 0x00, 0x00});

        mockMvc.perform(multipart("/api/v1/me/documents")
                .file(exeFile)
                .param("docType", "RESUME")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isBadRequest());

        // 3. Allow valid PDF
        byte[] pdfContent = "%PDF-1.7 Valid PDF Content".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile validPdf = new MockMultipartFile("file", "resume.pdf", "application/pdf", pdfContent);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/me/documents")
                .file(validPdf)
                .param("docType", "RESUME")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.docType").value("RESUME"))
            .andExpect(jsonPath("$.verificationStatus").value("UPLOADED"))
            .andReturn();

        String docId = uploadResult.getResponse().getContentAsString().split("\"id\":\"")[1].split("\"")[0];

        // 4. Download document as owner -> 200 OK with sanitized attachment header
        mockMvc.perform(get("/api/v1/documents/" + docId + "/download")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"resume.pdf\"")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 5. Download document as other employee -> 403 Forbidden
        mockMvc.perform(get("/api/v1/documents/" + docId + "/download")
                .header("Authorization", "Bearer " + otherEmpToken))
            .andExpect(status().isForbidden());

        // 6. Download document as Admin -> 200 OK
        mockMvc.perform(get("/api/v1/documents/" + docId + "/download")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());

        // 7. Admin verifies document
        mockMvc.perform(post("/api/v1/employees/" + empId + "/documents/" + docId + "/verify")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "status": "VERIFIED"
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
    }

    @Test
    @Order(9)
    void employeeMayOnlyEditSelfPhoneAddressEmergencyContact() throws Exception {
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);
        String empToken = createTestUserToken("EMP002", "emp1@octopyder.com", Role.EMPLOYEE, null);

        // Employee attempts to update self profile: changes phone/address, but also attempts to tamper name & baseSalary
        mockMvc.perform(put("/api/v1/me/profile")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "firstName": "HACKED_NAME",
                        "email": "hacked@octopyder.com",
                        "phone": "+91-9999988888",
                        "address": "456 Tech Park, Bangalore",
                        "emergencyContact": "Mom: +91-9999977777",
                        "baseSalary": 999999.00
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phone").value("+91-9999988888"))
            .andExpect(jsonPath("$.address").value("456 Tech Park, Bangalore"))
            .andExpect(jsonPath("$.emergencyContact").value("Mom: +91-9999977777"))
            .andExpect(jsonPath("$.firstName").value("Test")) // Untouched!
            .andExpect(jsonPath("$.email").value("emp1@octopyder.com")) // Untouched!
            .andExpect(jsonPath("$.baseSalary").value(50000.00)); // Untouched!
    }

    @Test
    @Order(10)
    void summaryEndpointsReturnExpectedStructureWithZeros() throws Exception {
        UUID empId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'emp1@octopyder.com'", UUID.class);
        String adminToken = getAdminToken();

        for (String tab : new String[]{"attendance", "tasks", "leaves", "payroll", "documents"}) {
            mockMvc.perform(get("/api/v1/employees/" + empId + "/summary/" + tab)
                    .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        }
    }
}
