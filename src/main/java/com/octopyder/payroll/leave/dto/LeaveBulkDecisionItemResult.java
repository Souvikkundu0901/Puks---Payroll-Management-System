package com.octopyder.payroll.leave.dto;

import java.util.UUID;

public record LeaveBulkDecisionItemResult(
    UUID id,
    boolean success,
    String status,
    String message
) {}
