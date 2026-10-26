package com.octopyder.payroll.common;

public final class CsvUtil {

    private CsvUtil() {
    }

    /**
     * Prevents formula injection (CSV Injection) by prepending a single quote
     * to any field that starts with '=', '+', '-', or '@'.
     */
    public static String sanitizeCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
            return "'" + value;
        }
        return value;
    }
}
