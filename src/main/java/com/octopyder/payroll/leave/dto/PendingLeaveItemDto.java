package com.octopyder.payroll.leave.dto;

import java.time.LocalDate;
import java.util.UUID;

public record PendingLeaveItemDto(
    UUID id,
    LocalDate startDate,
    LocalDate endDate,
    String leaveType
) {
}
