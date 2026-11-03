package com.octopyder.payroll.dashboard.dto;

public record DepartmentTaskDto(
    String departmentName,
    long totalTasks,
    long completedTasks,
    double completionPct
) {}
