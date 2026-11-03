package com.octopyder.payroll.task.dto;

import com.octopyder.payroll.task.TaskPriority;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UpdateTaskRequest(
    String title,
    String description,
    TaskPriority priority,
    UUID assigneeId,
    UUID managerId,
    UUID departmentId,
    Instant deadlineAt,
    @PositiveOrZero(message = "Estimated hours must be positive or zero")
    BigDecimal estHours,
    Long version
) {}
