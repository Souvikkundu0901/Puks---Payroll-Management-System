package com.octopyder.payroll.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AdminDashboardDto(
    long totalEmployees,
    long activeEmployees,
    String employeeGrowth,
    long totalManagers,
    long attendancePresent,
    long attendanceNotCheckedIn,
    long attendancePending,
    long attendanceAbsent,
    long pendingLeaves,
    long activeTasks,
    long completedTasks,
    long pendingTasks,
    String payrollStatus,
    BigDecimal payrollAmount,
    List<DailyAttendanceBarDto> weeklyAttendance,
    SalaryDonutDto salaryDonut,
    List<DepartmentTaskDto> departmentTasks,
    Map<String, Long> leaveStatsByType,
    List<SafeActivityDto> recentActivities
) {}
