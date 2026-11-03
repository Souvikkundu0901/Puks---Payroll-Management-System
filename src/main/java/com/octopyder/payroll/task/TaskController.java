package com.octopyder.payroll.task;

import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.task.dto.CreateCommentRequest;
import com.octopyder.payroll.task.dto.CreateTaskRequest;
import com.octopyder.payroll.task.dto.TaskActivityDto;
import com.octopyder.payroll.task.dto.TaskAttachmentDto;
import com.octopyder.payroll.task.dto.TaskCommentDto;
import com.octopyder.payroll.task.dto.TaskDto;
import com.octopyder.payroll.task.dto.TaskProgressRequest;
import com.octopyder.payroll.task.dto.TaskRejectRequest;
import com.octopyder.payroll.task.dto.TaskSummaryDto;
import com.octopyder.payroll.task.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.octopyder.payroll.storage.StorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final StorageService storageService;

    public TaskController(TaskService taskService, StorageService storageService) {
        this.taskService = taskService;
        this.storageService = storageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TaskDto createTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody CreateTaskRequest request
    ) {
        return taskService.createTask(currentUser, request);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<TaskDto> listTasks(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(required = false) TaskStatus status,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) UUID assigneeId,
        @RequestParam(required = false) UUID managerId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) Boolean dueToday,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort
    ) {
        return taskService.listTasks(currentUser, status, priority, assigneeId, managerId, departmentId, dueToday, overdue, q, page, size, sort);
    }

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public TaskSummaryDto getSummary(@AuthenticationPrincipal CurrentUser currentUser) {
        return taskService.getSummary(currentUser);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TaskDto getTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.getTask(currentUser, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TaskDto updateTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody UpdateTaskRequest request
    ) {
        return taskService.updateTask(currentUser, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void cancelTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        taskService.cancelTask(currentUser, id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("isAuthenticated()")
    public TaskDto startTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.startTask(currentUser, id);
    }

    @PatchMapping("/{id}/progress")
    @PreAuthorize("isAuthenticated()")
    public TaskDto updateProgress(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody TaskProgressRequest request
    ) {
        return taskService.updateProgress(currentUser, id, request);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public TaskDto submitTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.submitTask(currentUser, id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    public TaskDto approveTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.approveTask(currentUser, id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("isAuthenticated()")
    public TaskDto rejectTask(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody TaskRejectRequest request
    ) {
        return taskService.rejectTask(currentUser, id, request);
    }

    @GetMapping("/{id}/activities")
    @PreAuthorize("isAuthenticated()")
    public List<TaskActivityDto> getActivities(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.getActivities(currentUser, id);
    }

    @GetMapping("/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    public List<TaskCommentDto> getComments(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.getComments(currentUser, id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public TaskCommentDto addComment(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @Valid @RequestBody CreateCommentRequest request
    ) {
        return taskService.addComment(currentUser, id, request);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public TaskAttachmentDto addAttachment(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @RequestParam("file") MultipartFile file
    ) {
        return taskService.addAttachment(currentUser, id, file);
    }

    @GetMapping("/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public List<TaskAttachmentDto> getAttachments(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id
    ) {
        return taskService.getAttachments(currentUser, id);
    }

    @GetMapping("/{id}/attachments/{attachmentId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadAttachment(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @PathVariable UUID attachmentId
    ) {
        TaskAttachment attachment = taskService.getAttachmentForDownload(currentUser, id, attachmentId);
        InputStream stream = storageService.load(attachment.getStorageKey());
        String safeName = attachment.getOriginalName().replaceAll("[\\r\\n\"\\\\]", "_");
        String lowerName = attachment.getOriginalName().toLowerCase();
        String contentType = lowerName.endsWith(".png") ? "image/png"
            : (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ? "image/jpeg"
            : (lowerName.endsWith(".pdf") ? "application/pdf" : "application/octet-stream"));

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(stream));
    }

    @GetMapping("/{id}/attachments/{attachmentId}/stream")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> streamAttachment(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable UUID id,
        @PathVariable UUID attachmentId
    ) {
        TaskAttachment attachment = taskService.getAttachmentForDownload(currentUser, id, attachmentId);
        InputStream stream = storageService.load(attachment.getStorageKey());
        String lowerName = attachment.getOriginalName().toLowerCase();
        String contentType = lowerName.endsWith(".png") ? "image/png"
            : (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ? "image/jpeg"
            : (lowerName.endsWith(".pdf") ? "application/pdf" : "application/octet-stream"));

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(stream));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> exportTasks(@AuthenticationPrincipal CurrentUser currentUser) {
        String csv = taskService.exportTasksCsv(currentUser);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tasks.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> importTasks(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam("file") MultipartFile file
    ) {
        int imported = taskService.importTasksCsv(currentUser, file);
        return Map.of("importedCount", imported, "message", "Tasks imported successfully");
    }
}
