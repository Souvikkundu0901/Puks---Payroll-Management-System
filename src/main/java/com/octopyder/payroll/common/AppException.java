package com.octopyder.payroll.common;

import org.springframework.http.HttpStatus;

/**
 * Base domain exception carrying an HTTP status, stable error code, and developer/user message.
 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, Object> properties;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = java.util.Collections.emptyMap();
    }

    public AppException(HttpStatus status, String code, String message, java.util.Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties != null ? properties : java.util.Collections.emptyMap();
    }

    public AppException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.properties = java.util.Collections.emptyMap();
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public java.util.Map<String, Object> getProperties() {
        return properties;
    }
}
