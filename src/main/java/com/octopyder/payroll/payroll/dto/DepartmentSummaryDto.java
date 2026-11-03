package com.octopyder.payroll.payroll.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DepartmentSummaryDto(
    UUID departmentId,
    String departmentName,
    BigDecimal totalGross,
    BigDecimal totalNet,
    int employeeCount
) {}
