package com.octopyder.payroll.org.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record BackupRunDto(
    UUID id,
    Instant startedAt,
    Instant finishedAt,
    Long sizeBytes,
    String status,
    String location,
    String triggeredBy,
    Instant createdAt
) {
    public record WebhookPayload(
        @NotNull(message = "Started at timestamp is required")
        Instant startedAt,

        Instant finishedAt,

        @Min(value = 0, message = "Size bytes cannot be negative")
        Long sizeBytes,

        @NotBlank(message = "Status is required")
        @Pattern(regexp = "^(RUNNING|SUCCESS|FAILED)$", message = "Status must be RUNNING, SUCCESS, or FAILED")
        String status,

        String location,

        String triggeredBy
    ) {}
}
