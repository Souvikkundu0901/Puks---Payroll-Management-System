package com.octopyder.payroll.people.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DeductionsRequest(
    List<DeductionItem> deductions
) {
    public record DeductionItem(
        String type,
        BigDecimal monthlyAmount,
        LocalDate effectiveFrom
    ) {}
}
