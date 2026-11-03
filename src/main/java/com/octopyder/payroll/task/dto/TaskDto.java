package com.octopyder.payroll.task.dto;

import com.octopyder.payroll.task.Task;
import com.octopyder.payroll.task.TaskPriority;
import com.octopyder.payroll.task.TaskStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaskDto(
    UUID id,
    String code,
    String title,
    String description,
    TaskPriority priority,
    UUID assigneeId,
    String assigneeName,
    String assigneeCode,
    UUID managerId,
    String managerName,
    UUID createdBy,
    UUID departmentId,
    String departmentName,
    Instant deadlineAt,
    BigDecimal estHours,
    TaskStatus status,
    Integer progress,
    boolean overdue,
    Instant submittedAt,
    Instant approvedAt,
    UUID approvedBy,
    String rejectionNote,
    Instant cancelledAt,
    Long version,
    Instant createdAt,
    Instant updatedAt
) {
    public static TaskDto fromEntity(
        Task task,
        Instant now,
        String assigneeName,
        String assigneeCode,
        String managerName,
        String departmentName
    ) {
        return new TaskDto(
            task.getId(),
            task.getCode(),
            task.getTitle(),
            task.getDescription(),
            task.getPriority(),
            task.getAssigneeId(),
            assigneeName,
            assigneeCode,
            task.getManagerId(),
            managerName,
            task.getCreatedBy(),
            task.getDepartmentId(),
            departmentName,
            task.getDeadlineAt(),
            task.getEstHours(),
            task.getStatus(),
            task.getProgress(),
            task.isOverdue(now),
            task.getSubmittedAt(),
            task.getApprovedAt(),
            task.getApprovedBy(),
            task.getRejectionNote(),
            task.getCancelledAt(),
            task.getVersion(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
