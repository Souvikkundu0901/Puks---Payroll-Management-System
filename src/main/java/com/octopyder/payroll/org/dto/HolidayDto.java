package com.octopyder.payroll.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

public record HolidayDto(
    LocalDate holidayDate,
    String name,
    Instant createdAt
) {
    public record CreateRequest(
        @NotNull(message = "Holiday date is required")
        LocalDate holidayDate,

        @NotBlank(message = "Holiday name is required")
        String name
    ) {}
}
