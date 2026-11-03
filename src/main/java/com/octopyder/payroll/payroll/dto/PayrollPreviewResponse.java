package com.octopyder.payroll.payroll.dto;

import com.octopyder.payroll.leave.dto.PendingLeaveItemDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PayrollPreviewResponse(
    UUID employeeId,
    String employeeCode,
    String employeeName,
    String role,
    String month,
    LocalDate cutoffDate,
    BigDecimal gross,
    int tasksTotal,
    int tasksApproved,
    BigDecimal completionPct,
    int retentionDays,
    BigDecimal retentionAmount,
    int absentDays,
    BigDecimal absentDeduction,
    int unpaidLeaveDays,
    BigDecimal unpaidLeaveDeduction,
    BigDecimal totalUnpaidDeduction,
    BigDecimal tds,
    BigDecimal pf,
    BigDecimal pt,
    BigDecimal statutoryTotal,
    BigDecimal totalDeductions,
    BigDecimal net,
    BigDecimal defaultReleaseAmount,
    BigDecimal taskLinkedAmount,
    BigDecimal taskUnlockedAmount,
    BigDecimal releasedPct,
    BigDecimal tranche1,
    BigDecimal tranche2,
    boolean isNetNegativeClamped,
    Map<String, BigDecimal> earningsBreakup,
    List<LocalDate> pendingAttendance,
    List<PendingLeaveItemDto> pendingLeave
) {
}
