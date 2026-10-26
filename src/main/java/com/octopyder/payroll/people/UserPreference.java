package com.octopyder.payroll.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
public class UserPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String theme = "default";

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail = true;

    @Column(name = "notify_push", nullable = false)
    private boolean notifyPush = true;

    @Column(name = "notify_payroll", nullable = false)
    private boolean notifyPayroll = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserPreference() {
    }

    public UserPreference(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public boolean isNotifyEmail() {
        return notifyEmail;
    }

    public void setNotifyEmail(boolean notifyEmail) {
        this.notifyEmail = notifyEmail;
    }

    public boolean isNotifyPush() {
        return notifyPush;
    }

    public void setNotifyPush(boolean notifyPush) {
        this.notifyPush = notifyPush;
    }

    public boolean isNotifyPayroll() {
        return notifyPayroll;
    }

    public void setNotifyPayroll(boolean notifyPayroll) {
        this.notifyPayroll = notifyPayroll;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
