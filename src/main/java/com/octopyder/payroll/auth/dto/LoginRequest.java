package com.octopyder.payroll.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "Email is required")
    String email,

    @NotBlank(message = "Password is required")
    String password,

    Boolean rememberMe,

    String role
) {
    public boolean isRememberMe() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
