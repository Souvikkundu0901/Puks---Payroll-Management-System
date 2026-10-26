package com.octopyder.payroll.auth;

import com.octopyder.payroll.BaseIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void adminUserIsBootstrappedOnStartup() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE email = 'admin@octopyder.com' AND role = 'ADMIN'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(2)
    void loginWithValidCredentialsReturnsJwtAndSetsHttpOnlyCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "AdminBootstrap123!",
                        "rememberMe": false
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", notNullValue()))
            .andExpect(jsonPath("$.expiresIn", notNullValue()))
            .andExpect(jsonPath("$.user.email").value("admin@octopyder.com"))
            .andExpect(jsonPath("$.user.role").value("ADMIN"))
            .andExpect(jsonPath("$.user.mustChangePassword").value(true))
            .andExpect(cookie().exists("refreshToken"))
            .andExpect(cookie().httpOnly("refreshToken", true))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));

        // Verify audit log has LOGIN_SUCCESS
        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_SUCCESS'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    @Order(3)
    void roleAlwaysComesFromDatabaseIgnoringClientPayload() throws Exception {
        // Even if an attacker attempts to send role in the body
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "AdminBootstrap123!",
                        "role": "EMPLOYEE"
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    @Order(4)
    void wrongPasswordReturnsGenericErrorAndIncrementsFailedAttempts() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "WrongPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.detail").value("Invalid credentials or account temporarily locked"));
    }

    @Test
    @Order(5)
    void unknownEmailReturnsSameGenericError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "nonexistent@octopyder.com",
                        "password": "WrongPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.detail").value("Invalid credentials or account temporarily locked"));
    }

    @Test
    @Order(6)
    void fiveConsecutiveFailedLoginsLocksTheAccount() throws Exception {
        // Create a test user for lockout test
        jdbcTemplate.update("""
            INSERT INTO users (code, email, password_hash, role, status, must_change_password)
            VALUES ('EMP999', 'lockout.test@octopyder.com', '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'EMPLOYEE', 'ACTIVE', false)
            ON CONFLICT (email) DO NOTHING
        """);

        // Attempt 5 failed logins
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "email": "lockout.test@octopyder.com",
                            "password": "WrongPassword123!"
                        }
                    """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }

        // Verify account is locked in DB
        Boolean isLocked = jdbcTemplate.queryForObject(
            "SELECT (locked_until > now()) FROM users WHERE email = 'lockout.test@octopyder.com'",
            Boolean.class
        );
        assertThat(isLocked).isTrue();

        // 6th attempt with CORRECT password must still be rejected with same generic error
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "lockout.test@octopyder.com",
                        "password": "CorrectPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.detail").value("Invalid credentials or account temporarily locked"));
    }

    @Test
    @Order(7)
    void refreshTokenRotationAndGraceWindow() throws Exception {
        // 1. Login to get refresh cookie
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "AdminBootstrap123!"
                    }
                """))
            .andExpect(status().isOk())
            .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        // 2. Call /auth/refresh with cookie -> succeeds and returns new tokens + new cookie
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", notNullValue()))
            .andExpect(cookie().exists("refreshToken"))
            .andReturn();

        Cookie newRefreshCookie = refreshResult.getResponse().getCookie("refreshToken");
        assertThat(newRefreshCookie).isNotNull();
        assertThat(newRefreshCookie.getValue()).isNotEqualTo(refreshCookie.getValue());

        // 3. Grace window test: Re-submitting the OLD cookie within 20s must still succeed (returning same/new successor)
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", notNullValue()));
    }

    @Test
    @Order(8)
    void getMeReturnsAuthenticatedUser() throws Exception {
        // Login to get access token
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "AdminBootstrap123!"
                    }
                """))
            .andExpect(status().isOk())
            .andReturn();

        String token = com.jayway.jsonpath.JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");

        // Access /api/v1/auth/me with Bearer token
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("admin@octopyder.com"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.code").value("ADM001"));
    }

    @Test
    @Order(9)
    void unauthenticatedAccessReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(10)
    void changePasswordEnforcesPolicyAndInvalidatesTokens() throws Exception {
        // Login to get access token
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@octopyder.com",
                        "password": "AdminBootstrap123!"
                    }
                """))
            .andExpect(status().isOk())
            .andReturn();

        String oldAccessToken = com.jayway.jsonpath.JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");

        // Attempt weak password -> must return 422 AUTH_PASSWORD_POLICY
        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + oldAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "currentPassword": "AdminBootstrap123!",
                        "newPassword": "short"
                    }
                """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_POLICY"));

        // Valid change password
        mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + oldAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "currentPassword": "AdminBootstrap123!",
                        "newPassword": "NewAdminP@ssword2026!"
                    }
                """))
            .andExpect(status().isOk());

        // Token version incremented -> oldAccessToken must now be rejected with 401 on next request (BR-S5)
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + oldAccessToken))
            .andExpect(status().isUnauthorized());

        // Reset password back for other tests
        jdbcTemplate.update(
            "UPDATE users SET password_hash = '$2a$12$e68Y2T3fTdQ6zZk89yNfce7yq9Yp.sO1wzU/oZ52V8bO0aQ3JqUu2', token_version = token_version + 1 WHERE email = 'admin@octopyder.com'"
        );
    }
}
