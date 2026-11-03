package com.octopyder.payroll.attendance.dto;

import java.math.BigDecimal;
import java.util.List;

public record MonthAttendanceSummaryDto(
    int totalWorkingDays,
    int presentDays,
    int absentDays,
    int lateDays,
    int halfDays,
    int approvedLeaveDays,
    BigDecimal attendancePercentage,
    List<CalendarDayDto> calendarDays,
    List<AttendanceResponse> records
) {}
