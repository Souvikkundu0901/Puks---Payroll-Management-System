package com.octopyder.payroll.people.dto;

import jakarta.validation.constraints.NotBlank;

public record BankAccountDto(
    String bankName,
    String accountMasked,
    String ifsc
) {
}
