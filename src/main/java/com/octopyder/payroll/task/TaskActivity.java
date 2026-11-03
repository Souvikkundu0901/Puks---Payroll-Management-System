package com.octopyder.payroll.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_activities")
public class TaskActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskActivityType type;

    @Column(name = "from_value")
    private String fromValue;

    @Column(name = "to_value")
    private String toValue;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public TaskActivity() {}

    public TaskActivity(UUID taskId, UUID actorId, TaskActivityType type, String fromValue, String toValue, String note, Instant at) {
        this.taskId = taskId;
        this.actorId = actorId;
        this.type = type;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.note = note;
        this.at = at;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }

    public TaskActivityType getType() { return type; }
    public void setType(TaskActivityType type) { this.type = type; }

    public String getFromValue() { return fromValue; }
    public void setFromValue(String fromValue) { this.fromValue = fromValue; }

    public String getToValue() { return toValue; }
    public void setToValue(String toValue) { this.toValue = toValue; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
