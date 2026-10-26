package com.octopyder.payroll.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * RFC 9457 ProblemDetail exception handler providing stable machine-readable codes
 * and structured validation errors without leaking internal stack traces.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final BusinessClock businessClock;

    public GlobalExceptionHandler(BusinessClock businessClock) {
        this.businessClock = businessClock;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("code", ex.getCode());
        problem.setProperty("timestamp", businessClock.instant().toString());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("timestamp", businessClock.instant().toString());

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("fieldErrors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        problem.setProperty("code", ex.getStatusCode().toString());
        problem.setProperty("timestamp", businessClock.instant().toString());
        return ResponseEntity.status(ex.getStatusCode()).body(problem);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        problem.setProperty("code", "FORBIDDEN");
        problem.setProperty("timestamp", businessClock.instant().toString());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(org.springframework.security.core.AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Unauthenticated");
        problem.setProperty("code", "UNAUTHENTICATED");
        problem.setProperty("timestamp", businessClock.instant().toString());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneralException(Exception ex) {
        log.error("Unhandled exception: ", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");
        problem.setProperty("code", "INTERNAL_SERVER_ERROR");
        problem.setProperty("timestamp", businessClock.instant().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
