package com.octopyder.payroll.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
    UUID id,
    UUID userId,
    NotificationType type,
    String title,
    String body,
    String link,
    Instant readAt,
    Instant createdAt
) {
    public static NotificationDto fromEntity(Notification entity) {
        return new NotificationDto(
            entity.getId(),
            entity.getUserId(),
            entity.getType(),
            entity.getTitle(),
            entity.getBody(),
            entity.getLink(),
            entity.getReadAt(),
            entity.getCreatedAt()
        );
    }
}
