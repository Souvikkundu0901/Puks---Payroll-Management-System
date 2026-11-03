package com.octopyder.payroll.task.dto;

import com.octopyder.payroll.task.TaskActivityType;

import java.time.Instant;
import java.util.UUID;

public record TaskActivityDto(
    UUID id,
    UUID taskId,
    UUID actorId,
    String actorName,
    TaskActivityType type,
    String fromValue,
    String toValue,
    String note,
    Instant at
) {}
