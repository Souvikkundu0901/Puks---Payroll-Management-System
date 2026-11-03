package com.octopyder.payroll.task;

import com.octopyder.payroll.BaseIntegrationTest;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.notification.Notification;
import com.octopyder.payroll.notification.NotificationRepository;
import com.octopyder.payroll.notification.NotificationType;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineReminderJobTest extends BaseIntegrationTest {

    @Autowired
    private DeadlineReminderJob deadlineReminderJob;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskActivityRepository activityRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE notifications, task_activities, task_attachments, task_comments, tasks, users CASCADE");

        manager = new User();
        manager.setCode("REM_MGR");
        manager.setEmail("manager.rem@example.com");
        manager.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
        manager.setRole(Role.MANAGER);
        manager.setStatus(UserStatus.ACTIVE);
        manager.setTokenVersion(1);
        manager.setCreatedAt(businessClock.instant());
        manager = userRepository.save(manager);

        employee = new User();
        employee.setCode("REM_EMP");
        employee.setEmail("emp.rem@example.com");
        employee.setPasswordHash("$2a$12$eACCgG32508QZkF/5.8.hO0vB8g50s5yY/3E.J4b5749vVb/kLgVq");
        employee.setRole(Role.EMPLOYEE);
        employee.setStatus(UserStatus.ACTIVE);
        employee.setManagerId(manager.getId());
        employee.setTokenVersion(1);
        employee.setCreatedAt(businessClock.instant());
        employee = userRepository.save(employee);
    }

    private Task createTask(String code, TaskStatus status, Instant deadlineAt) {
        Task t = new Task();
        t.setCode(code);
        t.setTitle("Task " + code);
        t.setDescription("Task description");
        t.setAssigneeId(employee.getId());
        t.setManagerId(manager.getId());
        t.setCreatedBy(manager.getId());
        t.setStatus(status);
        t.setPriority(TaskPriority.HIGH);
        t.setDeadlineAt(deadlineAt);
        t.setProgress(0);
        t.setCreatedAt(businessClock.instant());
        return taskRepository.save(t);
    }

    @Test
    @DisplayName("Should send reminders for ASSIGNED, IN_PROGRESS, and REJECTED tasks due in <= 24h")
    void remindsEligibleTasksDueWithin24Hours() {
        Instant now = businessClock.instant();

        createTask("TSK-REM1", TaskStatus.ASSIGNED, now.plus(12, ChronoUnit.HOURS));
        createTask("TSK-REM2", TaskStatus.IN_PROGRESS, now.plus(20, ChronoUnit.HOURS));
        createTask("TSK-REM3", TaskStatus.REJECTED, now.plus(4, ChronoUnit.HOURS));

        // Ineligible
        createTask("TSK-REM4", TaskStatus.APPROVED, now.plus(10, ChronoUnit.HOURS));
        createTask("TSK-REM5", TaskStatus.PENDING_APPROVAL, now.plus(10, ChronoUnit.HOURS));
        createTask("TSK-REM6", TaskStatus.IN_PROGRESS, now.plus(26, ChronoUnit.HOURS));
        createTask("TSK-REM7", TaskStatus.IN_PROGRESS, now.minus(2, ChronoUnit.HOURS));

        int sent = deadlineReminderJob.runReminders();
        assertThat(sent).isEqualTo(3);

        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(employee.getId());
        assertThat(notifications).hasSize(3);
        assertThat(notifications).allMatch(n -> n.getType() == NotificationType.DEADLINE_REMINDER);

        List<TaskActivity> activities = activityRepository.findAll();
        assertThat(activities).hasSize(3);
        assertThat(activities).allMatch(a -> a.getType() == TaskActivityType.DEADLINE_REMINDER);
    }

    @Test
    @DisplayName("Should be idempotent and not resend reminder for the same task and deadline")
    void idempotentRun() {
        Instant now = businessClock.instant();
        createTask("TSK-IDEMP1", TaskStatus.IN_PROGRESS, now.plus(10, ChronoUnit.HOURS));

        int firstRun = deadlineReminderJob.runReminders();
        assertThat(firstRun).isEqualTo(1);

        int secondRun = deadlineReminderJob.runReminders();
        assertThat(secondRun).isEqualTo(0);

        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(employee.getId());
        assertThat(notifications).hasSize(1);
    }
}
