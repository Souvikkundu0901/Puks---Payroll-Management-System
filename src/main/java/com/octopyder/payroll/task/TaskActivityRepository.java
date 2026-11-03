package com.octopyder.payroll.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskActivityRepository extends JpaRepository<TaskActivity, UUID> {
    List<TaskActivity> findByTaskIdOrderByAtAsc(UUID taskId);
    boolean existsByTaskIdAndTypeAndToValue(UUID taskId, TaskActivityType type, String toValue);
}
