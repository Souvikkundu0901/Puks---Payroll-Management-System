package com.octopyder.payroll.dashboard.dto;

import java.util.UUID;

public record TeamMemberPerformanceDto(
    UUID userId,
    String code,
    String name,
    String designation,
    double completionPct
) {}
