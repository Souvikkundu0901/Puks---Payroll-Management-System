package com.octopyder.payroll.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-10-26T10:00:00Z"));
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(clock);

    @Test
    void handleAppExceptionReturnsProblemDetailWithCode() {
        AppException ex = new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Access denied");
        ResponseEntity<ProblemDetail> response = handler.handleAppException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(403);
        assertThat(body.getDetail()).isEqualTo("Access denied");
        assertThat(body.getProperties()).containsEntry("code", "FORBIDDEN_SCOPE");
        assertThat(body.getProperties()).containsEntry("timestamp", "2026-10-26T10:00:00Z");
    }

    @Test
    void handleGeneralExceptionHidesStackTrace() {
        Exception ex = new RuntimeException("Sensitive database credentials leaked in stack trace");
        ResponseEntity<ProblemDetail> response = handler.handleGeneralException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail()).isEqualTo("An internal error occurred");
        assertThat(body.getProperties()).containsEntry("code", "INTERNAL_SERVER_ERROR");
        assertThat(body.getDetail()).doesNotContain("Sensitive");
    }
}
