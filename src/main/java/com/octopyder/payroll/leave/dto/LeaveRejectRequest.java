package com.octopyder.payroll.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeaveRejectRequest(
    @NotBlank(message = "Rejection note is required")
    @Size(max = 500, message = "Note cannot exceed 500 characters")
    String note
) {}
