package com.octopyder.payroll.payroll.dto;

import java.util.UUID;

public record EmployeeBriefDto(
    UUID id,
    String code,
    String name,
    String department,
    String role
) {}
