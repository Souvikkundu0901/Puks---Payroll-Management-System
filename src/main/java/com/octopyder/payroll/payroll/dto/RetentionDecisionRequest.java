package com.octopyder.payroll.payroll.dto;

import jakarta.validation.constraints.NotBlank;

public record RetentionDecisionRequest(
    @NotBlank String note
) {}
