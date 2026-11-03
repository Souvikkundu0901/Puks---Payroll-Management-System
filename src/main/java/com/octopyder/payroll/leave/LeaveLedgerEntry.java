package com.octopyder.payroll.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_ledger_entries")
public class LeaveLedgerEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private LeaveLedgerEntryType entryType;

    @Column(name = "effective_on", nullable = false)
    private LocalDate effectiveOn;

    @Column(name = "days", nullable = false, precision = 4, scale = 1)
    private BigDecimal days;

    @Column(name = "period_month")
    private LocalDate periodMonth;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LeaveLedgerEntry() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public int getLeaveYear() { return leaveYear; }
    public void setLeaveYear(int leaveYear) { this.leaveYear = leaveYear; }

    public LeaveLedgerEntryType getEntryType() { return entryType; }
    public void setEntryType(LeaveLedgerEntryType entryType) { this.entryType = entryType; }

    public LocalDate getEffectiveOn() { return effectiveOn; }
    public void setEffectiveOn(LocalDate effectiveOn) { this.effectiveOn = effectiveOn; }

    public BigDecimal getDays() { return days; }
    public void setDays(BigDecimal days) { this.days = days; }

    public LocalDate getPeriodMonth() { return periodMonth; }
    public void setPeriodMonth(LocalDate periodMonth) { this.periodMonth = periodMonth; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
