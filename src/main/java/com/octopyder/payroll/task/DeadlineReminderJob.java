package com.octopyder.payroll.task;

import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.notification.NotificationService;
import com.octopyder.payroll.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Component
public class DeadlineReminderJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderJob.class);

    private final TaskRepository taskRepository;
    private final TaskActivityRepository activityRepository;
    private final NotificationService notificationService;
    private final BusinessClock businessClock;

    public DeadlineReminderJob(
        TaskRepository taskRepository,
        TaskActivityRepository activityRepository,
        NotificationService notificationService,
        BusinessClock businessClock
    ) {
        this.taskRepository = taskRepository;
        this.activityRepository = activityRepository;
        this.notificationService = notificationService;
        this.businessClock = businessClock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Running task deadline reminder catch-up on startup");
            runReminders();
        } catch (Exception e) {
            log.error("Error during startup task deadline reminder catch-up", e);
        }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    public void scheduledRun() {
        try {
            runReminders();
        } catch (Exception e) {
            log.error("Error running hourly deadline reminder job", e);
        }
    }

    @Transactional
    public int runReminders() {
        Instant now = businessClock.instant();
        Instant next24Hours = now.plus(24, ChronoUnit.HOURS);

        Set<TaskStatus> candidateStatuses = Set.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.REJECTED);
        List<Task> tasksDue = taskRepository.findByStatusInAndDeadlineAtBetween(candidateStatuses, now, next24Hours);

        int sentCount = 0;
        for (Task task : tasksDue) {
            String deadlineKey = task.getDeadlineAt().toString();
            if (activityRepository.existsByTaskIdAndTypeAndToValue(task.getId(), TaskActivityType.DEADLINE_REMINDER, deadlineKey)) {
                continue;
            }

            try {
                TaskActivity activity = new TaskActivity(
                    task.getId(),
                    task.getAssigneeId(),
                    TaskActivityType.DEADLINE_REMINDER,
                    null,
                    deadlineKey,
                    "Task deadline is in less than 24 hours (" + task.getDeadlineAt() + ")",
                    now
                );
                activityRepository.save(activity);

                notificationService.createNotification(
                    task.getAssigneeId(),
                    NotificationType.DEADLINE_REMINDER,
                    "Deadline Approaching: " + task.getTitle(),
                    "Task '" + task.getTitle() + "' is due on " + task.getDeadlineAt(),
                    "/employee/tasks.html"
                );
                sentCount++;
            } catch (DataIntegrityViolationException dive) {
                log.debug("Reminder already recorded for task {} at {}", task.getId(), deadlineKey);
            } catch (Exception e) {
                log.error("Failed to send deadline reminder for task {}", task.getId(), e);
            }
        }
        return sentCount;
    }
}
