package com.octopyder.payroll.task.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TaskProgressRequest(
    @NotNull(message = "Progress is required")
    @Min(value = 0, message = "Progress must be between 0 and 100")
    @Max(value = 100, message = "Progress must be between 0 and 100")
    Integer progress
) {
    @AssertTrue(message = "Progress must be in steps of 10")
    public boolean isStepOfTen() {
        return progress == null || progress % 10 == 0;
    }
}
