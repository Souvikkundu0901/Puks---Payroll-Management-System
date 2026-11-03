package com.octopyder.payroll.leave.dto;

import java.util.List;

public record LeaveBulkDecisionResult(
    int total,
    int successCount,
    int failureCount,
    List<LeaveBulkDecisionItemResult> results
) {}
