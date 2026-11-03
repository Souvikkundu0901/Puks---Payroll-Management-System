package com.octopyder.payroll.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditLogDto(
    Long id,
    Instant at,
    UUID actorId,
    String actorCode,
    String actorName,
    String actorRole,
    String action,
    String entityType,
    String entityId,
    String before,
    String after,
    String ip,
    String userAgent,
    String requestId
) {}
