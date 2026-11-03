package com.octopyder.payroll.leave.dto;

public record LeaveSummaryDto(
    int totalRequests,
    int pending,
    int approved,
    int rejected,
    int onLeaveToday
) {}
