package com.octopyder.payroll.auth;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.org.BackupService;
import com.octopyder.payroll.payroll.dto.PayrollPreviewRequest;
import com.octopyder.payroll.payroll.service.PayrollService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.report.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RateLimitSecurityTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportService reportService;

    @Autowired
    private PayrollService payrollService;

    @Autowired
    private BackupService backupService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @org.junit.jupiter.api.BeforeEach
    void resetLimits() {
        loginAttemptService.clear();
        payrollService.clearRateLimits();
    }

    @Test
    @DisplayName("Login rate limit: Exceeding 10 attempts for the same email triggers 429 Too Many Requests")
    void loginRateLimitByEmail() throws Exception {
        String testEmail = "rate_limit_email_" + UUID.randomUUID() + "@octopyder.com";

        // First 10 requests consume email tokens
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + testEmail + "\",\"password\":\"WrongPassword123!\"}"))
                .andExpect(status().isUnauthorized());
        }

        // 11th request must be rate limited (429)
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + testEmail + "\",\"password\":\"WrongPassword123!\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Login rate limit: Spoofing X-Forwarded-For cannot bypass IP rate limit when untrusted proxy")
    void loginRateLimitXffCannotBypassWhenUntrustedProxy() throws Exception {
        // When app.trusted-proxy is false (default), the server ignores X-Forwarded-For
        // and keys on remoteAddr (127.0.0.1 in MockMvc). Capacity is 30/min.
        for (int i = 1; i <= 30; i++) {
            String randomEmail = "spoof_" + i + "_" + UUID.randomUUID() + "@example.com";
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", "198.51.100." + i) // Spoofed IP header
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + randomEmail + "\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized());
        }

        // 31st request from same remote address must be blocked with 429 even with a different XFF IP
        mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Forwarded-For", "198.51.100.99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"spoof_31_" + UUID.randomUUID() + "@example.com\",\"password\":\"Password123!\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Report export rate limit: Exceeding 10 exports per minute per user triggers 429")
    void reportExportRateLimit() {
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), "ADM_RATE", "admin_rate@test.com", Role.ADMIN, 1L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 10 permitted
        for (int i = 0; i < 10; i++) {
            reportService.exportReport(admin, "employees", "csv", null, null, null, null, null, null, response);
        }

        // 11th must fail with 429 RATE_LIMITED
        assertThatThrownBy(() ->
            reportService.exportReport(admin, "employees", "csv", null, null, null, null, null, null, response)
        ).isInstanceOf(AppException.class)
         .satisfies(ex -> {
             AppException appEx = (AppException) ex;
             assertThat(appEx.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
             assertThat(appEx.getCode()).isEqualTo("RATE_LIMITED");
         });
    }

    @Test
    @DisplayName("Payroll preview rate limit: Exceeding 60 previews per minute per user triggers 429")
    void payrollPreviewRateLimit() {
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), "ADM_PAY", "admin_pay@test.com", Role.ADMIN, 1L);
        PayrollPreviewRequest req = new PayrollPreviewRequest(UUID.randomUUID(), "2026-10", false);

        // Consume 60 tokens
        for (int i = 0; i < 60; i++) {
            try {
                payrollService.previewPayroll(admin, req);
            } catch (AppException e) {
                if ("RATE_LIMITED".equals(e.getCode())) {
                    break;
                }
            }
        }

        // Next call must be blocked with 429 RATE_LIMITED
        assertThatThrownBy(() -> payrollService.previewPayroll(admin, req))
            .isInstanceOf(AppException.class)
            .satisfies(ex -> {
                AppException appEx = (AppException) ex;
                assertThat(appEx.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(appEx.getCode()).isEqualTo("RATE_LIMITED");
            });
    }

    @Test
    @DisplayName("Backup webhook rate limit: Exceeding 10 webhook invocations per minute triggers 429")
    void backupWebhookRateLimit() {
        // Consume 10
        for (int i = 0; i < 10; i++) {
            try {
                backupService.handleWebhook("dummy", null);
            } catch (AppException ignored) {
                // Ignore secret mismatch exceptions
            }
        }

        // 11th call must trigger 429 RATE_LIMITED
        assertThatThrownBy(() -> backupService.handleWebhook("dummy", null))
            .isInstanceOf(AppException.class)
            .satisfies(ex -> {
                AppException appEx = (AppException) ex;
                assertThat(appEx.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(appEx.getCode()).isEqualTo("RATE_LIMITED");
            });
    }
}
