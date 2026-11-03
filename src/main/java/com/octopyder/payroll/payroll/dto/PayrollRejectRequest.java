package com.octopyder.payroll.payroll.dto;

import jakarta.validation.constraints.NotBlank;

public record PayrollRejectRequest(
    @NotBlank String note
) {}
