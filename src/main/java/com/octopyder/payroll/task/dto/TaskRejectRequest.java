package com.octopyder.payroll.task.dto;

import jakarta.validation.constraints.NotBlank;

public record TaskRejectRequest(
    @NotBlank(message = "Rejection note is required")
    String note
) {}
