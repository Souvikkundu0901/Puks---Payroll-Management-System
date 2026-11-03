package com.octopyder.payroll.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record PayrollRunRequest(
    @NotBlank String month,
    UUID departmentId,
    Boolean treatPendingAsPresent,
    Boolean recalculate
) {}
