package com.octopyder.payroll.people.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.UUID;

public record AddManagerRequest(
    @NotBlank String firstName,
    @NotBlank String lastName,
    @NotBlank @Email String email,
    String phone,
    UUID departmentId,
    @NotBlank String designation,
    BigDecimal experienceYears,
    BigDecimal baseSalary
) {
}
