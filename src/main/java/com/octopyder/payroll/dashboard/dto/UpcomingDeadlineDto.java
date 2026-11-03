package com.octopyder.payroll.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record UpcomingDeadlineDto(
    UUID taskId,
    String code,
    String title,
    String assigneeName,
    Instant deadlineAt,
    String priority
) {}
