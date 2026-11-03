package com.octopyder.payroll.dashboard.dto;

import java.time.Instant;

public record EmployeeDashboardDto(
    String employeeCode,
    String employeeName,
    String department,
    String designation,
    String todayAttendanceStatus,
    Instant checkInAt,
    Instant checkOutAt,
    long assignedTasksCount,
    long pendingTasksCount,
    long completedTasksCount,
    double attendancePercentage,
    String leaveUsage,
    double leaveBalanceAvailable,
    EmployeeSalaryBriefDto salarySummary
) {}
