package com.octopyder.payroll.calendar.dto;

import java.time.LocalDate;
import java.util.UUID;

public record CalendarEventDto(
    UUID id,
    String title,
    LocalDate date,
    LocalDate endDate,
    String type,
    String employeeName,
    String employeeCode
) {}
