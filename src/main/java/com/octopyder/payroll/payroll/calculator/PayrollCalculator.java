package com.octopyder.payroll.payroll.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PayrollCalculator {

    private PayrollCalculator() {
    }

    public static PayrollResult calculate(PayrollInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("PayrollInputs cannot be null");
        }

        BigDecimal gross = inputs.gross().setScale(2, RoundingMode.HALF_UP);
        PayrollSettings settings = inputs.settings();
        int divisor = settings.dayRateDivisor();

        // 1. Two parts of salary: 50% default release and 50% task-linked release (BR-P2)
        // defaultRelease computed once and taskLinked = gross - defaultRelease (change 3)
        BigDecimal defaultReleaseAmount = gross.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taskLinkedAmount = gross.subtract(defaultReleaseAmount);

        // 2. Completion % and Retention Days (BR-P3, BR-P4)
        BigDecimal completionPct;
        int retentionDays;

        if (inputs.retentionEligible()) {
            if (inputs.tasksTotal() == 0) {
                if (settings.noTasksCountsAs100()) {
                    completionPct = new BigDecimal("100.00");
                    retentionDays = 0;
                } else {
                    completionPct = new BigDecimal("0.00");
                    retentionDays = settings.retentionDaysLow();
                }
            } else {
                BigDecimal rawPct = BigDecimal.valueOf(inputs.tasksApproved())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(inputs.tasksTotal()), 10, RoundingMode.HALF_UP);
                completionPct = rawPct.setScale(2, RoundingMode.HALF_UP);

                // Threshold comparisons use unrounded rawPct (never round before comparing)
                if (rawPct.compareTo(settings.thresholdFullRelease()) >= 0) {
                    retentionDays = 0;
                } else if (rawPct.compareTo(settings.thresholdMidRelease()) >= 0) {
                    retentionDays = settings.retentionDaysMid();
                } else {
                    retentionDays = settings.retentionDaysLow();
                }
            }
        } else {
            // Retention not eligible (e.g. Manager or Admin per D19)
            retentionDays = 0;
            if (inputs.tasksTotal() == 0) {
                completionPct = settings.noTasksCountsAs100() ? new BigDecimal("100.00") : new BigDecimal("0.00");
            } else {
                completionPct = BigDecimal.valueOf(inputs.tasksApproved())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(inputs.tasksTotal()), 2, RoundingMode.HALF_UP);
            }
        }

        // 3. Retention amount: gross * retention_days / divisor, rounded once (BR-P6)
        BigDecimal retentionAmount = gross.multiply(BigDecimal.valueOf(retentionDays))
            .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);

        // 4. Absent days & Unpaid leave deductions: separate in inputs and results (change 2, BR-P6)
        BigDecimal absentDeduction = gross.multiply(BigDecimal.valueOf(inputs.absentDays()))
            .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
        BigDecimal unpaidLeaveDeduction = gross.multiply(BigDecimal.valueOf(inputs.unpaidLeaveDays()))
            .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);

        BigDecimal totalUnpaidDeduction = absentDeduction.add(unpaidLeaveDeduction);
        // Cap unpaid deduction at gross (guard 6)
        if (totalUnpaidDeduction.compareTo(gross) > 0) {
            totalUnpaidDeduction = gross;
        }

        // 5. Task unlocked amount: taskLinkedAmount - retentionAmount clamped at 0 (guard 6)
        BigDecimal rawTaskUnlocked = taskLinkedAmount.subtract(retentionAmount);
        BigDecimal taskUnlockedAmount = rawTaskUnlocked.compareTo(BigDecimal.ZERO) < 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : rawTaskUnlocked;

        // 6. Released %: (defaultReleaseAmount + taskUnlockedAmount) / gross * 100 (BR-P2)
        BigDecimal releasedPct = defaultReleaseAmount.add(taskUnlockedAmount)
            .multiply(BigDecimal.valueOf(100))
            .divide(gross, 2, RoundingMode.HALF_UP);

        // 7. Statutory deductions: TDS, PF, PT separate + total (change 7)
        BigDecimal tds = inputs.tds().setScale(2, RoundingMode.HALF_UP);
        BigDecimal pf = inputs.pf().setScale(2, RoundingMode.HALF_UP);
        BigDecimal pt = inputs.pt().setScale(2, RoundingMode.HALF_UP);
        BigDecimal statutoryTotal = tds.add(pf).add(pt);

        // 8. Net and Net Clamp: net = gross - totalUnpaidDeduction - retentionAmount - statutoryTotal (BR-P9, guard 6)
        BigDecimal totalDeductions = totalUnpaidDeduction.add(retentionAmount).add(statutoryTotal);
        BigDecimal rawNet = gross.subtract(totalDeductions);

        boolean isNetNegativeClamped = false;
        BigDecimal net;
        if (rawNet.compareTo(BigDecimal.ZERO) < 0) {
            net = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            isNetNegativeClamped = true;
        } else {
            net = rawNet.setScale(2, RoundingMode.HALF_UP);
        }

        // 9. Payout tranches: tranche1 = min(defaultReleaseAmount, net), tranche2 = net - tranche1 (BR-P10)
        BigDecimal tranche1 = defaultReleaseAmount.min(net);
        BigDecimal tranche2 = net.subtract(tranche1);

        return new PayrollResult(
            gross,
            completionPct,
            retentionDays,
            retentionAmount,
            inputs.absentDays(),
            absentDeduction,
            inputs.unpaidLeaveDays(),
            unpaidLeaveDeduction,
            totalUnpaidDeduction,
            tds,
            pf,
            pt,
            statutoryTotal,
            totalDeductions,
            net,
            defaultReleaseAmount,
            taskLinkedAmount,
            taskUnlockedAmount,
            releasedPct,
            tranche1,
            tranche2,
            isNetNegativeClamped
        );
    }
}
