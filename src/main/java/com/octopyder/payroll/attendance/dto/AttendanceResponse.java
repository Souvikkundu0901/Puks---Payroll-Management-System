package com.octopyder.payroll.attendance.dto;

import com.octopyder.payroll.attendance.AttendanceApprovalStatus;
import com.octopyder.payroll.attendance.AttendanceDayType;
import com.octopyder.payroll.attendance.AttendanceWorkMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceResponse(
    UUID id,
    UUID userId,
    String userCode,
    String userName,
    String departmentName,
    LocalDate workDate,
    Instant checkInAt,
    Instant checkOutAt,
    int workedMinutes,
    int overtimeMinutes,
    AttendanceWorkMode workMode,
    AttendanceDayType dayType,
    boolean isLate,
    AttendanceApprovalStatus approvalStatus,
    UUID approvedBy,
    String approvedByName,
    Instant approvedAt,
    String rejectionNote,
    boolean missedCheckout,
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal accuracyM,
    boolean lowAccuracy,
    String devicePlatform,
    String userAgent,
    String ipAddress,
    UUID officeLocationId,
    String officeLocationName,
    BigDecimal distanceM,
    Long version
) {}
