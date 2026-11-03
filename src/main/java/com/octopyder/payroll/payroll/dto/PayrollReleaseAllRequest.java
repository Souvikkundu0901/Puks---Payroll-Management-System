package com.octopyder.payroll.payroll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollReleaseAllRequest(
    UUID runId,
    String month,
    @NotNull LocalDate paymentDate,
    @NotBlank @Size(max = 100) String paymentRef,
    @NotBlank @Size(max = 50) String paymentMethod
) {}
