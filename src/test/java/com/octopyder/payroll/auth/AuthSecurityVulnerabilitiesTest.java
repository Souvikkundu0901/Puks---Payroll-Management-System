package com.octopyder.payroll.auth;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.audit.AuditLogRepository;
import com.octopyder.payroll.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthSecurityVulnerabilitiesTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void postLockoutExpiredLockoutResetsCounterOnSubsequentAttempt() throws Exception {
        // Create user with expired lock and 5 failed attempts
        jdbcTemplate.update("""
            INSERT INTO users (code, email, password_hash, role, status, failed_attempts, locked_until)
            VALUES ('EMP_LOCK_TEST', 'expired.lock@octopyder.com', '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'EMPLOYEE', 'ACTIVE', 5, now() - INTERVAL '1 minute')
            ON CONFLICT (email) DO UPDATE SET failed_attempts = 5, locked_until = now() - INTERVAL '1 minute'
        """);

        // Attempt 1 failed login after expiration
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "expired.lock@octopyder.com",
                        "password": "WrongPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        // Counter must be 1, not 6, and account must NOT be locked
        Integer failedAttempts = jdbcTemplate.queryForObject(
            "SELECT failed_attempts FROM users WHERE email = 'expired.lock@octopyder.com'",
            Integer.class
        );
        Boolean isLocked = jdbcTemplate.queryForObject(
            "SELECT (locked_until IS NOT NULL AND locked_until > now()) FROM users WHERE email = 'expired.lock@octopyder.com'",
            Boolean.class
        );

        assertThat(failedAttempts).isEqualTo(1);
        assertThat(isLocked).isFalse();
    }

    @Test
    void oversizedRequestIdIsTruncatedWithoutCrashingAuditLog() {
        String longRequestId = "A".repeat(150);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", longRequestId);

        // Must not throw DataIntegrityViolationException
        auditService.logWithActor(null, null, "SECURITY_PROBE", "SYSTEM", "TEST", null, null, request);

        String savedRequestId = jdbcTemplate.queryForObject(
            "SELECT request_id FROM audit_logs WHERE action = 'SECURITY_PROBE' ORDER BY id DESC LIMIT 1",
            String.class
        );

        assertThat(savedRequestId).isNotNull();
        assertThat(savedRequestId.length()).isLessThanOrEqualTo(64);
        assertThat(savedRequestId).isEqualTo("A".repeat(64));
    }

    @Test
    void unauthenticatedProxyRejectsForgedXffHeader() throws Exception {
        // When APP_TRUSTED_PROXY=false, client sending X-Forwarded-For should have its real IP logged in audit
        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "203.0.113.195")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "unknown.user@octopyder.com",
                        "password": "WrongPassword123!"
                    }
                """))
            .andExpect(status().isUnauthorized());

        String clientIpLogged = jdbcTemplate.queryForObject(
            "SELECT ip FROM audit_logs WHERE action = 'LOGIN_FAILED' AND entity_id = 'unknown.user@octopyder.com' ORDER BY id DESC LIMIT 1",
            String.class
        );

        // Should NOT be the forged X-Forwarded-For IP
        assertThat(clientIpLogged).isNotEqualTo("203.0.113.195");
    }
}
