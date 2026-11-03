package com.octopyder.payroll.attendance.dto;

import java.util.List;
import java.util.UUID;

public record BulkDecisionResult(
    int totalCount,
    int successCount,
    int failureCount,
    List<BulkDecisionItemResult> items
) {}
