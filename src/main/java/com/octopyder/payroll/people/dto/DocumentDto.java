package com.octopyder.payroll.people.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentDto(
    UUID id,
    UUID userId,
    String docType,
    String originalName,
    String contentType,
    long sizeBytes,
    String verificationStatus,
    UUID verifiedBy,
    Instant uploadedAt,
    String downloadUrl
) {
}
