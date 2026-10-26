package com.octopyder.payroll.people.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record AssignTeamRequest(
    @NotEmpty List<UUID> employeeIds
) {
}
