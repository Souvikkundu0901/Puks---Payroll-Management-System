package com.octopyder.payroll.payroll.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PayrollPreviewRequest(
    @NotNull(message = "Employee ID is required")
    UUID employeeId,

    @NotNull(message = "Month is required")
    String month,

    Boolean treatPendingAsPresent
) {
    public boolean isTreatPendingAsPresent() {
        return Boolean.TRUE.equals(treatPendingAsPresent);
    }
}
