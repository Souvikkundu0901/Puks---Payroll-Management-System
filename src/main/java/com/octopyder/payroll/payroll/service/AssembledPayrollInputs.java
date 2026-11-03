package com.octopyder.payroll.payroll.service;

import com.octopyder.payroll.leave.dto.PendingLeaveItemDto;
import com.octopyder.payroll.payroll.calculator.PayrollInputs;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AssembledPayrollInputs(
    PayrollInputs inputs,
    User user,
    EmployeeProfile profile,
    LocalDate cutoffDate,
    int workingDays,
    BigDecimal presentDays,
    List<LocalDate> pendingAttendance,
    List<PendingLeaveItemDto> pendingLeave
) {
}
