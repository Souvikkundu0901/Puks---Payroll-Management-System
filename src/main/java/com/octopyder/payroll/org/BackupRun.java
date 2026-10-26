package com.octopyder.payroll.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "backup_runs")
public class BackupRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "location")
    private String location;

    @Column(name = "triggered_by", length = 64, nullable = false)
    private String triggeredBy = "SYSTEM";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public BackupRun() {
    }

    public BackupRun(Instant startedAt, Instant finishedAt, Long sizeBytes, String status, String location, String triggeredBy) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.sizeBytes = sizeBytes;
        this.status = status;
        this.location = location;
        this.triggeredBy = triggeredBy != null ? triggeredBy : "SYSTEM";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
