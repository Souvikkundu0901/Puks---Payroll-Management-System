package com.octopyder.payroll.attendance;

import java.time.YearMonth;
import java.util.UUID;

public interface PayrollQueryService {
    boolean existsPayrollForUserAndMonth(UUID userId, YearMonth month);
}
