package com.octopyder.payroll.payroll.dto;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSummaryResponse(
    String month,
    BigDecimal totalGross,
    BigDecimal totalNet,
    BigDecimal totalReleased,
    BigDecimal totalPending,
    BigDecimal totalRetained,
    int paidCount,
    int totalEmployees,
    List<DepartmentSummaryDto> departmentSummaries
) {}
