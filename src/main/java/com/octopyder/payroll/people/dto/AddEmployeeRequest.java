package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AddEmployeeRequest(
    @NotBlank String firstName,
    @NotBlank String lastName,
    @NotBlank @Email String email,
    String phone,
    String address,
    String gender,
    LocalDate dateOfBirth,
    String bloodGroup,
    String emergencyContact,
    String maritalStatus,
    UUID departmentId,
    @NotBlank String designation,
    String employmentType,
    LocalDate joiningDate,
    UUID managerId,
    BigDecimal baseSalary,
    BigDecimal experienceYears,
    String[] skills,
    @NotNull Role role,
    String password
) {
}
