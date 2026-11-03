package com.octopyder.payroll.leave;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaveCalculatorTest {

    private final Set<DayOfWeek> weekend = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private final MonthDay resetDate = MonthDay.of(4, 1); // 1 April

    @Test
    @DisplayName("Working days exclude weekends and holidays")
    void workingDaysExcludeWeekendsAndHolidays() {
        // 2026-07-06 (Monday) to 2026-07-12 (Sunday) = 7 calendar days
        // Mon-Fri = 5 days, Sat-Sun = 2 days
        // Suppose 2026-07-08 (Wednesday) is a public holiday
        LocalDate start = LocalDate.of(2026, 7, 6);
        LocalDate end = LocalDate.of(2026, 7, 12);
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 7, 8));

        int days = LeaveCalculator.calculateWorkingDays(start, end, weekend, holidays);
        assertThat(days).isEqualTo(4); // 5 working days - 1 holiday = 4
    }

    @Test
    @DisplayName("Zero working days when request falls entirely on weekend/holiday")
    void zeroWorkingDaysOnWeekend() {
        LocalDate sat = LocalDate.of(2026, 7, 11);
        LocalDate sun = LocalDate.of(2026, 7, 12);

        int days = LeaveCalculator.calculateWorkingDays(sat, sun, weekend, Set.of());
        assertThat(days).isEqualTo(0);
    }

    @Test
    @DisplayName("Leave year calculation for 1 April reset date")
    void leaveYearCalculation() {
        // On or after 1 April 2026 -> leave year 2026
        assertThat(LeaveCalculator.getLeaveYear(LocalDate.of(2026, 4, 1), resetDate)).isEqualTo(2026);
        assertThat(LeaveCalculator.getLeaveYear(LocalDate.of(2026, 7, 15), resetDate)).isEqualTo(2026);
        assertThat(LeaveCalculator.getLeaveYear(LocalDate.of(2027, 3, 31), resetDate)).isEqualTo(2026);

        // Before 1 April 2026 -> leave year 2025
        assertThat(LeaveCalculator.getLeaveYear(LocalDate.of(2026, 3, 31), resetDate)).isEqualTo(2025);
        assertThat(LeaveCalculator.getLeaveYear(LocalDate.of(2026, 1, 1), resetDate)).isEqualTo(2025);
    }

    @Test
    @DisplayName("Crosses leave year boundary detection")
    void crossesLeaveYearBoundaryDetection() {
        // 2026-03-30 to 2026-04-02 spans across 31 March and 1 April
        LocalDate start = LocalDate.of(2026, 3, 30);
        LocalDate end = LocalDate.of(2026, 4, 2);
        assertThat(LeaveCalculator.crossesLeaveYearBoundary(start, end, resetDate)).isTrue();

        // Within same leave year
        LocalDate startSame = LocalDate.of(2026, 4, 5);
        LocalDate endSame = LocalDate.of(2026, 4, 10);
        assertThat(LeaveCalculator.crossesLeaveYearBoundary(startSame, endSame, resetDate)).isFalse();
    }

    @Test
    @DisplayName("Reusable unpaid leave days in month calculation with working days and cutoff")
    void countUnpaidLeaveDaysInMonthWithCutoff() {
        // Request from 2026-07-20 to 2026-07-26 (Monday to Sunday)
        // Mon 20 to Fri 24 = 5 working days
        // Cutoff is 2026-07-22 (Wednesday)
        // Working days up to cutoff: Mon 20, Tue 21, Wed 22 = 3 days
        LocalDate start = LocalDate.of(2026, 7, 20);
        LocalDate end = LocalDate.of(2026, 7, 26);
        LocalDate cutoff = LocalDate.of(2026, 7, 22);

        int unpaidDays = LeaveCalculator.countUnpaidLeaveDaysInPeriod(
            start, end,
            YearMonth.of(2026, 7),
            cutoff,
            weekend,
            Set.of()
        );
        assertThat(unpaidDays).isEqualTo(3);
    }
}
