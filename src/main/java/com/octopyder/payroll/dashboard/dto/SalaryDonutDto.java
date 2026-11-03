package com.octopyder.payroll.dashboard.dto;

import java.math.BigDecimal;

public record SalaryDonutDto(
    BigDecimal totalGross,
    BigDecimal released,
    BigDecimal pending,
    BigDecimal retained,
    double releasedPct,
    double pendingPct,
    double retainedPct
) {}
