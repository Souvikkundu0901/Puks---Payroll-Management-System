package com.octopyder.payroll.people.dto;

import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.User;

import java.math.BigDecimal;

public record EmployeePayrollDetails(
    User user,
    EmployeeProfile profile,
    BigDecimal tds,
    BigDecimal pf,
    BigDecimal pt
) {
}
