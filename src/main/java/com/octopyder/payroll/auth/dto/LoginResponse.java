package com.octopyder.payroll.auth.dto;

public record LoginResponse(
    String accessToken,
    long expiresIn,
    UserDto user
) {
}
