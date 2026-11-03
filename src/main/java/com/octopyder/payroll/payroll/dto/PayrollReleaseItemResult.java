package com.octopyder.payroll.payroll.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PayrollReleaseItemResult(
    UUID recordId,
    String status,
    BigDecimal amountPaid,
    String error
) {}
