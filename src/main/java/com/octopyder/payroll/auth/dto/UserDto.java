package com.octopyder.payroll.auth.dto;

import com.octopyder.payroll.people.Role;

import java.util.UUID;

public record UserDto(
    UUID id,
    String code,
    String name,
    String email,
    Role role,
    String department,
    String avatar,
    boolean mustChangePassword,
    String theme
) {
}
