package com.octopyder.payroll.leave.dto;

import com.octopyder.payroll.leave.LeaveLedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveLedgerEntryDto(
    UUID id,
    UUID userId,
    int leaveYear,
    LeaveLedgerEntryType entryType,
    LocalDate effectiveOn,
    BigDecimal days,
    LocalDate periodMonth,
    UUID requestId,
    String note,
    Instant createdAt
) {}
