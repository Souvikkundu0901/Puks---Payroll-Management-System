package com.octopyder.payroll.dashboard.dto;

import java.time.LocalDate;

public record DailyAttendanceBarDto(
    String dayName,
    LocalDate date,
    long presentCount,
    long totalCount
) {}
