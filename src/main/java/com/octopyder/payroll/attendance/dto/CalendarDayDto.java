package com.octopyder.payroll.attendance.dto;

import java.time.LocalDate;

public record CalendarDayDto(
    LocalDate date,
    String dayOfWeek,
    String status,
    String title,
    boolean isLate
) {}
