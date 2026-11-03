package com.octopyder.payroll.payroll.calculator;

import java.math.BigDecimal;
import java.util.Objects;

public record PayrollInputs(
    BigDecimal gross,
    int tasksTotal,
    int tasksApproved,
    int absentDays,
    int unpaidLeaveDays,
    BigDecimal tds,
    BigDecimal pf,
    BigDecimal pt,
    boolean retentionEligible,
    PayrollSettings settings
) {
    public PayrollInputs {
        if (gross == null || gross.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Gross salary must be strictly positive");
        }
        if (tasksTotal < 0) {
            throw new IllegalArgumentException("tasksTotal cannot be negative");
        }
        if (tasksApproved < 0) {
            throw new IllegalArgumentException("tasksApproved cannot be negative");
        }
        if (tasksApproved > tasksTotal) {
            throw new IllegalArgumentException("tasksApproved cannot exceed tasksTotal");
        }
        if (absentDays < 0) {
            throw new IllegalArgumentException("absentDays cannot be negative");
        }
        if (unpaidLeaveDays < 0) {
            throw new IllegalArgumentException("unpaidLeaveDays cannot be negative");
        }
        tds = Objects.requireNonNullElse(tds, BigDecimal.ZERO);
        pf = Objects.requireNonNullElse(pf, BigDecimal.ZERO);
        pt = Objects.requireNonNullElse(pt, BigDecimal.ZERO);
        if (tds.compareTo(BigDecimal.ZERO) < 0 || pf.compareTo(BigDecimal.ZERO) < 0 || pt.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Statutory deductions cannot be negative");
        }
        if (settings == null) {
            settings = PayrollSettings.defaultSettings();
        }
    }

    public BigDecimal statutoryTotal() {
        return tds.add(pf).add(pt);
    }

    // Convenience constructor
    public static PayrollInputs of(
        BigDecimal gross,
        int tasksTotal,
        int tasksApproved,
        int absentDays,
        int unpaidLeaveDays,
        BigDecimal tds,
        BigDecimal pf,
        BigDecimal pt
    ) {
        return new PayrollInputs(gross, tasksTotal, tasksApproved, absentDays, unpaidLeaveDays, tds, pf, pt, true, PayrollSettings.defaultSettings());
    }

    public static PayrollInputs of(
        BigDecimal gross,
        int tasksTotal,
        int tasksApproved,
        int absentDays,
        int unpaidLeaveDays,
        BigDecimal statutoryTotal
    ) {
        return new PayrollInputs(gross, tasksTotal, tasksApproved, absentDays, unpaidLeaveDays, statutoryTotal, BigDecimal.ZERO, BigDecimal.ZERO, true, PayrollSettings.defaultSettings());
    }
}
