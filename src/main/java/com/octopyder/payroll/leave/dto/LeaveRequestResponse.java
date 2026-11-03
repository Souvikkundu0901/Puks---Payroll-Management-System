package com.octopyder.payroll.leave.dto;

import com.octopyder.payroll.leave.LeaveStatus;
import com.octopyder.payroll.leave.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequestResponse(
    UUID id,
    UUID userId,
    String userCode,
    String userName,
    String departmentName,
    LeaveType leaveType,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal days,
    BigDecimal debitedDays,
    String reason,
    String attachmentKey,
    LeaveStatus status,
    boolean insufficientBalance,
    UUID managerId,
    String managerName,
    UUID decidedBy,
    String decidedByName,
    Instant decidedAt,
    String decisionNote,
    Instant createdAt
) {}
