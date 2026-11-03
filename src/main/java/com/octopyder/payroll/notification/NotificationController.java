package com.octopyder.payroll.notification;

import com.octopyder.payroll.common.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationDto> list(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return notificationService.listForUser(currentUser.id(), unreadOnly);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Long> unreadCount(@AuthenticationPrincipal CurrentUser currentUser) {
        return java.util.Map.of("count", notificationService.countUnread(currentUser.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void markRead(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        notificationService.markAsRead(id, currentUser.id());
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void markAllRead(@AuthenticationPrincipal CurrentUser currentUser) {
        notificationService.markAllAsRead(currentUser.id());
    }
}
