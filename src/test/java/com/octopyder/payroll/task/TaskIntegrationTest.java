package com.octopyder.payroll.task;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TaskIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TaskRepository taskRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM task_attachments");
        jdbcTemplate.execute("DELETE FROM task_comments");
        jdbcTemplate.execute("DELETE FROM task_activities");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM tasks");
        jdbcTemplate.execute("ALTER SEQUENCE seq_tsk_code RESTART WITH 1");
    }

    private User createTestUser(String code, String email, Role role, UUID managerId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, code, email, password_hash, role, status, manager_id, token_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1)",
            userId, code, email, "$2a$12$eAccYoNOz2F5DO7mxgWBn.tY81Xj3dZ2aK2xWqG95yZc7W1uXUzyq", role.name(), managerId
        );
        User user = new User();
        user.setId(userId);
        user.setCode(code);
        user.setEmail(email);
        user.setRole(role);
        user.setManagerId(managerId);
        user.setTokenVersion(1);
        return user;
    }

    private String tokenFor(User user) {
        return tokenService.generateAccessToken(user);
    }

    @Test
    @DisplayName("Full Task Lifecycle: assign -> start -> progress -> submit (auto on 100) -> approve")
    void fullTaskLifecycle() throws Exception {
        User manager = createTestUser("MGR_TASK1", "mgr.task1@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_TASK1", "emp.task1@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        // 1. Manager assigns task
        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Build Auth Frontend",
                        "description": "Implement authentication screens with validations",
                        "priority": "HIGH",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z",
                        "estHours": 20
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("TSK001"))
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.progress").value(0))
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Verify notification sent to employee on assign
        mockMvc.perform(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].type").value("TASK_ASSIGNED"));

        // 2. Employee starts task
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 3. Employee updates progress to 50%
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 50
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.progress").value(50))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 4. Employee updates progress to 100% (automatically moves to PENDING_APPROVAL)
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 100
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.progress").value(100))
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Verify manager received TASK_SUBMITTED notification
        mockMvc.perform(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("TASK_SUBMITTED"));

        // 5. Manager approves task
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value(manager.getId().toString()));

        // Verify employee received TASK_APPROVED notification
        mockMvc.perform(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("TASK_APPROVED"));

        // 6. Verify timeline activities recorded (CREATED, STARTED, PROGRESS, PROGRESS, SUBMITTED, APPROVED)
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/activities")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(6)));
    }

    @Test
    @DisplayName("Admin and other managers cannot approve task (403 Forbidden)")
    void adminAndOtherManagersCannotApprove() throws Exception {
        User admin = createTestUser("ADM_TASK", "adm.task@octopyder.com", Role.ADMIN, null);
        User manager1 = createTestUser("MGR_OWN", "mgr.own@octopyder.com", Role.MANAGER, null);
        User manager2 = createTestUser("MGR_OTHER", "mgr.other@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_T2", "emp.t2@octopyder.com", Role.EMPLOYEE, manager1.getId());

        String adminToken = tokenFor(admin);
        String mgr1Token = tokenFor(manager1);
        String mgr2Token = tokenFor(manager2);
        String empToken = tokenFor(employee);

        // Manager 1 creates task
        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgr1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Backend Optimization",
                        "description": "Refactor database query indexes",
                        "priority": "MEDIUM",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Employee submits task
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/submit")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Admin tries to approve -> MUST FAIL with 403 (BR-T4: Admin cannot approve tasks)
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("TASK_NOT_YOUR_APPROVAL"));

        // Manager 2 (not task manager) tries to approve -> MUST FAIL with 403 (or 404)
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                .header("Authorization", "Bearer " + mgr2Token))
            .andExpect(status().isForbidden());

        // Manager 1 (own manager) approves -> SUCCEEDS
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                .header("Authorization", "Bearer " + mgr1Token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("Reject requires a note and allows employee to resubmit")
    void rejectRequiresNoteAndCanResubmit() throws Exception {
        User manager = createTestUser("MGR_REJ", "mgr.rej@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_REJ", "emp.rej@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "QA Test Execution",
                        "description": "Execute smoke tests",
                        "priority": "LOW",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Employee submits
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/submit")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk());

        // Reject without note -> MUST FAIL with 400 Bad Request
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reject")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "note": "   "
                    }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Reject with valid note -> SUCCEEDS
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reject")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "note": "Edge cases for concurrent logins are missing."
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionNote").value("Edge cases for concurrent logins are missing."));

        // Employee resubmits task -> SUCCEEDS
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/submit")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("Progress values not in steps of 10 are rejected with 400")
    void progressValidationRejectsNonStepOfTen() throws Exception {
        User manager = createTestUser("MGR_PROG", "mgr.prog@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_PROG", "emp.prog@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Progress Test Task",
                        "description": "Testing progress increments",
                        "priority": "LOW",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Progress 15 -> Rejected with 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 15
                    }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Progress 20 -> Accepted
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 20
                    }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.progress").value(20));
    }

    @Test
    @DisplayName("Soft cancel marks task CANCELLED and excludes it from counts in summary")
    void softCancelExcludesFromSummaryCounts() throws Exception {
        User manager = createTestUser("MGR_CANCEL", "mgr.cancel@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_CANCEL", "emp.cancel@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Task to Cancel",
                        "description": "This will be cancelled",
                        "priority": "LOW",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Cancel the task
        mockMvc.perform(delete("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isNoContent());

        // Task still exists with status CANCELLED
        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Summary counts exclude cancelled tasks
        mockMvc.perform(get("/api/v1/tasks/summary")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.pending").value(0))
            .andExpect(jsonPath("$.completed").value(0));
    }

    @Test
    @DisplayName("Comments and Attachments endpoints work as expected")
    void commentsAndAttachments() throws Exception {
        User manager = createTestUser("MGR_COM", "mgr.com@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_COM", "emp.com@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Commentable Task",
                        "description": "Task for testing comments and files",
                        "priority": "HIGH",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Add a comment
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "body": "Working on initial draft."
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.body").value("Working on initial draft."));

        // List comments
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/comments")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].body").value("Working on initial draft."));

        // Upload attachment
        byte[] pdfBytes = "%PDF-1.4 test attachment content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "spec.pdf",
            "application/pdf",
            pdfBytes
        );

        mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
                .file(file)
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.originalName").value("spec.pdf"));
    }

    @Test
    @DisplayName("Optimistic lock conflict returns 409 CONFLICT_VERSION")
    void optimisticLockConflictReturns409() throws Exception {
        User manager = createTestUser("MGR_LOCK", "mgr.lock@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_LOCK", "emp.lock@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Optimistic Locking Test",
                        "description": "Testing @Version concurrent modification",
                        "priority": "MEDIUM",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-09-30T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Employee updates progress (version becomes 1)
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 30
                    }
                """))
            .andExpect(status().isOk());

        // Simulate stale entity update by artificially setting the version in DB backwards
        jdbcTemplate.update("UPDATE tasks SET version = 99 WHERE id = ?", UUID.fromString(taskId));

        // Attempting to update with stale entity version will trigger OptimisticLockingFailureException -> 409 CONFLICT_VERSION
        mockMvc.perform(put("/api/v1/tasks/" + taskId)
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Updated Title",
                        "description": "Updated Description",
                        "priority": "HIGH",
                        "deadlineAt": "2026-10-15T18:00:00Z",
                        "version": 1
                    }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));

        // Also verify Hibernate's @Version directly prevents stale writes
        Task staleTask = taskRepository.findById(UUID.fromString(taskId)).orElseThrow();
        jdbcTemplate.update("UPDATE tasks SET version = 999 WHERE id = ?", UUID.fromString(taskId));
        staleTask.setTitle("Direct Stale Update");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskRepository.saveAndFlush(staleTask))
            .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Issue 1: Manager cannot assign task to an employee belonging to another manager")
    void managerCannotAssignTaskToEmployeeOfAnotherManager() throws Exception {
        User manager1 = createTestUser("MGR_SEC1", "mgr.sec1@octopyder.com", Role.MANAGER, null);
        User manager2 = createTestUser("MGR_SEC2", "mgr.sec2@octopyder.com", Role.MANAGER, null);
        User employee2 = createTestUser("EMP_SEC2", "emp.sec2@octopyder.com", Role.EMPLOYEE, manager2.getId());

        String mgr1Token = tokenFor(manager1);

        // Manager 1 attempts to assign task to Employee 2, explicitly setting managerId = manager1.id
        mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgr1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Unauthorized Assignment",
                        "description": "Attempting cross-team assignment",
                        "priority": "HIGH",
                        "assigneeId": "%s",
                        "managerId": "%s",
                        "deadlineAt": "2026-10-31T18:00:00Z"
                    }
                """.formatted(employee2.getId(), manager1.getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));
    }

    @Test
    @DisplayName("Issue 2: Cannot rollback or update progress on task in PENDING_APPROVAL")
    void cannotRollbackOrMutateProgressOnPendingApprovalTask() throws Exception {
        User manager = createTestUser("MGR_SEC3", "mgr.sec3@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_SEC3", "emp.sec3@octopyder.com", Role.EMPLOYEE, manager.getId());

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Pending Approval Lock Test",
                        "description": "Test immutability while pending approval",
                        "priority": "MEDIUM",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-10-31T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Employee submits directly to PENDING_APPROVAL
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/submit")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Attempting to call start() should be rejected with 409 Conflict
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_INVALID_TRANSITION"));

        // Attempting to update progress back to 50 should be rejected with 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "progress": 50
                    }
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("Issue 3: Task deliverables list, download and access controls")
    void taskDeliverablesListAndDownload() throws Exception {
        User manager = createTestUser("MGR_SEC4", "mgr.sec4@octopyder.com", Role.MANAGER, null);
        User employee = createTestUser("EMP_SEC4", "emp.sec4@octopyder.com", Role.EMPLOYEE, manager.getId());
        User otherEmployee = createTestUser("EMP_SEC5", "emp.sec5@octopyder.com", Role.EMPLOYEE, null);

        String mgrToken = tokenFor(manager);
        String empToken = tokenFor(employee);
        String otherEmpToken = tokenFor(otherEmployee);

        String createResp = mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + mgrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Attachment Download Test",
                        "description": "Deliverable upload and retrieval verification",
                        "priority": "HIGH",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-10-31T18:00:00Z"
                    }
                """.formatted(employee.getId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // Employee uploads attachment
        MockMultipartFile file = new MockMultipartFile(
            "file", "design_spec.pdf", "application/pdf", "%PDF-1.4 sample pdf content".getBytes()
        );

        String uploadResp = mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
                .file(file)
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.originalName").value("design_spec.pdf"))
            .andReturn().getResponse().getContentAsString();

        String attachmentId = com.jayway.jsonpath.JsonPath.read(uploadResp, "$.id");
        String storageKey = com.jayway.jsonpath.JsonPath.read(uploadResp, "$.storageKey");

        // Manager lists attachments
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(attachmentId));

        // Manager downloads attachment
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId + "/download")
                .header("Authorization", "Bearer " + mgrToken))
            .andExpect(status().isOk())
            .andExpect(result -> {
                assertThat(result.getResponse().getContentAsString()).contains("%PDF-1.4");
                assertThat(result.getResponse().getHeader("Content-Disposition")).contains("design_spec.pdf");
                assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            });

        // Other employee attempting download is forbidden / not found
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId + "/download")
                .header("Authorization", "Bearer " + otherEmpToken))
            .andExpect(status().isForbidden());

        // Stream via /api/v1/documents/stream/{storageKey} works for authorized user and is forbidden for other
        mockMvc.perform(get("/api/v1/documents/stream/" + storageKey)
                .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/stream/" + storageKey)
                .header("Authorization", "Bearer " + otherEmpToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Issue 4: Task creation and CSV import without manager returns clear validation error")
    void taskCreationWithoutManagerReturnsDescriptiveError() throws Exception {
        User admin = createTestUser("ADM_SEC1", "admin.sec1@octopyder.com", Role.ADMIN, null);
        User unassignedEmp = createTestUser("EMP_UNASS", "emp.unassigned@octopyder.com", Role.EMPLOYEE, null);

        String adminToken = tokenFor(admin);

        // Admin attempts to create task for employee with no manager without specifying managerId
        mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "title": "Unassigned Employee Task",
                        "description": "Should fail with clear error",
                        "priority": "LOW",
                        "assigneeId": "%s",
                        "deadlineAt": "2026-10-31T18:00:00Z"
                    }
                """.formatted(unassignedEmp.getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ASSIGNEE_HAS_NO_MANAGER"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Assignee does not have an assigned manager")));

        // Admin attempts CSV import for unassigned employee
        String csvContent = "Title,Description,Priority,Assignee,Deadline\n"
            + "CSV Task,Some description,HIGH," + unassignedEmp.getEmail() + ",2026-10-31T18:00:00Z\n";

        MockMultipartFile csvFile = new MockMultipartFile(
            "file", "tasks.csv", "text/csv", csvContent.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/tasks/import")
                .file(csvFile)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("ASSIGNEE_HAS_NO_MANAGER"));
    }
}
