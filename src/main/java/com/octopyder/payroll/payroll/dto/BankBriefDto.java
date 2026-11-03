package com.octopyder.payroll.payroll.dto;

public record BankBriefDto(
    String bankName,
    String accountMasked,
    String ifsc
) {}
