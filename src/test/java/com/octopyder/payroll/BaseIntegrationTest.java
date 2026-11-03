package com.octopyder.payroll;

import com.octopyder.payroll.auth.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base test class for integration tests running against a real PostgreSQL 16
 * Testcontainers database with Flyway migrations applied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class BaseIntegrationTest {

    @Autowired(required = false)
    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetRateLimits() {
        if (loginAttemptService != null) {
            loginAttemptService.clear();
        }
    }
}
