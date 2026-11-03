package com.octopyder.payroll.task.dto;

public record TaskSummaryDto(
    long total,
    long completed,
    long pending,
    long overdue
) {}
