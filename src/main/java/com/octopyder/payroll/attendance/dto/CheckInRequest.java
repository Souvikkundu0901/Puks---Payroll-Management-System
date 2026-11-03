package com.octopyder.payroll.attendance.dto;

import java.math.BigDecimal;

public record CheckInRequest(
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal accuracyM,
    String devicePlatform
) {}
