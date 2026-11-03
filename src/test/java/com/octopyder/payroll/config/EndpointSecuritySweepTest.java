package com.octopyder.payroll.config;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EndpointSecuritySweepTest extends BaseIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    private String employeeToken;

    private static final Set<String> PUBLIC_ALLOWLIST = Set.of(
        "/actuator/health",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password",
        "/api/v1/backups/webhook"
    );

    @BeforeEach
    void setUp() {
        User employee = userRepository.findByEmailIgnoreCase("sweep_emp@octopyder.com").orElseGet(() -> {
            User u = new User();
            u.setCode("SWEEP_EMP_01");
            u.setEmail("sweep_emp@octopyder.com");
            u.setPasswordHash("$2a$12$eXampleHashPaddingPaddingPaddingPaddingPaddingPaddingPa");
            u.setRole(Role.EMPLOYEE);
            u.setStatus(UserStatus.ACTIVE);
            u.setMustChangePassword(false);
            u.setFailedAttempts(0);
            u.setTokenVersion(1);
            return userRepository.save(u);
        });
        employeeToken = tokenService.generateAccessToken(employee);
    }

    @Test
    @DisplayName("Endpoint inventory: Every Octopyder mapping has @PreAuthorize or is in public allowlist")
    void everyMappingHasPreAuthorizeOrPublicAllowlist() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        List<String> violations = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod method = entry.getValue();

            // Only check our controllers, ignoring framework / error / actuator
            Class<?> beanType = method.getBeanType();
            if (!beanType.getPackageName().startsWith("com.octopyder.payroll")) {
                continue;
            }

            Set<String> patterns = info.getPatternValues();
            boolean isPublic = patterns.stream().anyMatch(PUBLIC_ALLOWLIST::contains);
            if (isPublic) {
                continue;
            }

            boolean hasRoleRule = method.getMethod().isAnnotationPresent(PreAuthorize.class)
                || beanType.isAnnotationPresent(PreAuthorize.class);

            if (!hasRoleRule) {
                violations.add(beanType.getSimpleName() + "." + method.getMethod().getName() + " mappings=" + patterns);
            }
        }

        assertThat(violations)
            .withFailMessage("The following endpoints lack @PreAuthorize role rules: " + violations)
            .isEmpty();
    }

    @Test
    @DisplayName("No-token sweep: Secured endpoints across modules return 401 UNAUTHORIZED")
    void sweepNoTokenReturnsUnauthorized() throws Exception {
        String[] securedGetPaths = {
            "/api/v1/employees",
            "/api/v1/managers",
            "/api/v1/me/profile",
            "/api/v1/settings",
            "/api/v1/attendance",
            "/api/v1/leave/me/balance",
            "/api/v1/tasks",
            "/api/v1/payroll/summary?month=2026-10",
            "/api/v1/audit-logs",
            "/api/v1/reports/employees",
            "/api/v1/dashboard/admin",
            "/api/v1/calendar/events",
            "/api/v1/notifications"
        };

        for (String path : securedGetPaths) {
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("Wrong-role sweep: Employee token calling Admin-only endpoints returns 403 FORBIDDEN")
    void sweepWrongRoleReturnsForbidden() throws Exception {
        String[] adminGetEndpoints = {
            "/api/v1/managers",
            "/api/v1/settings",
            "/api/v1/audit-logs",
            "/api/v1/reports/employees",
            "/api/v1/dashboard/admin"
        };

        for (String path : adminGetEndpoints) {
            mockMvc.perform(get(path)
                    .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
        }

        // POST/DELETE admin endpoints
        mockMvc.perform(post("/api/v1/employees/00000000-0000-0000-0000-000000000000/reset-password")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/employees/00000000-0000-0000-0000-000000000000")
                .header("Authorization", "Bearer " + employeeToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/leave/admin-apply")
                .header("Authorization", "Bearer " + employeeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"00000000-0000-0000-0000-000000000000","type":"CASUAL","startDate":"2026-11-02","endDate":"2026-11-03","reason":"Test"}
                """))
            .andExpect(status().isForbidden());
    }
}
