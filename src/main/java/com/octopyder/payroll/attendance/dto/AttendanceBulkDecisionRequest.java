package com.octopyder.payroll.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record AttendanceBulkDecisionRequest(
    @NotEmpty(message = "IDs cannot be empty")
    @Size(max = 200, message = "Maximum 200 items per bulk decision")
    List<UUID> ids,

    @NotBlank(message = "Decision is required")
    String decision,

    String note
) {}
