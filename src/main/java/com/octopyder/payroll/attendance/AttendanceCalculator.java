package com.octopyder.payroll.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

public final class AttendanceCalculator {

    private AttendanceCalculator() {}

    public static boolean isLate(LocalTime checkInTime, LocalTime officeStart, int graceMinutes) {
        LocalTime lateThreshold = officeStart.plusMinutes(graceMinutes);
        return checkInTime.isAfter(lateThreshold);
    }

    public static AttendanceDayType deriveDayType(int workedMinutes, int halfDayThresholdMinutes) {
        return workedMinutes < halfDayThresholdMinutes ? AttendanceDayType.HALF_DAY : AttendanceDayType.PRESENT;
    }

    public static int calculateOvertimeMinutes(int workedMinutes, int standardDayMinutes) {
        return Math.max(0, workedMinutes - standardDayMinutes);
    }

    /**
     * Monthly attendance % per PRD BR-A8:
     * (present days + 0.5 * half days + approved leave days) / working days up to cutoff * 100
     */
    public static BigDecimal calculateAttendancePercentage(int presentDays, int halfDays, int approvedLeaveDays, int workingDays) {
        if (workingDays <= 0) {
            return new BigDecimal("100.00");
        }
        double weightedDays = (double) presentDays + (0.5 * halfDays) + (double) approvedLeaveDays;
        double pct = (weightedDays / (double) workingDays) * 100.0;
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP);
    }
}
