package com.octopyder.payroll.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.Set;

public final class LeaveCalculator {

    private LeaveCalculator() {}

    public static int calculateWorkingDays(
        LocalDate startDate,
        LocalDate endDate,
        Set<DayOfWeek> weeklyOffDays,
        Set<LocalDate> holidays
    ) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return 0;
        }

        int count = 0;
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            if (weeklyOffDays != null && weeklyOffDays.contains(d.getDayOfWeek())) {
                continue;
            }
            if (holidays != null && holidays.contains(d)) {
                continue;
            }
            count++;
        }
        return count;
    }

    public static int getLeaveYear(LocalDate date, MonthDay resetDate) {
        if (date == null) {
            return 0;
        }
        MonthDay md = resetDate != null ? resetDate : MonthDay.of(4, 1);
        int year = date.getYear();
        LocalDate resetThisYear = md.atYear(year);
        if (date.isBefore(resetThisYear)) {
            return year - 1;
        }
        return year;
    }

    public static boolean crossesLeaveYearBoundary(LocalDate startDate, LocalDate endDate, MonthDay resetDate) {
        if (startDate == null || endDate == null) {
            return false;
        }
        return getLeaveYear(startDate, resetDate) != getLeaveYear(endDate, resetDate);
    }

    public static int countUnpaidLeaveDaysInPeriod(
        LocalDate requestStart,
        LocalDate requestEnd,
        YearMonth month,
        LocalDate cutoffDate,
        Set<DayOfWeek> weeklyOffDays,
        Set<LocalDate> holidays
    ) {
        if (requestStart == null || requestEnd == null || month == null) {
            return 0;
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        LocalDate effectiveStart = requestStart.isBefore(monthStart) ? monthStart : requestStart;
        LocalDate effectiveEnd = requestEnd.isAfter(monthEnd) ? monthEnd : requestEnd;

        if (cutoffDate != null && effectiveEnd.isAfter(cutoffDate)) {
            effectiveEnd = cutoffDate;
        }

        if (effectiveStart.isAfter(effectiveEnd)) {
            return 0;
        }

        return calculateWorkingDays(effectiveStart, effectiveEnd, weeklyOffDays, holidays);
    }
}
