package com.octopyder.payroll.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingTaskApprovalDto(
    UUID taskId,
    String code,
    String title,
    String assigneeName,
    Instant deadlineAt,
    String priority,
    int progress
) {}
