package com.octopyder.payroll.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {
    List<TaskAttachment> findByTaskIdOrderByAtAsc(UUID taskId);
    Optional<TaskAttachment> findByStorageKey(String storageKey);
    boolean existsByStorageKey(String storageKey);
}
