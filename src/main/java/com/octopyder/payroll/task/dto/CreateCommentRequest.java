package com.octopyder.payroll.task.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCommentRequest(
    @NotBlank(message = "Comment body is required")
    String body
) {}
