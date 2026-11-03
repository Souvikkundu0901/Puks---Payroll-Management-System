package com.octopyder.payroll.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 32)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "days", nullable = false, precision = 4, scale = 1)
    private BigDecimal days;

    @Column(name = "debited_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal debitedDays = BigDecimal.ZERO;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "attachment_key")
    private String attachmentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LeaveStatus status = LeaveStatus.PENDING;

    @Column(name = "insufficient_balance", nullable = false)
    private boolean insufficientBalance = false;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public LeaveRequest() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public LeaveType getLeaveType() { return leaveType; }
    public void setLeaveType(LeaveType leaveType) { this.leaveType = leaveType; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public BigDecimal getDays() { return days; }
    public void setDays(BigDecimal days) { this.days = days; }

    public BigDecimal getDebitedDays() { return debitedDays; }
    public void setDebitedDays(BigDecimal debitedDays) { this.debitedDays = debitedDays; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getAttachmentKey() { return attachmentKey; }
    public void setAttachmentKey(String attachmentKey) { this.attachmentKey = attachmentKey; }

    public LeaveStatus getStatus() { return status; }
    public void setStatus(LeaveStatus status) { this.status = status; }

    public boolean isInsufficientBalance() { return insufficientBalance; }
    public void setInsufficientBalance(boolean insufficientBalance) { this.insufficientBalance = insufficientBalance; }

    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID managerId) { this.managerId = managerId; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
