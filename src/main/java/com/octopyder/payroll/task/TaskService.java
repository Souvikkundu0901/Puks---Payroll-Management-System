package com.octopyder.payroll.task;

import com.octopyder.payroll.attendance.PayrollQueryService;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.notification.NotificationService;
import com.octopyder.payroll.notification.NotificationType;
import com.octopyder.payroll.people.Department;
import com.octopyder.payroll.people.DepartmentRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.storage.StorageService;
import com.octopyder.payroll.task.dto.CreateCommentRequest;
import com.octopyder.payroll.task.dto.CreateTaskRequest;
import com.octopyder.payroll.task.dto.TaskActivityDto;
import com.octopyder.payroll.task.dto.TaskAttachmentDto;
import com.octopyder.payroll.task.dto.TaskCommentDto;
import com.octopyder.payroll.task.dto.TaskDto;
import com.octopyder.payroll.task.dto.TaskMonthStats;
import com.octopyder.payroll.task.dto.TaskProgressRequest;
import com.octopyder.payroll.task.dto.TaskRejectRequest;
import com.octopyder.payroll.task.dto.TaskSummaryDto;
import com.octopyder.payroll.task.dto.UpdateTaskRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class TaskService {

    private static final long MAX_CSV_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final TaskRepository taskRepository;
    private final TaskActivityRepository taskActivityRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final TaskAttachmentRepository taskAttachmentRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final NotificationService notificationService;
    private final StorageService storageService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final PayrollQueryService payrollQueryService;

    public TaskService(
        TaskRepository taskRepository,
        TaskActivityRepository taskActivityRepository,
        TaskCommentRepository taskCommentRepository,
        TaskAttachmentRepository taskAttachmentRepository,
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        NotificationService notificationService,
        StorageService storageService,
        AuditService auditService,
        BusinessClock businessClock,
        PayrollQueryService payrollQueryService
    ) {
        this.taskRepository = taskRepository;
        this.taskActivityRepository = taskActivityRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.taskAttachmentRepository = taskAttachmentRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.notificationService = notificationService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.payrollQueryService = payrollQueryService;
    }

    public TaskDto createTask(CurrentUser currentUser, CreateTaskRequest request) {
        if (currentUser.isEmployee()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Employees cannot assign tasks");
        }

        User assignee = userRepository.findById(request.assigneeId())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "ASSIGNEE_NOT_FOUND", "Assignee not found"));

        UUID managerId;
        if (currentUser.isManager()) {
            if (!currentUser.id().equals(assignee.getManagerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers can only assign tasks to their team");
            }
            if (request.managerId() != null && !currentUser.id().equals(request.managerId())) {
                throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Managers cannot delegate task approval to another manager");
            }
            managerId = currentUser.id();
        } else {
            managerId = request.managerId();
            if (managerId == null) {
                managerId = assignee.getManagerId();
            }
            if (managerId == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "ASSIGNEE_HAS_NO_MANAGER", "Assignee does not have an assigned manager. Please assign a manager or specify managerId.");
            }
            if (!userRepository.existsById(managerId)) {
                throw new AppException(HttpStatus.NOT_FOUND, "MANAGER_NOT_FOUND", "Specified manager does not exist");
            }
        }

        Long seq = taskRepository.getNextCodeSequence();
        String code = String.format("TSK%03d", seq);

        Task task = new Task();
        task.setCode(code);
        task.setTitle(request.title().trim());
        task.setDescription(request.description() != null ? request.description().trim() : "");
        task.setPriority(request.priority());
        task.setAssigneeId(assignee.getId());
        task.setManagerId(managerId);
        task.setCreatedBy(currentUser.id());
        task.setDepartmentId(request.departmentId());
        task.setDeadlineAt(request.deadlineAt());
        task.setEstHours(request.estHours());
        task.setStatus(TaskStatus.ASSIGNED);
        task.setProgress(0);
        task.setCreatedAt(businessClock.instant());
        task.setUpdatedAt(businessClock.instant());

        Task saved = taskRepository.save(task);

        // Record CREATED activity
        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.CREATED, null, TaskStatus.ASSIGNED.name(), "Task created");

        // Send notification to assignee
        notificationService.createNotification(
            assignee.getId(),
            NotificationType.TASK_ASSIGNED,
            "New Task Assigned: " + saved.getCode(),
            "You have been assigned: " + saved.getTitle(),
            "/employee/tasks.html?id=" + saved.getId()
        );

        auditService.log("TASK_CREATE", "TASK", saved.getId().toString(), null, "Created " + saved.getCode());

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public TaskDto getTask(CurrentUser currentUser, UUID id) {
        Task task = getTaskEntity(id);
        assertCanViewTask(currentUser, task);
        return toDto(task);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDto> listTasks(
        CurrentUser currentUser,
        TaskStatus status,
        TaskPriority priority,
        UUID assigneeId,
        UUID managerId,
        UUID departmentId,
        Boolean dueToday,
        Boolean overdue,
        String q,
        int page,
        int size,
        String sort
    ) {
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        Specification<Task> spec = buildSpecification(currentUser, status, priority, assigneeId, managerId, departmentId, dueToday, overdue, q);

        Sort sortObj = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1]) ? Sort.Direction.ASC : Sort.Direction.DESC;
            sortObj = Sort.by(dir, parts[0]);
        }

        Pageable pageable = PageRequest.of(pageNo, pageSize, sortObj);
        Page<Task> pageResult = taskRepository.findAll(spec, pageable);

        Instant now = businessClock.instant();
        Map<UUID, User> userCache = new HashMap<>();
        Map<UUID, Department> deptCache = new HashMap<>();

        List<TaskDto> dtos = pageResult.getContent().stream()
            .map(t -> toDto(t, now, userCache, deptCache))
            .toList();

        return new PageResponse<>(dtos, pageNo, pageSize, pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    public TaskDto startTask(CurrentUser currentUser, UUID id) {
        Task task = getTaskEntity(id);
        if (!currentUser.isAdmin() && !task.getAssigneeId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only assignee can start the task");
        }

        TaskStatus oldStatus = task.getStatus();
        task.start();
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.STARTED, oldStatus.name(), saved.getStatus().name(), "Task started");
        auditService.log("TASK_START", "TASK", saved.getId().toString(), oldStatus.name(), saved.getStatus().name());

        return toDto(saved);
    }

    public TaskDto updateProgress(CurrentUser currentUser, UUID id, TaskProgressRequest request) {
        Task task = getTaskEntity(id);
        assertCanUpdateProgress(currentUser, task);

        int oldProgress = task.getProgress();
        TaskStatus oldStatus = task.getStatus();

        task.updateProgress(request.progress(), businessClock.instant());
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(
            saved.getId(),
            currentUser.id(),
            TaskActivityType.PROGRESS,
            String.valueOf(oldProgress),
            String.valueOf(saved.getProgress()),
            "Progress updated to " + saved.getProgress() + "%"
        );

        if (saved.getStatus() == TaskStatus.PENDING_APPROVAL && oldStatus != TaskStatus.PENDING_APPROVAL) {
            recordActivity(saved.getId(), currentUser.id(), TaskActivityType.SUBMITTED, oldStatus.name(), TaskStatus.PENDING_APPROVAL.name(), "Automatically submitted on 100% progress");
            notificationService.createNotification(
                saved.getManagerId(),
                NotificationType.TASK_SUBMITTED,
                "Task Submitted for Approval: " + saved.getCode(),
                "Task " + saved.getCode() + " is ready for review.",
                "/manager/assign-task.html?id=" + saved.getId()
            );
        }

        auditService.log("TASK_PROGRESS", "TASK", saved.getId().toString(), String.valueOf(oldProgress), String.valueOf(saved.getProgress()));

        return toDto(saved);
    }

    public TaskDto submitTask(CurrentUser currentUser, UUID id) {
        Task task = getTaskEntity(id);
        if (!currentUser.isAdmin() && !task.getAssigneeId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only assignee can submit the task");
        }

        TaskStatus oldStatus = task.getStatus();
        task.submit(businessClock.instant());
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.SUBMITTED, oldStatus.name(), TaskStatus.PENDING_APPROVAL.name(), "Task submitted for approval");

        notificationService.createNotification(
            saved.getManagerId(),
            NotificationType.TASK_SUBMITTED,
            "Task Submitted for Approval: " + saved.getCode(),
            "Task " + saved.getCode() + " submitted by assignee.",
            "/manager/assign-task.html?id=" + saved.getId()
        );

        auditService.log("TASK_SUBMIT", "TASK", saved.getId().toString(), oldStatus.name(), TaskStatus.PENDING_APPROVAL.name());

        return toDto(saved);
    }

    public TaskDto approveTask(CurrentUser currentUser, UUID id) {
        Task task = getTaskEntity(id);

        // BR-T4: Admin cannot approve tasks. Approval authority belongs strictly to task's manager.
        if (currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "TASK_NOT_YOUR_APPROVAL", "Admin cannot approve tasks. Manager approval required.");
        }

        if (!task.getManagerId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "TASK_NOT_YOUR_APPROVAL", "Only the assigned manager can approve this task");
        }

        if (task.getDeadlineAt() != null) {
            YearMonth deadlineMonth = YearMonth.from(task.getDeadlineAt().atZone(businessClock.getZone()));
            if (payrollQueryService != null && payrollQueryService.existsPayrollForUserAndMonth(task.getAssigneeId(), deadlineMonth)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_EXISTS", "Cannot modify task for a month with approved or paid payroll");
            }
        }

        TaskStatus oldStatus = task.getStatus();
        task.approve(currentUser.id(), businessClock.instant());
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.APPROVED, oldStatus.name(), TaskStatus.APPROVED.name(), "Task approved");

        notificationService.createNotification(
            saved.getAssigneeId(),
            NotificationType.TASK_APPROVED,
            "Task Approved: " + saved.getCode(),
            "Your task " + saved.getCode() + " has been approved.",
            "/employee/tasks.html?id=" + saved.getId()
        );

        auditService.log("TASK_APPROVE", "TASK", saved.getId().toString(), oldStatus.name(), TaskStatus.APPROVED.name());

        return toDto(saved);
    }

    public TaskDto rejectTask(CurrentUser currentUser, UUID id, TaskRejectRequest request) {
        Task task = getTaskEntity(id);

        if (currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "TASK_NOT_YOUR_APPROVAL", "Admin cannot reject tasks. Manager decision required.");
        }

        if (!task.getManagerId().equals(currentUser.id())) {
            throw new AppException(HttpStatus.FORBIDDEN, "TASK_NOT_YOUR_APPROVAL", "Only the assigned manager can reject this task");
        }

        if (task.getDeadlineAt() != null) {
            YearMonth deadlineMonth = YearMonth.from(task.getDeadlineAt().atZone(businessClock.getZone()));
            if (payrollQueryService != null && payrollQueryService.existsPayrollForUserAndMonth(task.getAssigneeId(), deadlineMonth)) {
                throw new AppException(HttpStatus.CONFLICT, "PAYROLL_EXISTS", "Cannot modify task for a month with approved or paid payroll");
            }
        }

        TaskStatus oldStatus = task.getStatus();
        task.reject(request.note().trim());
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.REJECTED, oldStatus.name(), TaskStatus.REJECTED.name(), request.note().trim());

        notificationService.createNotification(
            saved.getAssigneeId(),
            NotificationType.TASK_REJECTED,
            "Task Rejected: " + saved.getCode(),
            "Task " + saved.getCode() + " was rejected: " + request.note().trim(),
            "/employee/tasks.html?id=" + saved.getId()
        );

        auditService.log("TASK_REJECT", "TASK", saved.getId().toString(), oldStatus.name(), TaskStatus.REJECTED.name());

        return toDto(saved);
    }

    public TaskDto updateTask(CurrentUser currentUser, UUID id, UpdateTaskRequest request) {
        Task task = getTaskEntity(id);
        assertCanManageTask(currentUser, task);

        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_INVALID_TRANSITION", "Cannot edit a cancelled task");
        }

        if (request.version() != null && !request.version().equals(task.getVersion())) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "Stale task version: expected " + request.version() + " but found " + task.getVersion()
            );
        }

        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            task.setDescription(request.description().trim());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.assigneeId() != null) {
            userRepository.findById(request.assigneeId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "ASSIGNEE_NOT_FOUND", "Assignee not found"));
            task.setAssigneeId(request.assigneeId());
        }
        if (request.managerId() != null) {
            userRepository.findById(request.managerId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MANAGER_NOT_FOUND", "Manager not found"));
            task.setManagerId(request.managerId());
        }
        if (request.departmentId() != null) {
            task.setDepartmentId(request.departmentId());
        }
        if (request.deadlineAt() != null) {
            task.setDeadlineAt(request.deadlineAt());
        }
        if (request.estHours() != null) {
            task.setEstHours(request.estHours());
        }

        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.EDITED, null, null, "Task details updated");
        auditService.log("TASK_UPDATE", "TASK", saved.getId().toString(), null, "Updated " + saved.getCode());

        return toDto(saved);
    }

    public void cancelTask(CurrentUser currentUser, UUID id) {
        Task task = getTaskEntity(id);
        assertCanManageTask(currentUser, task);

        TaskStatus oldStatus = task.getStatus();
        task.cancel(businessClock.instant());
        task.setUpdatedAt(businessClock.instant());
        Task saved = taskRepository.save(task);

        recordActivity(saved.getId(), currentUser.id(), TaskActivityType.CANCELLED, oldStatus.name(), TaskStatus.CANCELLED.name(), "Task cancelled");
        auditService.log("TASK_CANCEL", "TASK", saved.getId().toString(), oldStatus.name(), TaskStatus.CANCELLED.name());
    }

    @Transactional(readOnly = true)
    public TaskSummaryDto getSummary(CurrentUser currentUser) {
        Instant now = businessClock.instant();
        List<Task> tasks;

        if (currentUser.isAdmin()) {
            tasks = taskRepository.findAll();
        } else if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            Set<UUID> memberIds = team.stream().map(User::getId).collect(Collectors.toSet());
            memberIds.add(currentUser.id());

            tasks = taskRepository.findAll().stream()
                .filter(t -> currentUser.id().equals(t.getManagerId()) || memberIds.contains(t.getAssigneeId()))
                .toList();
        } else {
            tasks = taskRepository.findAll().stream()
                .filter(t -> currentUser.id().equals(t.getAssigneeId()))
                .toList();
        }

        // Cancelled tasks are strictly excluded from summary counts per BR-T6
        List<Task> active = tasks.stream()
            .filter(t -> t.getStatus() != TaskStatus.CANCELLED)
            .toList();

        long total = active.size();
        long completed = active.stream().filter(t -> t.getStatus() == TaskStatus.APPROVED).count();
        long pending = active.stream().filter(t -> t.getStatus() != TaskStatus.APPROVED).count();
        long overdue = active.stream().filter(t -> t.isOverdue(now)).count();

        return new TaskSummaryDto(total, completed, pending, overdue);
    }

    @Transactional(readOnly = true)
    public List<TaskActivityDto> getActivities(CurrentUser currentUser, UUID taskId) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        Map<UUID, String> userNameCache = new HashMap<>();
        return taskActivityRepository.findByTaskIdOrderByAtAsc(taskId).stream()
            .map(a -> {
                String actorName = userNameCache.computeIfAbsent(a.getActorId(), this::resolveUserName);
                return new TaskActivityDto(
                    a.getId(),
                    a.getTaskId(),
                    a.getActorId(),
                    actorName,
                    a.getType(),
                    a.getFromValue(),
                    a.getToValue(),
                    a.getNote(),
                    a.getAt()
                );
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskCommentDto> getComments(CurrentUser currentUser, UUID taskId) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        Map<UUID, String> userNameCache = new HashMap<>();
        return taskCommentRepository.findByTaskIdOrderByAtAsc(taskId).stream()
            .map(c -> {
                String authorName = userNameCache.computeIfAbsent(c.getAuthorId(), this::resolveUserName);
                return new TaskCommentDto(
                    c.getId(),
                    c.getTaskId(),
                    c.getAuthorId(),
                    authorName,
                    c.getBody(),
                    c.getAt()
                );
            })
            .toList();
    }

    public TaskCommentDto addComment(CurrentUser currentUser, UUID taskId, CreateCommentRequest request) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        TaskComment comment = new TaskComment(
            taskId,
            currentUser.id(),
            request.body().trim(),
            businessClock.instant()
        );
        TaskComment saved = taskCommentRepository.save(comment);

        recordActivity(taskId, currentUser.id(), TaskActivityType.COMMENT_ADDED, null, null, "Comment added");

        String authorName = resolveUserName(currentUser.id());
        return new TaskCommentDto(saved.getId(), saved.getTaskId(), saved.getAuthorId(), authorName, saved.getBody(), saved.getAt());
    }

    public TaskAttachmentDto addAttachment(CurrentUser currentUser, UUID taskId, MultipartFile file) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        if (file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "File is empty");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new AppException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "File size exceeds 5 MB limit");
        }

        try {
            String storageKey = storageService.store(
                file.getOriginalFilename(),
                file.getInputStream(),
                file.getContentType(),
                file.getSize()
            );

            TaskAttachment attachment = new TaskAttachment(
                taskId,
                currentUser.id(),
                storageKey,
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "file",
                file.getSize(),
                businessClock.instant()
            );

            TaskAttachment saved = taskAttachmentRepository.save(attachment);

            recordActivity(taskId, currentUser.id(), TaskActivityType.ATTACHMENT_ADDED, null, saved.getOriginalName(), "Attachment uploaded");

            String uploaderName = resolveUserName(currentUser.id());
            return new TaskAttachmentDto(
                saved.getId(),
                saved.getTaskId(),
                saved.getUploadedBy(),
                uploaderName,
                saved.getStorageKey(),
                saved.getOriginalName(),
                saved.getSizeBytes(),
                saved.getAt()
            );
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Failed to store task attachment: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<TaskAttachmentDto> getAttachments(CurrentUser currentUser, UUID taskId) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        Map<UUID, String> userNameCache = new HashMap<>();
        return taskAttachmentRepository.findByTaskIdOrderByAtAsc(taskId).stream()
            .map(att -> new TaskAttachmentDto(
                att.getId(),
                att.getTaskId(),
                att.getUploadedBy(),
                userNameCache.computeIfAbsent(att.getUploadedBy(), this::resolveUserName),
                att.getStorageKey(),
                att.getOriginalName(),
                att.getSizeBytes(),
                att.getAt()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public TaskAttachment getAttachmentForDownload(CurrentUser currentUser, UUID taskId, UUID attachmentId) {
        Task task = getTaskEntity(taskId);
        assertCanViewTask(currentUser, task);

        TaskAttachment attachment = taskAttachmentRepository.findById(attachmentId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment not found"));

        if (!attachment.getTaskId().equals(taskId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment does not belong to this task");
        }
        return attachment;
    }

    @Transactional(readOnly = true)
    public boolean isTaskAttachmentKey(String storageKey) {
        return taskAttachmentRepository.existsByStorageKey(storageKey);
    }

    @Transactional(readOnly = true)
    public void validateAttachmentAccess(CurrentUser currentUser, String storageKey) {
        TaskAttachment att = taskAttachmentRepository.findByStorageKey(storageKey)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found"));
        Task task = getTaskEntity(att.getTaskId());
        assertCanViewTask(currentUser, task);
    }

    @Transactional(readOnly = true)
    public String exportTasksCsv(CurrentUser currentUser) {
        Instant now = businessClock.instant();
        List<Task> tasks;
        if (currentUser.isAdmin()) {
            tasks = taskRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else if (currentUser.isManager()) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            Set<UUID> memberIds = team.stream().map(User::getId).collect(Collectors.toSet());
            memberIds.add(currentUser.id());
            tasks = taskRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(t -> currentUser.id().equals(t.getManagerId()) || memberIds.contains(t.getAssigneeId()))
                .toList();
        } else {
            tasks = taskRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(t -> currentUser.id().equals(t.getAssigneeId()))
                .toList();
        }

        Map<UUID, User> userCache = new HashMap<>();
        Map<UUID, Department> deptCache = new HashMap<>();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Code,Title,Priority,Status,Progress,AssigneeCode,AssigneeName,ManagerName,Department,Deadline,EstHours,Overdue");

        for (Task t : tasks) {
            TaskDto dto = toDto(t, now, userCache, deptCache);
            pw.printf(
                "%s,%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s%n",
                escapeCsv(dto.code()),
                escapeCsv(dto.title()),
                escapeCsv(dto.priority().name()),
                escapeCsv(dto.status().name()),
                dto.progress(),
                escapeCsv(dto.assigneeCode()),
                escapeCsv(dto.assigneeName()),
                escapeCsv(dto.managerName()),
                escapeCsv(dto.departmentName()),
                dto.deadlineAt() != null ? dto.deadlineAt().toString() : "",
                dto.estHours() != null ? dto.estHours().toString() : "",
                dto.overdue() ? "YES" : "NO"
            );
        }

        auditService.log("TASKS_EXPORT", "TASK", null, null, "Exported " + tasks.size() + " tasks");
        return sw.toString();
    }

    public int importTasksCsv(CurrentUser currentUser, MultipartFile file) {
        if (!currentUser.isAdmin()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only admins can import tasks");
        }
        if (file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "File is empty");
        }
        if (file.getSize() > MAX_CSV_SIZE_BYTES) {
            throw new AppException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "CSV file exceeds 5 MB limit");
        }

        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 5) continue;

                String title = cols[0].trim();
                String desc = cols[1].trim();
                TaskPriority priority = TaskPriority.valueOf(cols[2].trim().toUpperCase());
                String assigneeEmailOrCode = cols[3].trim();
                String deadlineStr = cols[4].trim();

                User assignee = userRepository.findByEmailIgnoreCase(assigneeEmailOrCode)
                    .or(() -> userRepository.findByCode(assigneeEmailOrCode))
                    .orElse(null);

                if (assignee == null) continue;

                if (assignee.getManagerId() == null) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "ASSIGNEE_HAS_NO_MANAGER", "Row for " + assigneeEmailOrCode + " failed: employee has no assigned manager");
                }

                Instant deadline = Instant.parse(deadlineStr);
                BigDecimal estHours = cols.length > 5 && !cols[5].isBlank() ? new BigDecimal(cols[5].trim()) : null;

                CreateTaskRequest createReq = new CreateTaskRequest(
                    title,
                    desc,
                    priority,
                    assignee.getId(),
                    assignee.getManagerId(),
                    null,
                    deadline,
                    estHours
                );
                createTask(currentUser, createReq);
                count++;
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CSV_PARSE_ERROR", "Failed to parse CSV: " + e.getMessage());
        }

        auditService.log("TASKS_IMPORT", "TASK", null, null, "Imported " + count + " tasks");
        return count;
    }

    @Transactional(readOnly = true)
    public TaskMonthStats getTaskStatsForMonth(UUID userId, YearMonth month, LocalDate cutoffDate, ZoneId zoneId) {
        if (userId == null || month == null || cutoffDate == null || zoneId == null) {
            return new TaskMonthStats(0, 0);
        }
        Map<UUID, TaskMonthStats> map = getTaskStatsForMonthBatch(List.of(userId), month, cutoffDate, zoneId);
        return map.getOrDefault(userId, new TaskMonthStats(0, 0));
    }

    @Transactional(readOnly = true)
    public Map<UUID, TaskMonthStats> getTaskStatsForMonthBatch(java.util.Collection<UUID> userIds, YearMonth month, LocalDate cutoffDate, ZoneId zoneId) {
        if (userIds == null || userIds.isEmpty() || month == null || cutoffDate == null || zoneId == null) {
            return Collections.emptyMap();
        }
        Instant start = month.atDay(1).atStartOfDay(zoneId).toInstant();
        Instant end = cutoffDate.atTime(23, 59, 59, 999_999_999).atZone(zoneId).toInstant();

        List<Task> tasks = taskRepository.findByAssigneeIdInAndDeadlineAtBetween(userIds, start, end);

        Map<UUID, Integer> totalMap = new HashMap<>();
        Map<UUID, Integer> approvedMap = new HashMap<>();

        for (Task t : tasks) {
            if (t.getStatus() == TaskStatus.CANCELLED) {
                continue; // BR-P3: cancelled tasks excluded
            }
            UUID uid = t.getAssigneeId();
            totalMap.put(uid, totalMap.getOrDefault(uid, 0) + 1);
            if (t.getStatus() == TaskStatus.APPROVED) {
                approvedMap.put(uid, approvedMap.getOrDefault(uid, 0) + 1);
            }
        }

        Map<UUID, TaskMonthStats> result = new HashMap<>();
        for (UUID uid : userIds) {
            int total = totalMap.getOrDefault(uid, 0);
            int approved = approvedMap.getOrDefault(uid, 0);
            result.put(uid, new TaskMonthStats(total, approved));
        }
        return result;
    }

    // Helper methods

    private Task getTaskEntity(UUID id) {
        return taskRepository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found: " + id));
    }

    private void assertCanViewTask(CurrentUser currentUser, Task task) {
        if (currentUser.isAdmin()) return;
        if (task.getAssigneeId().equals(currentUser.id())) return;
        if (currentUser.isManager()) {
            if (task.getManagerId().equals(currentUser.id())) return;
            // Check if assignee is in manager's team
            boolean inTeam = userRepository.findById(task.getAssigneeId())
                .map(u -> currentUser.id().equals(u.getManagerId()))
                .orElse(false);
            if (inTeam) return;
        }
        throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Access denied to task " + task.getCode());
    }

    private void assertCanUpdateProgress(CurrentUser currentUser, Task task) {
        if (currentUser.isAdmin()) return;
        if (task.getAssigneeId().equals(currentUser.id())) return;
        if (currentUser.isManager() && task.getManagerId().equals(currentUser.id())) return;
        throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Only assignee or manager can update progress");
    }

    private void assertCanManageTask(CurrentUser currentUser, Task task) {
        if (currentUser.isAdmin()) return;
        if (currentUser.isManager() && task.getManagerId().equals(currentUser.id())) return;
        throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Only admin or task manager can modify this task");
    }

    private void recordActivity(UUID taskId, UUID actorId, TaskActivityType type, String fromValue, String toValue, String note) {
        TaskActivity activity = new TaskActivity(
            taskId,
            actorId,
            type,
            fromValue,
            toValue,
            note,
            businessClock.instant()
        );
        taskActivityRepository.save(activity);
    }

    private Specification<Task> buildSpecification(
        CurrentUser currentUser,
        TaskStatus status,
        TaskPriority priority,
        UUID assigneeId,
        UUID managerId,
        UUID departmentId,
        Boolean dueToday,
        Boolean overdue,
        String q
    ) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();

            // Scoping
            if (currentUser.isEmployee()) {
                predicates.add(cb.equal(root.get("assigneeId"), currentUser.id()));
            } else if (currentUser.isManager()) {
                List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
                List<UUID> teamIds = team.stream().map(User::getId).collect(Collectors.toList());
                teamIds.add(currentUser.id());

                var managerPredicate = cb.equal(root.get("managerId"), currentUser.id());
                var teamPredicate = root.get("assigneeId").in(teamIds);
                predicates.add(cb.or(managerPredicate, teamPredicate));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            if (assigneeId != null) {
                predicates.add(cb.equal(root.get("assigneeId"), assigneeId));
            }
            if (managerId != null) {
                predicates.add(cb.equal(root.get("managerId"), managerId));
            }
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("departmentId"), departmentId));
            }
            if (dueToday != null && dueToday) {
                LocalDate today = businessClock.today();
                ZonedDateTime startOfDay = today.atStartOfDay(businessClock.getZone());
                ZonedDateTime endOfDay = today.plusDays(1).atStartOfDay(businessClock.getZone());
                predicates.add(cb.between(root.get("deadlineAt"), startOfDay.toInstant(), endOfDay.toInstant()));
            }
            if (overdue != null && overdue) {
                predicates.add(cb.lessThan(root.get("deadlineAt"), businessClock.instant()));
                predicates.add(cb.notEqual(root.get("status"), TaskStatus.APPROVED));
                predicates.add(cb.notEqual(root.get("status"), TaskStatus.CANCELLED));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase() + "%";
                var codeLike = cb.like(cb.lower(root.get("code")), pattern);
                var titleLike = cb.like(cb.lower(root.get("title")), pattern);
                var descLike = cb.like(cb.lower(root.get("description")), pattern);
                predicates.add(cb.or(codeLike, titleLike, descLike));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private TaskDto toDto(Task task) {
        return toDto(task, businessClock.instant(), new HashMap<>(), new HashMap<>());
    }

    private TaskDto toDto(Task task, Instant now, Map<UUID, User> userCache, Map<UUID, Department> deptCache) {
        User assignee = task.getAssigneeId() != null
            ? userCache.computeIfAbsent(task.getAssigneeId(), id -> userRepository.findById(id).orElse(null))
            : null;

        User manager = task.getManagerId() != null
            ? userCache.computeIfAbsent(task.getManagerId(), id -> userRepository.findById(id).orElse(null))
            : null;

        Department dept = task.getDepartmentId() != null
            ? deptCache.computeIfAbsent(task.getDepartmentId(), id -> departmentRepository.findById(id).orElse(null))
            : null;

        String assigneeName = assignee != null ? (assignee.getEmail() != null ? assignee.getEmail() : assignee.getCode()) : null;
        String assigneeCode = assignee != null ? assignee.getCode() : null;
        String managerName = manager != null ? (manager.getEmail() != null ? manager.getEmail() : manager.getCode()) : null;
        String deptName = dept != null ? dept.getName() : null;

        return TaskDto.fromEntity(task, now, assigneeName, assigneeCode, managerName, deptName);
    }

    private String resolveUserName(UUID userId) {
        return userRepository.findById(userId)
            .map(u -> u.getEmail() != null ? u.getEmail() : u.getCode())
            .orElse("Unknown");
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        // Formula injection protection per BR-S/AGENTS.md
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@")) {
            val = "'" + val;
        }
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
