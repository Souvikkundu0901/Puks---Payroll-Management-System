package com.octopyder.payroll.dashboard.dto;

import java.math.BigDecimal;

public record EmployeeSalaryBriefDto(
    String month,
    BigDecimal gross,
    BigDecimal defaultRelease,
    BigDecimal retained,
    BigDecimal netPayable,
    String status
) {}
