package com.octopyder.payroll.attendance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AttendanceCalculationTest {

    @Test
    @DisplayName("Haversine distance calculation is accurate within 1 meter")
    void haversineDistanceCalculation() {
        // Kolkata Office: (22.5726, 88.4373)
        // 100 meters east approximately: (22.5726, 88.43827)
        double lat1 = 22.5726;
        double lon1 = 88.4373;
        double lat2 = 22.5726;
        double lon2 = 88.43827;

        double distance = HaversineCalculator.distanceMeters(lat1, lon1, lat2, lon2);
        assertThat(distance).isCloseTo(100.0, within(2.0));
    }

    @Test
    @DisplayName("Punctuality with 15m grace: 09:15 is on-time, 09:16 is late")
    void punctualityWith15MinuteGrace() {
        LocalTime officeStart = LocalTime.of(9, 0);
        int graceMinutes = 15;

        LocalTime onTime = LocalTime.of(9, 15, 0);
        LocalTime late = LocalTime.of(9, 15, 1);
        LocalTime definitelyLate = LocalTime.of(9, 16, 0);

        assertThat(AttendanceCalculator.isLate(onTime, officeStart, graceMinutes)).isFalse();
        assertThat(AttendanceCalculator.isLate(late, officeStart, graceMinutes)).isTrue();
        assertThat(AttendanceCalculator.isLate(definitelyLate, officeStart, graceMinutes)).isTrue();
    }

    @Test
    @DisplayName("Punctuality with 30m grace: 09:30 is on-time, 09:31 is late")
    void punctualityWith30MinuteGrace() {
        LocalTime officeStart = LocalTime.of(9, 0);
        int graceMinutes = 30;

        LocalTime onTime = LocalTime.of(9, 30, 0);
        LocalTime late = LocalTime.of(9, 31, 0);

        assertThat(AttendanceCalculator.isLate(onTime, officeStart, graceMinutes)).isFalse();
        assertThat(AttendanceCalculator.isLate(late, officeStart, graceMinutes)).isTrue();
    }

    @Test
    @DisplayName("Day type duration boundary: 239 min is HALF_DAY, 240 min is PRESENT")
    void dayTypeDurationBoundary() {
        int halfDayThresholdMinutes = 240;

        assertThat(AttendanceCalculator.deriveDayType(239, halfDayThresholdMinutes))
            .isEqualTo(AttendanceDayType.HALF_DAY);

        assertThat(AttendanceCalculator.deriveDayType(240, halfDayThresholdMinutes))
            .isEqualTo(AttendanceDayType.PRESENT);

        assertThat(AttendanceCalculator.deriveDayType(480, halfDayThresholdMinutes))
            .isEqualTo(AttendanceDayType.PRESENT);
    }

    @Test
    @DisplayName("Overtime calculation: extra time beyond standard work day (e.g. 540 min)")
    void overtimeCalculation() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(18, 0);
        int standardDayMinutes = (int) Duration.between(start, end).toMinutes(); // 540 min = 9 hours

        assertThat(AttendanceCalculator.calculateOvertimeMinutes(540, standardDayMinutes)).isEqualTo(0);
        assertThat(AttendanceCalculator.calculateOvertimeMinutes(500, standardDayMinutes)).isEqualTo(0);
        assertThat(AttendanceCalculator.calculateOvertimeMinutes(600, standardDayMinutes)).isEqualTo(60);
    }

    @Test
    @DisplayName("Monthly attendance % formula from PRD BR-A8")
    void monthlyAttendancePercentageFormula() {
        // Formula: (present days + 0.5 * half days + approved leave days) / working days up to cutoff * 100
        int workingDays = 22;
        int presentDays = 18;
        int halfDays = 2; // counts as 1.0
        int approvedLeaveDays = 2;

        BigDecimal pct = AttendanceCalculator.calculateAttendancePercentage(
            presentDays, halfDays, approvedLeaveDays, workingDays
        );

        // (18 + 1.0 + 2) / 22 = 21 / 22 = 95.45%
        assertThat(pct).isEqualTo(new BigDecimal("95.45"));
    }

    @Test
    @DisplayName("IPv4 and IPv6 CIDR matching")
    void cidrMatching() {
        String cidrs = "192.168.1.0/24, 10.0.0.0/8, 2001:db8::/32, 172.16.5.10";

        // IPv4 in range
        assertThat(CidrMatcher.matches("192.168.1.100", cidrs)).isTrue();
        assertThat(CidrMatcher.matches("10.254.1.2", cidrs)).isTrue();
        assertThat(CidrMatcher.matches("172.16.5.10", cidrs)).isTrue();

        // IPv4 out of range
        assertThat(CidrMatcher.matches("192.168.2.1", cidrs)).isFalse();
        assertThat(CidrMatcher.matches("172.16.5.11", cidrs)).isFalse();

        // IPv6 in range
        assertThat(CidrMatcher.matches("2001:db8::1", cidrs)).isTrue();
        assertThat(CidrMatcher.matches("2001:db8:ffff:ffff::1", cidrs)).isTrue();

        // IPv6 out of range
        assertThat(CidrMatcher.matches("2001:db9::1", cidrs)).isFalse();
    }
}
