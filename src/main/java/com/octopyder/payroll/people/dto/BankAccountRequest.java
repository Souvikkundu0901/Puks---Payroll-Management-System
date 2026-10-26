package com.octopyder.payroll.people.dto;

import jakarta.validation.constraints.NotBlank;

public record BankAccountRequest(
    @NotBlank String bankName,
    @NotBlank String accountNumber,
    @NotBlank String ifsc
) {
}
