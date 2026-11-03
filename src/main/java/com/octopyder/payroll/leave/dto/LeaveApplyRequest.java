package com.octopyder.payroll.leave.dto;

import com.octopyder.payroll.leave.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record LeaveApplyRequest(
    @NotNull(message = "Leave type is required")
    LeaveType type,

    @NotNull(message = "Start date is required")
    LocalDate startDate,

    @NotNull(message = "End date is required")
    LocalDate endDate,

    @NotBlank(message = "Reason is required")
    @Size(max = 500, message = "Reason cannot exceed 500 characters")
    String reason
) {}
