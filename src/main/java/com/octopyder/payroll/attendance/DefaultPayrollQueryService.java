package com.octopyder.payroll.attendance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

@Service
public class DefaultPayrollQueryService implements PayrollQueryService {

    private final JdbcTemplate jdbcTemplate;

    public DefaultPayrollQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsPayrollForUserAndMonth(UUID userId, YearMonth month) {
        Boolean tableExists = jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.payroll_records') IS NOT NULL",
            Boolean.class
        );
        if (Boolean.FALSE.equals(tableExists)) {
            return false;
        }

        LocalDate firstDay = month.atDay(1);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payroll_records WHERE user_id = ? AND month = ? AND (status IN ('APPROVED', 'RELEASED') OR paid_amount > 0)",
            Integer.class,
            userId, firstDay
        );
        return count != null && count > 0;
    }
}
