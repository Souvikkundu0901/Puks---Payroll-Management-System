package com.octopyder.payroll.people.dto;

import java.util.UUID;

public record CreateEmployeeResponse(
    UUID id,
    String code,
    String email,
    String temporaryPassword,
    boolean mustChangePassword,
    boolean emailSent
) {
    public CreateEmployeeResponse(UUID id, String code, String email, String temporaryPassword, boolean mustChangePassword) {
        this(id, code, email, temporaryPassword, mustChangePassword, false);
    }
}
