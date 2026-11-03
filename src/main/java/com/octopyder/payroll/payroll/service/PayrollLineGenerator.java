package com.octopyder.payroll.payroll.service;

import com.octopyder.payroll.payroll.PayrollLine;
import com.octopyder.payroll.payroll.PayrollLineKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PayrollLineGenerator {

    public record LineGenerationResult(
        List<PayrollLine> lines,
        boolean netClamped,
        BigDecimal netPayable
    ) {}

    public static LineGenerationResult generateLines(
        UUID recordId,
        BigDecimal gross,
        BigDecimal retentionReleasedAmount,
        BigDecimal absentDeduction,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal tds,
        BigDecimal pf,
        BigDecimal pt,
        BigDecimal retentionAmount,
        String breakupPercentSetting
    ) {
        List<PayrollLine> lines = new ArrayList<>();
        int order = 1;

        // 1. Parse or default breakup percentages: Basic 50, HRA 20, Medical 10, Travel 10, Performance bonus 10
        // e.g. "Basic 50, HRA 20, Medical 10, Travel 10, Performance bonus 10"
        List<BreakupPart> parts = parseBreakup(breakupPercentSetting);

        BigDecimal earningsSoFar = BigDecimal.ZERO;
        for (int i = 0; i < parts.size(); i++) {
            BreakupPart part = parts.get(i);
            BigDecimal partAmount;
            if (i == parts.size() - 1) {
                // Last part takes the remainder
                partAmount = gross.subtract(earningsSoFar);
            } else {
                partAmount = gross.multiply(part.pct)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                earningsSoFar = earningsSoFar.add(partAmount);
            }
            lines.add(new PayrollLine(recordId, PayrollLineKind.EARNING, part.label, partAmount, order++));
        }

        // Released retention
        if (retentionReleasedAmount != null && retentionReleasedAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.EARNING, "Retention released", retentionReleasedAmount, order++));
        }

        // Deductions
        if (unpaidLeaveDeduction != null && unpaidLeaveDeduction.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.DEDUCTION, "Unpaid leave deduction", unpaidLeaveDeduction, order++));
        }
        if (absentDeduction != null && absentDeduction.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.DEDUCTION, "Absent days deduction", absentDeduction, order++));
        }
        if (tds != null && tds.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.DEDUCTION, "TDS", tds, order++));
        }
        if (pf != null && pf.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.DEDUCTION, "PF", pf, order++));
        }
        if (pt != null && pt.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.DEDUCTION, "Professional Tax", pt, order++));
        }

        // Retention
        if (retentionAmount != null && retentionAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new PayrollLine(recordId, PayrollLineKind.RETENTION, "Retention", retentionAmount, order++));
        }

        // Sum earnings & deductions
        BigDecimal totalEarnings = gross.add(retentionReleasedAmount != null ? retentionReleasedAmount : BigDecimal.ZERO);
        BigDecimal totalDeductions = BigDecimal.ZERO;
        if (unpaidLeaveDeduction != null) totalDeductions = totalDeductions.add(unpaidLeaveDeduction);
        if (absentDeduction != null) totalDeductions = totalDeductions.add(absentDeduction);
        if (tds != null) totalDeductions = totalDeductions.add(tds);
        if (pf != null) totalDeductions = totalDeductions.add(pf);
        if (pt != null) totalDeductions = totalDeductions.add(pt);
        if (retentionAmount != null) totalDeductions = totalDeductions.add(retentionAmount);

        BigDecimal rawNet = totalEarnings.subtract(totalDeductions);
        boolean netClamped = false;
        BigDecimal netPayable = rawNet;

        if (rawNet.compareTo(BigDecimal.ZERO) < 0) {
            netClamped = true;
            netPayable = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal adjustmentAmount = totalDeductions.subtract(totalEarnings);
            lines.add(new PayrollLine(recordId, PayrollLineKind.ADJUSTMENT, "Net zero adjustment", adjustmentAmount, order++));
        }

        // Invariant assertion check: sum(EARNING) - sum(DEDUCTION) - sum(RETENTION) + sum(ADJUSTMENT) == netPayable
        BigDecimal invariantNet = BigDecimal.ZERO;
        for (PayrollLine line : lines) {
            switch (line.getKind()) {
                case EARNING -> invariantNet = invariantNet.add(line.getAmount());
                case DEDUCTION, RETENTION -> invariantNet = invariantNet.subtract(line.getAmount());
                case ADJUSTMENT -> invariantNet = invariantNet.add(line.getAmount());
            }
        }
        if (invariantNet.compareTo(netPayable) != 0) {
            throw new IllegalStateException("Line-sum invariant violation: computed " + invariantNet + " but netPayable is " + netPayable);
        }

        return new LineGenerationResult(lines, netClamped, netPayable);
    }

    private record BreakupPart(String label, BigDecimal pct) {}

    private static List<BreakupPart> parseBreakup(String setting) {
        if (setting == null || setting.isBlank()) {
            return defaultBreakup();
        }
        List<BreakupPart> list = new ArrayList<>();
        String[] tokens = setting.split(",");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 0) {
                String label = trimmed.substring(0, lastSpace).trim();
                String pctStr = trimmed.substring(lastSpace + 1).replace("%", "").trim();
                try {
                    BigDecimal pct = new BigDecimal(pctStr);
                    list.add(new BreakupPart(label, pct));
                } catch (NumberFormatException ignored) {}
            }
        }
        return list.isEmpty() ? defaultBreakup() : list;
    }

    private static List<BreakupPart> defaultBreakup() {
        return List.of(
            new BreakupPart("Basic Salary", new BigDecimal("50")),
            new BreakupPart("HRA", new BigDecimal("20")),
            new BreakupPart("Medical Allowance", new BigDecimal("10")),
            new BreakupPart("Travel Allowance", new BigDecimal("10")),
            new BreakupPart("Performance Bonus", new BigDecimal("10"))
        );
    }
}
