package com.octopyder.payroll.common;

import com.octopyder.payroll.people.Role;

import java.util.UUID;

public record CurrentUser(
    UUID id,
    String code,
    String email,
    Role role,
    long tokenVersion
) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }

    public boolean isEmployee() {
        return role == Role.EMPLOYEE;
    }
}
