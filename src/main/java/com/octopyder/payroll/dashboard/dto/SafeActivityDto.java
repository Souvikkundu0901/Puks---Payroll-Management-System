package com.octopyder.payroll.dashboard.dto;

import java.time.Instant;

public record SafeActivityDto(
    String title,
    String timeAgo,
    String type,
    Instant at
) {}
