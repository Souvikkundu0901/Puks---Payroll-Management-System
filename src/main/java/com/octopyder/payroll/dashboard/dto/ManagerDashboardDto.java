package com.octopyder.payroll.dashboard.dto;

import java.util.List;

public record ManagerDashboardDto(
    long teamMembersCount,
    long activeTasksCount,
    long pendingAttendanceCount,
    long pendingLeaveCount,
    long pendingSalaryApprovalsCount,
    long tasksAwaitingCount,
    long presentTodayCount,
    long totalTeamCount,
    double attendancePercentage,
    List<TeamMemberPerformanceDto> teamPerformance,
    List<PendingTaskApprovalDto> pendingTaskApprovals,
    List<SafeActivityDto> recentActivities,
    List<UpcomingDeadlineDto> upcomingDeadlines
) {}
