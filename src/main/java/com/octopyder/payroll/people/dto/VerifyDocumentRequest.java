package com.octopyder.payroll.people.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyDocumentRequest(
    @NotBlank String status
) {
}
