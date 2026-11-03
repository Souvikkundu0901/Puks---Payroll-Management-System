package com.octopyder.payroll.leave.dto;

import jakarta.validation.constraints.Size;

public record LeaveDecisionRequest(
    Boolean asUnpaid,
    @Size(max = 500, message = "Note cannot exceed 500 characters")
    String note
) {}
