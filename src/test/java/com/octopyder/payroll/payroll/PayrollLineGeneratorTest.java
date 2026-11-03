package com.octopyder.payroll.payroll;

import com.octopyder.payroll.payroll.service.PayrollLineGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayrollLineGeneratorTest {

    @Test
    @DisplayName("Earnings breakdown splits gross exactly, last part takes remainder, and line-sum matches net")
    void testStandardBreakupAndLineSumInvariant() {
        UUID recordId = UUID.randomUUID();
        BigDecimal gross = new BigDecimal("50000.00");
        BigDecimal retentionReleased = new BigDecimal("3333.33");
        BigDecimal absentDeduction = BigDecimal.ZERO;
        BigDecimal unpaidLeaveDeduction = BigDecimal.ZERO;
        BigDecimal tds = new BigDecimal("1000.00");
        BigDecimal pf = new BigDecimal("2100.00");
        BigDecimal pt = new BigDecimal("200.00");
        BigDecimal retention = new BigDecimal("3333.33");

        var result = PayrollLineGenerator.generateLines(
            recordId, gross, retentionReleased, absentDeduction, unpaidLeaveDeduction,
            tds, pf, pt, retention,
            "Basic Salary 50%, HRA 20%, Medical 10%, Travel 10%, Performance Bonus 10%"
        );

        assertThat(result.netClamped()).isFalse();
        // gross (50,000) + retentionReleased (3,333.33) - statutory (3,300) - retention (3,333.33) = 46,700.00
        assertThat(result.netPayable()).isEqualByComparingTo("46700.00");

        // Verify earnings sum to gross + retentionReleased
        BigDecimal totalEarnings = result.lines().stream()
            .filter(l -> l.getKind() == PayrollLineKind.EARNING)
            .map(PayrollLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalEarnings).isEqualByComparingTo("53333.33");

        // Check individual base parts sum to 50000.00
        BigDecimal baseEarnings = result.lines().stream()
            .filter(l -> l.getKind() == PayrollLineKind.EARNING && !l.getLabel().equals("Retention released"))
            .map(PayrollLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(baseEarnings).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("Odd gross splits with last part taking fractional remainder so sum is exact")
    void testOddGrossRemainder() {
        UUID recordId = UUID.randomUUID();
        BigDecimal gross = new BigDecimal("33333.33");

        var result = PayrollLineGenerator.generateLines(
            recordId, gross, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null
        );

        BigDecimal baseEarnings = result.lines().stream()
            .filter(l -> l.getKind() == PayrollLineKind.EARNING)
            .map(PayrollLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(baseEarnings).isEqualByComparingTo("33333.33");
    }

    @Test
    @DisplayName("Negative net is clamped to zero and ADJUSTMENT line preserves line-sum invariant")
    void testNetClampedWithAdjustmentLine() {
        UUID recordId = UUID.randomUUID();
        BigDecimal gross = new BigDecimal("10000.00");
        BigDecimal absentDeduction = new BigDecimal("15000.00"); // exceeds gross

        var result = PayrollLineGenerator.generateLines(
            recordId, gross, BigDecimal.ZERO, absentDeduction, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null
        );

        assertThat(result.netClamped()).isTrue();
        assertThat(result.netPayable()).isEqualByComparingTo("0.00");

        boolean hasAdjustment = result.lines().stream()
            .anyMatch(l -> l.getKind() == PayrollLineKind.ADJUSTMENT && l.getAmount().compareTo(new BigDecimal("5000.00")) == 0);
        assertThat(hasAdjustment).isTrue();
    }
}
