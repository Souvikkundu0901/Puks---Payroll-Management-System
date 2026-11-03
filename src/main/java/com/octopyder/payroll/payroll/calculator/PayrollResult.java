package com.octopyder.payroll.payroll.calculator;

import java.math.BigDecimal;

public record PayrollResult(
    BigDecimal gross,
    BigDecimal completionPct,
    int retentionDays,
    BigDecimal retentionAmount,
    int absentDays,
    BigDecimal absentDeduction,
    int unpaidLeaveDays,
    BigDecimal unpaidLeaveDeduction,
    BigDecimal totalUnpaidDeduction,
    BigDecimal tds,
    BigDecimal pf,
    BigDecimal pt,
    BigDecimal statutoryTotal,
    BigDecimal totalDeductions,
    BigDecimal net,
    BigDecimal defaultReleaseAmount,
    BigDecimal taskLinkedAmount,
    BigDecimal taskUnlockedAmount,
    BigDecimal releasedPct,
    BigDecimal tranche1,
    BigDecimal tranche2,
    boolean isNetNegativeClamped
) {
}
