package com.octopyder.payroll.leave.dto;

import java.math.BigDecimal;

public record LeaveBalanceDto(
    BigDecimal annualAllowance,
    BigDecimal earned,
    BigDecimal used,
    BigDecimal available,
    BigDecimal carryForward
) {}
