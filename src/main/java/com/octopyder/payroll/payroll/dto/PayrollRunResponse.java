package com.octopyder.payroll.payroll.dto;

import java.util.List;
import java.util.UUID;

public record PayrollRunResponse(
    UUID runId,
    String month,
    int totalProcessed,
    int generatedCount,
    int recalculatedCount,
    int skippedCount,
    int failedCount,
    List<String> skipped,
    List<String> failed
) {}
