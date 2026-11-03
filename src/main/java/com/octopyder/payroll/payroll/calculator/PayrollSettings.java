package com.octopyder.payroll.payroll.calculator;

import java.math.BigDecimal;

public record PayrollSettings(
    int dayRateDivisor,
    BigDecimal thresholdFullRelease,
    BigDecimal thresholdMidRelease,
    int retentionDaysMid,
    int retentionDaysLow,
    boolean noTasksCountsAs100
) {
    public static final int DEFAULT_DIVISOR = 30;
    public static final BigDecimal DEFAULT_THRESHOLD_FULL = new BigDecimal("70.00");
    public static final BigDecimal DEFAULT_THRESHOLD_MID = new BigDecimal("50.00");
    public static final int DEFAULT_RETENTION_MID = 2;
    public static final int DEFAULT_RETENTION_LOW = 7;

    public PayrollSettings {
        if (dayRateDivisor <= 0) {
            throw new IllegalArgumentException("dayRateDivisor must be positive");
        }
        if (thresholdFullRelease == null) {
            thresholdFullRelease = DEFAULT_THRESHOLD_FULL;
        }
        if (thresholdMidRelease == null) {
            thresholdMidRelease = DEFAULT_THRESHOLD_MID;
        }
    }

    public static PayrollSettings defaultSettings() {
        return new PayrollSettings(
            DEFAULT_DIVISOR,
            DEFAULT_THRESHOLD_FULL,
            DEFAULT_THRESHOLD_MID,
            DEFAULT_RETENTION_MID,
            DEFAULT_RETENTION_LOW,
            true
        );
    }
}
