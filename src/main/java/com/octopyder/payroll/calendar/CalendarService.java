package com.octopyder.payroll.calendar;

import com.octopyder.payroll.calendar.dto.CalendarEventDto;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.leave.LeaveRequest;
import com.octopyder.payroll.leave.LeaveRequestRepository;
import com.octopyder.payroll.org.Holiday;
import com.octopyder.payroll.org.HolidayRepository;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.task.Task;
import com.octopyder.payroll.task.TaskRepository;
import com.octopyder.payroll.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CalendarService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final TaskRepository taskRepository;
    private final HolidayRepository holidayRepository;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final SettingsService settingsService;

    public CalendarService(
        LeaveRequestRepository leaveRequestRepository,
        TaskRepository taskRepository,
        HolidayRepository holidayRepository,
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        SettingsService settingsService
    ) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.taskRepository = taskRepository;
        this.holidayRepository = holidayRepository;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.settingsService = settingsService;
    }

    public List<CalendarEventDto> getEvents(CurrentUser currentUser, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Start and end dates are required");
        }
        if (to.isBefore(from)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "End date must be on or after start date");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > 93) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CALENDAR_RANGE_EXCEEDED", "Calendar date range cannot exceed 93 days");
        }

        String tz = settingsService.getString("company.timezone", "Asia/Kolkata");
        ZoneId zoneId = ZoneId.of(tz != null ? tz : "Asia/Kolkata");

        List<CalendarEventDto> events = new ArrayList<>();

        // 1. Holidays (visible to everyone)
        List<Holiday> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to);
        for (Holiday h : holidays) {
            events.add(new CalendarEventDto(
                null,
                h.getName(),
                h.getHolidayDate(),
                h.getHolidayDate(),
                "HOLIDAY",
                null,
                null
            ));
        }

        // Determine user scope
        boolean isAdmin = currentUser.role() == Role.ADMIN;
        boolean isManager = currentUser.role() == Role.MANAGER;

        List<UUID> scopedUserIds;
        if (isAdmin) {
            scopedUserIds = null;
        } else if (isManager) {
            List<User> team = userRepository.findByManagerIdAndDeletedAtIsNull(currentUser.id());
            List<UUID> ids = team.stream().map(User::getId).collect(Collectors.toList());
            ids.add(currentUser.id()); // include manager themselves
            scopedUserIds = ids;
        } else {
            scopedUserIds = List.of(currentUser.id());
        }

        // 2. Leaves (Approved only, hide private reasons)
        List<LeaveRequest> leaves;
        if (isAdmin) {
            leaves = leaveRequestRepository.findAllApprovedInDateRange(from, to);
        } else {
            leaves = leaveRequestRepository.findTeamApprovedInDateRange(scopedUserIds, from, to);
        }

        // 3. Tasks (Not CANCELLED)
        Instant startInstant = from.atStartOfDay(zoneId).toInstant();
        Instant endInstant = to.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1);

        List<Task> tasks;
        if (isAdmin) {
            tasks = taskRepository.findByDeadlineAtBetween(startInstant, endInstant);
        } else if (isManager) {
            tasks = taskRepository.findByAssigneeIdInAndDeadlineAtBetween(scopedUserIds, startInstant, endInstant);
        } else {
            tasks = taskRepository.findByAssigneeIdAndDeadlineAtBetween(currentUser.id(), startInstant, endInstant);
        }

        // Scoped preloading of only relevant users & profiles (Rule 6: no loading whole tables)
        java.util.Set<UUID> relevantUserIds = new java.util.HashSet<>();
        for (LeaveRequest l : leaves) {
            if (l.getUserId() != null) relevantUserIds.add(l.getUserId());
        }
        for (Task t : tasks) {
            if (t.getStatus() != TaskStatus.CANCELLED && t.getAssigneeId() != null) {
                relevantUserIds.add(t.getAssigneeId());
            }
        }

        Map<UUID, User> userMap = relevantUserIds.isEmpty() ? Map.of() :
            userRepository.findAllById(relevantUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<UUID, EmployeeProfile> profileMap = relevantUserIds.isEmpty() ? Map.of() :
            profileRepository.findAllById(relevantUserIds).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, p -> p, (a, b) -> a));

        for (LeaveRequest l : leaves) {
            User u = userMap.get(l.getUserId());
            EmployeeProfile p = profileMap.get(l.getUserId());
            String name = (p != null && p.getFirstName() != null)
                ? p.getFirstName() + (p.getLastName() != null && !p.getLastName().isBlank() ? " " + p.getLastName() : "")
                : (u != null ? u.getCode() : "Employee");
            String code = u != null ? u.getCode() : null;

            events.add(new CalendarEventDto(
                l.getId(),
                "On leave: " + name,
                l.getStartDate(),
                l.getEndDate(),
                "LEAVE",
                name,
                code
            ));
        }

        for (Task t : tasks) {
            if (t.getStatus() == TaskStatus.CANCELLED) {
                continue;
            }
            LocalDate deadlineDate = t.getDeadlineAt().atZone(zoneId).toLocalDate();
            User u = userMap.get(t.getAssigneeId());
            EmployeeProfile p = profileMap.get(t.getAssigneeId());
            String name = (p != null && p.getFirstName() != null)
                ? p.getFirstName() + (p.getLastName() != null && !p.getLastName().isBlank() ? " " + p.getLastName() : "")
                : (u != null ? u.getCode() : "Assignee");
            String code = u != null ? u.getCode() : null;

            events.add(new CalendarEventDto(
                t.getId(),
                t.getTitle(),
                deadlineDate,
                deadlineDate,
                "TASK",
                name,
                code
            ));
        }

        // Sort by date ascending and cap at 500 events
        return events.stream()
            .sorted(Comparator.comparing(CalendarEventDto::date))
            .limit(500)
            .toList();
    }
}
