package com.octopyder.payroll.leave.dto;

import java.util.List;

public record LeaveLedgerDetailsDto(
    LeaveBalanceDto balance,
    List<LeaveLedgerEntryDto> entries
) {}
