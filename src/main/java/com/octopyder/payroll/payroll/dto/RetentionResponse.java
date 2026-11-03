package com.octopyder.payroll.payroll.dto;

import com.octopyder.payroll.payroll.RetentionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RetentionResponse(
    UUID id,
    UUID recordId,
    UUID userId,
    String employeeName,
    String employeeCode,
    int days,
    BigDecimal amount,
    RetentionStatus status,
    String note,
    UUID decidedBy,
    Instant decidedAt,
    UUID releasedInRecordId,
    Instant createdAt
) {}
