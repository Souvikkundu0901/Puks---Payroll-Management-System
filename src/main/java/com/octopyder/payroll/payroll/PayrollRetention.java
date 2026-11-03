package com.octopyder.payroll.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payroll_retentions")
public class PayrollRetention {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int days;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RetentionStatus status = RetentionStatus.HELD;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "note")
    private String note;

    @Column(name = "released_in_record_id")
    private UUID releasedInRecordId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public PayrollRetention() {}

    public PayrollRetention(UUID recordId, UUID userId, int days, BigDecimal amount, RetentionStatus status) {
        this.recordId = recordId;
        this.userId = userId;
        this.days = days;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.status = status != null ? status : RetentionStatus.HELD;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID recordId) { this.recordId = recordId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public RetentionStatus getStatus() { return status; }
    public void setStatus(RetentionStatus status) { this.status = status; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public UUID getReleasedInRecordId() { return releasedInRecordId; }
    public void setReleasedInRecordId(UUID releasedInRecordId) { this.releasedInRecordId = releasedInRecordId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
