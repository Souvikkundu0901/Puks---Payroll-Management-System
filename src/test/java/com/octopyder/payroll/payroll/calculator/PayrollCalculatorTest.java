package com.octopyder.payroll.payroll.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayrollCalculatorTest {

    @Test
    @DisplayName("G1: 50,000 gross, 55% completion, 0 unpaid, 3,300 statutory -> net 43,366.67, unlocked 21,666.67, released 93.33%")
    void testGoldenCaseG1() {
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("50000.00"),
            20, 11, // 55%
            0, 0,
            new BigDecimal("1000.00"), new BigDecimal("2100.00"), new BigDecimal("200.00"),
            true,
            PayrollSettings.defaultSettings()
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("55.00"), result.completionPct());
        assertEquals(2, result.retentionDays());
        assertEquals(new BigDecimal("3333.33"), result.retentionAmount());
        assertEquals(new BigDecimal("0.00"), result.absentDeduction());
        assertEquals(new BigDecimal("0.00"), result.unpaidLeaveDeduction());
        assertEquals(new BigDecimal("0.00"), result.totalUnpaidDeduction());
        assertEquals(new BigDecimal("3300.00"), result.statutoryTotal());
        assertEquals(new BigDecimal("43366.67"), result.net());
        assertEquals(new BigDecimal("25000.00"), result.defaultReleaseAmount());
        assertEquals(new BigDecimal("25000.00"), result.taskLinkedAmount());
        assertEquals(new BigDecimal("21666.67"), result.taskUnlockedAmount());
        assertEquals(new BigDecimal("93.33"), result.releasedPct());
        assertEquals(new BigDecimal("25000.00"), result.tranche1());
        assertEquals(new BigDecimal("18366.67"), result.tranche2());
        assertFalse(result.isNetNegativeClamped());
    }

    @Test
    @DisplayName("G2: 60,000 gross, 60% completion, 1 unpaid leave day -> net 54,000.00")
    void testGoldenCaseG2() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("60000.00"),
            10, 6,
            0, 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("60.00"), result.completionPct());
        assertEquals(2, result.retentionDays());
        assertEquals(new BigDecimal("4000.00"), result.retentionAmount());
        assertEquals(new BigDecimal("2000.00"), result.unpaidLeaveDeduction());
        assertEquals(new BigDecimal("54000.00"), result.net());
    }

    @Test
    @DisplayName("G3: 30,000 gross, 40% completion -> 7 days retention -> net 23,000.00")
    void testGoldenCaseG3() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("30000.00"),
            10, 4,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("40.00"), result.completionPct());
        assertEquals(7, result.retentionDays());
        assertEquals(new BigDecimal("7000.00"), result.retentionAmount());
        assertEquals(new BigDecimal("23000.00"), result.net());
    }

    @Test
    @DisplayName("G4: 45,000 gross, 60% completion -> 2 days retention -> net 42,000.00")
    void testGoldenCaseG4() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("45000.00"),
            10, 6,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("60.00"), result.completionPct());
        assertEquals(2, result.retentionDays());
        assertEquals(new BigDecimal("3000.00"), result.retentionAmount());
        assertEquals(new BigDecimal("42000.00"), result.net());
    }

    @Test
    @DisplayName("G5: 40,000 gross, 40% completion -> 7 days retention rounded once -> net 30,666.67")
    void testGoldenCaseG5() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("40000.00"),
            10, 4,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("40.00"), result.completionPct());
        assertEquals(7, result.retentionDays());
        // 40000 * 7 / 30 = 9333.3333... rounded once = 9333.33
        assertEquals(new BigDecimal("9333.33"), result.retentionAmount());
        assertEquals(new BigDecimal("30666.67"), result.net());
    }

    @Test
    @DisplayName("G6: 50,000 gross, 80% completion -> 0 retention -> net 50,000.00")
    void testGoldenCaseG6() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("50000.00"),
            10, 8,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("80.00"), result.completionPct());
        assertEquals(0, result.retentionDays());
        assertEquals(new BigDecimal("0.00"), result.retentionAmount());
        assertEquals(new BigDecimal("50000.00"), result.net());
    }

    @Test
    @DisplayName("G7: 30,000 gross, 60% completion -> 2 days retention -> net 28,000.00")
    void testGoldenCaseG7() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("30000.00"),
            10, 6,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("60.00"), result.completionPct());
        assertEquals(2, result.retentionDays());
        assertEquals(new BigDecimal("2000.00"), result.retentionAmount());
        assertEquals(new BigDecimal("28000.00"), result.net());
    }

    @Test
    @DisplayName("G8: 40,000 gross, 40% completion, 1 unpaid day -> components rounded first, net derived (29,333.34)")
    void testGoldenCaseG8() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("40000.00"),
            10, 4,
            0, 1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertEquals(new BigDecimal("40.00"), result.completionPct());
        assertEquals(7, result.retentionDays());
        assertEquals(new BigDecimal("9333.33"), result.retentionAmount());
        assertEquals(new BigDecimal("1333.33"), result.unpaidLeaveDeduction());
        // 40000 - 9333.33 - 1333.33 = 29333.34
        assertEquals(new BigDecimal("29333.34"), result.net());
    }

    @Test
    @DisplayName("Boundary: 70.00% exact completion gives 0 retention days")
    void testBoundary70PercentExact() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("50000.00"),
            10, 7,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(0, result.retentionDays());
        assertEquals(new BigDecimal("0.00"), result.retentionAmount());
    }

    @Test
    @DisplayName("Boundary: 69.99% completion gives 2 retention days (never round before comparing)")
    void testBoundary69Point99Percent() {
        // 6999 / 10000 = 69.99%
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("50000.00"),
            10000, 6999,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(2, result.retentionDays());
    }

    @Test
    @DisplayName("Boundary: 50.00% exact completion gives 2 retention days")
    void testBoundary50PercentExact() {
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("50000.00"),
            10, 5,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(2, result.retentionDays());
    }

    @Test
    @DisplayName("Boundary: 49.99% completion gives 7 retention days")
    void testBoundary49Point99Percent() {
        // 4999 / 10000 = 49.99%
        PayrollInputs inputs = PayrollInputs.of(
            new BigDecimal("50000.00"),
            10000, 4999,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(7, result.retentionDays());
    }

    @Test
    @DisplayName("Boundary: 0 tasks with noTasksCountsAs100=true gives 100% completion and 0 retention")
    void testBoundaryNoTasksCountsAs100True() {
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("50000.00"),
            0, 0,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            true,
            new PayrollSettings(30, new BigDecimal("70.00"), new BigDecimal("50.00"), 2, 7, true)
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(new BigDecimal("100.00"), result.completionPct());
        assertEquals(0, result.retentionDays());
    }

    @Test
    @DisplayName("Boundary: 0 tasks with noTasksCountsAs100=false gives 0% completion and 7 retention days")
    void testBoundaryNoTasksCountsAs100False() {
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("50000.00"),
            0, 0,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            true,
            new PayrollSettings(30, new BigDecimal("70.00"), new BigDecimal("50.00"), 2, 7, false)
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(new BigDecimal("0.00"), result.completionPct());
        assertEquals(7, result.retentionDays());
    }

    @Test
    @DisplayName("Retention disabled (e.g. Manager/Admin role decided by assembler): 0 retention days, but unpaid leave still deducted")
    void testRetentionDisabledRoleExemption() {
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("60000.00"),
            10, 2, // 20% completion
            0, 2,  // 2 unpaid leave days
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            false, // retentionEligible = false
            PayrollSettings.defaultSettings()
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(0, result.retentionDays());
        assertEquals(new BigDecimal("0.00"), result.retentionAmount());
        assertEquals(new BigDecimal("4000.00"), result.unpaidLeaveDeduction());
        assertEquals(new BigDecimal("56000.00"), result.net());
        assertEquals(new BigDecimal("30000.00"), result.defaultReleaseAmount());
        assertEquals(new BigDecimal("30000.00"), result.taskLinkedAmount());
        assertEquals(new BigDecimal("30000.00"), result.taskUnlockedAmount());
    }

    @Test
    @DisplayName("Guards: Gross <= 0, impossible task counts, negative days, and divisor <= 0 reject with IllegalArgumentException")
    void testInputGuards() {
        assertThrows(IllegalArgumentException.class, () ->
            new PayrollInputs(BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, null));

        assertThrows(IllegalArgumentException.class, () ->
            new PayrollInputs(new BigDecimal("-100"), 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, null));

        assertThrows(IllegalArgumentException.class, () ->
            PayrollInputs.of(new BigDecimal("50000"), -1, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () ->
            PayrollInputs.of(new BigDecimal("50000"), 5, 6, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () ->
            PayrollInputs.of(new BigDecimal("50000"), 5, 2, -1, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () ->
            PayrollInputs.of(new BigDecimal("50000"), 5, 2, 0, -1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () ->
            new PayrollSettings(0, new BigDecimal("70"), new BigDecimal("50"), 2, 7, true));
    }

    @Test
    @DisplayName("Net clamp & cap: deductions exceeding gross clamp net to 0 with warning flag")
    void testNetNegativeClampedAndCap() {
        // Gross 10,000, 35 unpaid days (exceeds month) -> capped at gross 10,000, TDS 5,000
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("10000.00"),
            10, 0, // 7 days retention
            20, 15, // total 35 days unpaid
            new BigDecimal("5000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            true,
            PayrollSettings.defaultSettings()
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);

        assertTrue(result.isNetNegativeClamped());
        assertEquals(new BigDecimal("0.00"), result.net());
        assertEquals(new BigDecimal("0.00"), result.tranche1());
        assertEquals(new BigDecimal("0.00"), result.tranche2());
        assertEquals(new BigDecimal("2666.67"), result.taskUnlockedAmount());
        // Unpaid deductions capped at gross
        assertTrue(result.totalUnpaidDeduction().compareTo(result.gross()) <= 0);
    }

    @Test
    @DisplayName("Task unlocked clamp: when retention exceeds task-linked part, taskUnlockedAmount clamps at 0")
    void testTaskUnlockedAmountClampedAtZero() {
        // Gross 10,000, taskLinked is 5,000. Divisor 30, retentionDaysLow = 20 -> retentionAmount = 10,000 * 20 / 30 = 6,666.67 > 5,000.
        PayrollSettings customSettings = new PayrollSettings(30, new BigDecimal("70.00"), new BigDecimal("50.00"), 2, 20, true);
        PayrollInputs inputs = new PayrollInputs(
            new BigDecimal("10000.00"),
            10, 0,
            0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            true,
            customSettings
        );

        PayrollResult result = PayrollCalculator.calculate(inputs);
        assertEquals(new BigDecimal("6666.67"), result.retentionAmount());
        assertEquals(new BigDecimal("0.00"), result.taskUnlockedAmount());
        assertEquals(new BigDecimal("5000.00"), result.defaultReleaseAmount());
        assertEquals(new BigDecimal("50.00"), result.releasedPct()); // (5000 + 0) / 10000 = 50%
    }
}
