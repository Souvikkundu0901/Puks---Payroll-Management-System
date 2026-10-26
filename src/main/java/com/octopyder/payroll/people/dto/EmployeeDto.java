package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.UserStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EmployeeDto(
    UUID id,
    String code,
    String firstName,
    String lastName,
    String fullName,
    String email,
    String phone,
    String address,
    String gender,
    LocalDate dateOfBirth,
    String bloodGroup,
    String emergencyContact,
    String maritalStatus,
    UUID departmentId,
    String departmentName,
    String designation,
    String employmentType,
    LocalDate joiningDate,
    LocalDate exitDate,
    UUID managerId,
    String managerName,
    BigDecimal baseSalary,
    BigDecimal experienceYears,
    String[] skills,
    Role role,
    UserStatus status,
    boolean onLeaveToday,
    String photoUrl
) {
}
