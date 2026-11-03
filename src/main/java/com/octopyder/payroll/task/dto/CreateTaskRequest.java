package com.octopyder.payroll.task.dto;

import com.octopyder.payroll.task.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateTaskRequest(
    @NotBlank(message = "Title is required")
    String title,

    String description,

    @NotNull(message = "Priority is required")
    TaskPriority priority,

    @NotNull(message = "Assignee is required")
    @JsonAlias("employeeId")
    UUID assigneeId,

    UUID managerId,

    UUID departmentId,

    @NotNull(message = "Deadline is required")
    Instant deadlineAt,

    @PositiveOrZero(message = "Estimated hours must be positive or zero")
    BigDecimal estHours
) {}
