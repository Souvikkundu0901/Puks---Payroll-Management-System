package com.octopyder.payroll.payroll.dto;

import java.util.List;

public record PayrollReleaseAllResponse(
    int totalProcessed,
    int releasedCount,
    int tranche1Count,
    int failedCount,
    List<PayrollReleaseItemResult> results
) {}
