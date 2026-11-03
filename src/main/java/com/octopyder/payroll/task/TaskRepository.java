package com.octopyder.payroll.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    Optional<Task> findByCode(String code);

    java.util.List<Task> findByDeadlineAtBetween(java.time.Instant start, java.time.Instant end);

    java.util.List<Task> findByAssigneeIdAndDeadlineAtBetween(UUID assigneeId, java.time.Instant start, java.time.Instant end);

    java.util.List<Task> findByAssigneeIdInAndDeadlineAtBetween(java.util.Collection<UUID> assigneeIds, java.time.Instant start, java.time.Instant end);

    java.util.List<Task> findByStatusInAndDeadlineAtBetween(java.util.Collection<TaskStatus> statuses, java.time.Instant start, java.time.Instant end);

    long countByStatusIn(java.util.Collection<TaskStatus> statuses);

    long countByStatus(TaskStatus status);

    long countByAssigneeIdInAndStatusIn(java.util.Collection<UUID> assigneeIds, java.util.Collection<TaskStatus> statuses);

    long countByAssigneeIdInAndStatus(java.util.Collection<UUID> assigneeIds, TaskStatus status);

    long countByAssigneeIdAndStatus(UUID assigneeId, TaskStatus status);

    long countByAssigneeIdAndStatusIn(UUID assigneeId, java.util.Collection<TaskStatus> statuses);

    java.util.List<Task> findByAssigneeIdInAndStatus(java.util.Collection<UUID> assigneeIds, TaskStatus status);

    @Query(value = "SELECT nextval('seq_tsk_code')", nativeQuery = true)
    Long getNextCodeSequence();
}
