package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.UserStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record OffboardRequest(
    LocalDate exitDate,
    @NotNull UserStatus status
) {
}
