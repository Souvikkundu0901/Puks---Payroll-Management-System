package com.octopyder.payroll.attendance.dto;

import java.util.UUID;

public record BulkDecisionItemResult(
    UUID id,
    boolean success,
    String status,
    String message
) {}
