package com.octopyder.payroll.attendance.dto;

import java.time.LocalDate;

public record AttendanceSummaryDto(
    LocalDate date,
    int totalEmployees,
    int presentCount,
    int lateCount,
    int remoteCount,
    int pendingApprovalCount,
    int absentCount,
    int onLeaveCount
) {}
