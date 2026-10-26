package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.UserStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateEmployeeRequest(
    String firstName,
    String lastName,
    String phone,
    String address,
    String gender,
    LocalDate dateOfBirth,
    String bloodGroup,
    String emergencyContact,
    String maritalStatus,
    UUID departmentId,
    String designation,
    String employmentType,
    LocalDate joiningDate,
    UUID managerId,
    BigDecimal baseSalary,
    BigDecimal experienceYears,
    String[] skills,
    UserStatus status
) {
}
