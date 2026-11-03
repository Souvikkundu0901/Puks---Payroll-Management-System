package com.octopyder.payroll.notification;

import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final BusinessClock businessClock;

    public NotificationService(NotificationRepository notificationRepository, BusinessClock businessClock) {
        this.notificationRepository = notificationRepository;
        this.businessClock = businessClock;
    }

    public NotificationDto createNotification(UUID userId, NotificationType type, String title, String body, String link) {
        Notification notification = new Notification(userId, type, title, body, link, businessClock.instant());
        Notification saved = notificationRepository.save(notification);
        return NotificationDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> listForUser(UUID userId, boolean unreadOnly) {
        List<Notification> list = unreadOnly
            ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId)
            : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return list.stream().map(NotificationDto::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    public void markAsRead(UUID id, UUID userId) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(businessClock.instant());
            notificationRepository.save(notification);
        }
    }

    public void markAllAsRead(UUID userId) {
        notificationRepository.markAllAsRead(userId, businessClock.instant());
    }
}
