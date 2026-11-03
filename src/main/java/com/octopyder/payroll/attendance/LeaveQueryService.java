package com.octopyder.payroll.attendance;

import java.time.LocalDate;
import java.util.UUID;

public interface LeaveQueryService {
    boolean hasApprovedLeave(UUID userId, LocalDate date);
    int countApprovedLeaveDays(UUID userId, LocalDate from, LocalDate to);
}
