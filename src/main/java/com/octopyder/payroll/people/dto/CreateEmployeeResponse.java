package com.octopyder.payroll.people.dto;

import java.util.UUID;

public record CreateEmployeeResponse(
    UUID id,
    String code,
    String email,
    String temporaryPassword,
    boolean mustChangePassword
) {
}
