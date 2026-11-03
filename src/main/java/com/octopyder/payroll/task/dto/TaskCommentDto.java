package com.octopyder.payroll.task.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskCommentDto(
    UUID id,
    UUID taskId,
    UUID authorId,
    String authorName,
    String body,
    Instant at
) {}
