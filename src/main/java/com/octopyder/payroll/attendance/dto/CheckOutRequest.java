package com.octopyder.payroll.attendance.dto;

import java.math.BigDecimal;

public record CheckOutRequest(
    BigDecimal latitude,
    BigDecimal longitude
) {}
