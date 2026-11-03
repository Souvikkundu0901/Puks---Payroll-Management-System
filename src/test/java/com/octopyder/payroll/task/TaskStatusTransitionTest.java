package com.octopyder.payroll.task;

import com.octopyder.payroll.common.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStatusTransitionTest {

    private Task createSampleTask() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setCode("TSK001");
        task.setTitle("Sample Task");
        task.setDescription("Sample Description");
        task.setPriority(TaskPriority.HIGH);
        task.setAssigneeId(UUID.randomUUID());
        task.setManagerId(UUID.randomUUID());
        task.setCreatedBy(UUID.randomUUID());
        task.setDeadlineAt(Instant.parse("2026-08-15T18:00:00Z"));
        task.setStatus(TaskStatus.ASSIGNED);
        task.setProgress(0);
        return task;
    }

    @Test
    @DisplayName("Starting an ASSIGNED task moves it to IN_PROGRESS")
    void startTaskMovesToInProgress() {
        Task task = createSampleTask();
        task.start();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Setting progress > 0 on an ASSIGNED task moves it to IN_PROGRESS")
    void progressGreaterThanZeroMovesToInProgress() {
        Task task = createSampleTask();
        task.updateProgress(30);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getProgress()).isEqualTo(30);
    }

    @Test
    @DisplayName("Setting progress to 100 moves task to PENDING_APPROVAL and records submittedAt")
    void progressHundredMovesToPendingApproval() {
        Task task = createSampleTask();
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        task.updateProgress(100, now);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getSubmittedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Submit moves task from IN_PROGRESS or REJECTED to PENDING_APPROVAL")
    void submitMovesToPendingApproval() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.IN_PROGRESS);
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        task.submit(now);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getSubmittedAt()).isEqualTo(now);

        // Can also submit from REJECTED
        task.setStatus(TaskStatus.REJECTED);
        task.submit(now.plusSeconds(3600));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("Approving task in PENDING_APPROVAL sets APPROVED status and records approver")
    void approvePendingTask() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.PENDING_APPROVAL);
        task.setProgress(100);

        UUID managerId = task.getManagerId();
        Instant now = Instant.parse("2026-08-02T10:00:00Z");
        task.approve(managerId, now);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(task.getApprovedBy()).isEqualTo(managerId);
        assertThat(task.getApprovedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Rejecting task requires a note and sets status to REJECTED")
    void rejectPendingTaskRequiresNote() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.PENDING_APPROVAL);
        task.setProgress(100);

        assertThatThrownBy(() -> task.reject("   "))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Reject note is required");

        task.reject("Please fix validation logic on registration screen.");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(task.getRejectionNote()).isEqualTo("Please fix validation logic on registration screen.");
    }

    @Test
    @DisplayName("Cannot approve task unless status is PENDING_APPROVAL")
    void cannotApproveIfNotPendingApproval() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.IN_PROGRESS);

        assertThatThrownBy(() -> task.approve(task.getManagerId(), Instant.now()))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Cannot approve task in status IN_PROGRESS");
    }

    @Test
    @DisplayName("Cannot start task if it is APPROVED or CANCELLED or PENDING_APPROVAL")
    void cannotStartFinishedTask() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.APPROVED);

        assertThatThrownBy(task::start)
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Cannot start task in status APPROVED");

        task.setStatus(TaskStatus.PENDING_APPROVAL);
        assertThatThrownBy(task::start)
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Cannot start task in status PENDING_APPROVAL");
    }

    @Test
    @DisplayName("Cannot update progress if status is PENDING_APPROVAL, APPROVED or CANCELLED")
    void cannotUpdateProgressWhenPendingApproval() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.PENDING_APPROVAL);
        task.setProgress(100);

        assertThatThrownBy(() -> task.updateProgress(50))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Cannot update progress on task in status PENDING_APPROVAL");
    }

    @Test
    @DisplayName("Updating progress on REJECTED task moves it to IN_PROGRESS")
    void updatingProgressOnRejectedTaskMovesToInProgress() {
        Task task = createSampleTask();
        task.setStatus(TaskStatus.REJECTED);
        task.updateProgress(40);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getProgress()).isEqualTo(40);
    }

    @ParameterizedTest
    @ValueSource(ints = {-10, 15, 25, 99, 110})
    @DisplayName("Invalid progress values not in steps of 10 or outside 0..100 are rejected")
    void invalidProgressValuesRejected(int invalidProgress) {
        Task task = createSampleTask();
        assertThatThrownBy(() -> task.updateProgress(invalidProgress))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Progress must be between 0 and 100 in steps of 10");
    }

    @Test
    @DisplayName("Cancel sets status to CANCELLED and records cancelledAt")
    void cancelTask() {
        Task task = createSampleTask();
        Instant now = Instant.parse("2026-08-01T15:00:00Z");
        task.cancel(now);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCancelledAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Overdue is derived when deadline passed and task is not approved or cancelled")
    void overdueDerivedStatus() {
        Task task = createSampleTask();
        task.setDeadlineAt(Instant.parse("2026-08-01T18:00:00Z"));
        Instant beforeDeadline = Instant.parse("2026-08-01T12:00:00Z");
        Instant afterDeadline = Instant.parse("2026-08-01T19:00:00Z");

        assertThat(task.isOverdue(beforeDeadline)).isFalse();
        assertThat(task.isOverdue(afterDeadline)).isTrue();

        task.setStatus(TaskStatus.APPROVED);
        assertThat(task.isOverdue(afterDeadline)).isFalse();

        task.setStatus(TaskStatus.CANCELLED);
        assertThat(task.isOverdue(afterDeadline)).isFalse();
    }
}
