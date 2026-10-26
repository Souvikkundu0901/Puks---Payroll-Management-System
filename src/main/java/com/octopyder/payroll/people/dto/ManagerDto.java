package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.UserStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ManagerDto(
    UUID id,
    String code,
    String name,
    String email,
    String phone,
    UUID departmentId,
    String departmentName,
    String designation,
    BigDecimal experienceYears,
    long teamSize,
    UserStatus status
) {
}
