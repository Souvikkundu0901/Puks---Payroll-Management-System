package com.octopyder.payroll.task;

import com.octopyder.payroll.common.AppException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority;

    @Column(name = "assignee_id", nullable = false)
    private UUID assigneeId;

    @Column(name = "manager_id", nullable = false)
    private UUID managerId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "est_hours", precision = 5, scale = 2)
    private BigDecimal estHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status = TaskStatus.ASSIGNED;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejection_note", columnDefinition = "TEXT")
    private String rejectionNote;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Task() {}

    public void start() {
        if (status != TaskStatus.ASSIGNED && status != TaskStatus.REJECTED) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot start task in status " + status);
        }
        this.status = TaskStatus.IN_PROGRESS;
    }

    public void updateProgress(int newProgress) {
        updateProgress(newProgress, Instant.now());
    }

    public void updateProgress(int newProgress, Instant now) {
        if (newProgress < 0 || newProgress > 100 || newProgress % 10 != 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Progress must be between 0 and 100 in steps of 10");
        }
        if (status == TaskStatus.PENDING_APPROVAL || status == TaskStatus.APPROVED || status == TaskStatus.CANCELLED) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot update progress on task in status " + status);
        }
        this.progress = newProgress;
        if (newProgress == 100) {
            this.status = TaskStatus.PENDING_APPROVAL;
            this.submittedAt = now;
        } else if ((status == TaskStatus.ASSIGNED || status == TaskStatus.REJECTED) && newProgress > 0) {
            this.status = TaskStatus.IN_PROGRESS;
        }
    }

    public void submit(Instant now) {
        if (status != TaskStatus.ASSIGNED && status != TaskStatus.IN_PROGRESS && status != TaskStatus.REJECTED) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot submit task in status " + status);
        }
        this.progress = 100;
        this.status = TaskStatus.PENDING_APPROVAL;
        this.submittedAt = now;
    }

    public void approve(UUID approverManagerId, Instant now) {
        if (status != TaskStatus.PENDING_APPROVAL) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot approve task in status " + status);
        }
        this.status = TaskStatus.APPROVED;
        this.approvedBy = approverManagerId;
        this.approvedAt = now;
    }

    public void reject(String note) {
        if (status != TaskStatus.PENDING_APPROVAL) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot reject task in status " + status);
        }
        if (note == null || note.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Reject note is required");
        }
        this.status = TaskStatus.REJECTED;
        this.rejectionNote = note.trim();
    }

    public void cancel(Instant now) {
        if (this.status == TaskStatus.CANCELLED) {
            return;
        }
        this.status = TaskStatus.CANCELLED;
        this.cancelledAt = now;
    }

    public boolean isOverdue(Instant now) {
        return deadlineAt != null && deadlineAt.isBefore(now) && status != TaskStatus.APPROVED && status != TaskStatus.CANCELLED;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TaskPriority getPriority() { return priority; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }

    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }

    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID managerId) { this.managerId = managerId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }

    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }

    public BigDecimal getEstHours() { return estHours; }
    public void setEstHours(BigDecimal estHours) { this.estHours = estHours; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public String getRejectionNote() { return rejectionNote; }
    public void setRejectionNote(String rejectionNote) { this.rejectionNote = rejectionNote; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
