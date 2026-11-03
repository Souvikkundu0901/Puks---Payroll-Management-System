package com.octopyder.payroll.payroll.dto;

import java.math.BigDecimal;

public record PayrollLineDto(
    String kind,
    String label,
    BigDecimal amount
) {}
