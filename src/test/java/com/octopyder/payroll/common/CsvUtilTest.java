package com.octopyder.payroll.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvUtilTest {

    @Test
    void escapesFormulaInjectionPrefixes() {
        assertThat(CsvUtil.sanitizeCell("=cmd|'/C calc'!A0")).isEqualTo("'=cmd|'/C calc'!A0");
        assertThat(CsvUtil.sanitizeCell("+12345")).isEqualTo("'+12345");
        assertThat(CsvUtil.sanitizeCell("-sum(A1:A10)")).isEqualTo("'-sum(A1:A10)");
        assertThat(CsvUtil.sanitizeCell("@sum(A1:A10)")).isEqualTo("'@sum(A1:A10)");
    }

    @Test
    void normalStringsAreUntouched() {
        assertThat(CsvUtil.sanitizeCell("Regular Name")).isEqualTo("Regular Name");
        assertThat(CsvUtil.sanitizeCell("john.doe@example.com")).isEqualTo("john.doe@example.com");
        assertThat(CsvUtil.sanitizeCell("")).isEqualTo("");
        assertThat(CsvUtil.sanitizeCell(null)).isEqualTo("");
    }
}
