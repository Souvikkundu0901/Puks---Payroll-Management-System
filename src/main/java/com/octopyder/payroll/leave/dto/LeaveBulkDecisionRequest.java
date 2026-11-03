package com.octopyder.payroll.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record LeaveBulkDecisionRequest(
    @NotEmpty(message = "IDs list cannot be empty")
    @Size(max = 200, message = "Maximum 200 items per bulk decision")
    List<UUID> ids,

    @NotBlank(message = "Decision is required")
    String decision,

    @Size(max = 500, message = "Note cannot exceed 500 characters")
    String note,

    Boolean asUnpaid
) {}
