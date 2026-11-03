package com.octopyder.payroll.task.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskAttachmentDto(
    UUID id,
    UUID taskId,
    UUID uploadedBy,
    String uploadedByName,
    String storageKey,
    String originalName,
    Long sizeBytes,
    Instant at
) {}
