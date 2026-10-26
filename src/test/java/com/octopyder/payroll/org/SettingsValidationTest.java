package com.octopyder.payroll.org;

import com.octopyder.payroll.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsValidationTest {

    private SettingsValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SettingsValidator();
    }

    @Test
    void rejectsUnknownKey() {
        assertThatThrownBy(() -> validator.validate("company", Map.of("company.unknownKey", "value")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Unknown setting key");
    }

    @Test
    void rejectsSettingForWrongGroup() {
        assertThatThrownBy(() -> validator.validate("payroll", Map.of("company.name", "New Name")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("does not belong to group");
    }

    @Test
    void validatesAttendanceGraceMinutes() {
        // 15 and 30 are allowed
        validator.validate("attendance", Map.of("attendance.graceMinutes", 15));
        validator.validate("attendance", Map.of("attendance.graceMinutes", 30));

        // Other values are rejected
        assertThatThrownBy(() -> validator.validate("attendance", Map.of("attendance.graceMinutes", 20)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("graceMinutes must be 15 or 30");
    }

    @Test
    void validatesOfficeStartAndEndTimeOrder() {
        // Valid time order: start 09:00, end 18:00
        validator.validate("attendance", Map.of(
            "attendance.officeStart", "09:00",
            "attendance.officeEnd", "18:00"
        ));

        // Invalid time order: start 18:00, end 09:00
        assertThatThrownBy(() -> validator.validate("attendance", Map.of(
            "attendance.officeStart", "18:00",
            "attendance.officeEnd", "09:00"
        )))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("officeStart must be before officeEnd");

        // Invalid time format
        assertThatThrownBy(() -> validator.validate("attendance", Map.of("attendance.officeStart", "9am")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Invalid time format");
    }

    @Test
    void validatesDayRateDivisor() {
        validator.validate("payroll", Map.of("payroll.dayRateDivisor", 30));

        assertThatThrownBy(() -> validator.validate("payroll", Map.of("payroll.dayRateDivisor", 0)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("dayRateDivisor must be greater than 0");

        assertThatThrownBy(() -> validator.validate("payroll", Map.of("payroll.dayRateDivisor", -5)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("dayRateDivisor must be greater than 0");
    }

    @Test
    void validatesBreakupPercentSumEquals100() {
        // Basic 50, HRA 20, Medical 10, Travel 10, Bonus 10 -> Sum = 100
        validator.validate("payroll", Map.of(
            "payroll.breakupPercent", Map.of("basic", 50, "hra", 20, "medical", 10, "travel", 10, "bonus", 10)
        ));

        // Sum = 90 -> must be rejected
        assertThatThrownBy(() -> validator.validate("payroll", Map.of(
            "payroll.breakupPercent", Map.of("basic", 50, "hra", 20, "medical", 10, "travel", 10)
        )))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Breakup percentages must sum to 100");
    }

    @Test
    void validatesWeeklyOffDays() {
        // Valid weekly off days
        validator.validate("attendance", Map.of("attendance.weeklyOffDays", "SAT,SUN"));
        validator.validate("attendance", Map.of("attendance.weeklyOffDays", "SUN"));

        // Invalid day name
        assertThatThrownBy(() -> validator.validate("attendance", Map.of("attendance.weeklyOffDays", "SAT,FUNDAY")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Invalid day of week");
    }

    @Test
    void validatesSecurityFloorsAndCeilings() {
        // passwordMinLength: min 8, max 64
        validator.validate("security", Map.of("security.passwordMinLength", 8));
        validator.validate("security", Map.of("security.passwordMinLength", 16));

        assertThatThrownBy(() -> validator.validate("security", Map.of("security.passwordMinLength", 6)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("passwordMinLength must be at least 8");

        assertThatThrownBy(() -> validator.validate("security", Map.of("security.passwordMinLength", 100)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("passwordMinLength cannot exceed 64");

        // maxLoginAttempts: min 1, max 20
        validator.validate("security", Map.of("security.maxLoginAttempts", 5));
        assertThatThrownBy(() -> validator.validate("security", Map.of("security.maxLoginAttempts", 0)))
            .isInstanceOf(AppException.class);

        // sessionTimeout: min 5, max 1440
        validator.validate("security", Map.of("security.sessionTimeout", 30));
        assertThatThrownBy(() -> validator.validate("security", Map.of("security.sessionTimeout", 2)))
            .isInstanceOf(AppException.class);
    }

    @Test
    void validatesTimezone() {
        validator.validate("company", Map.of("company.timezone", "Asia/Kolkata"));
        validator.validate("company", Map.of("company.timezone", "UTC"));

        assertThatThrownBy(() -> validator.validate("company", Map.of("company.timezone", "Invalid/Timezone_123")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Invalid timezone");
    }
}
