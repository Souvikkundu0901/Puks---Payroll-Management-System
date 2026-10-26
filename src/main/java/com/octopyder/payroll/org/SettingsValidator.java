package com.octopyder.payroll.org;

import com.octopyder.payroll.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SettingsValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Set<String> VALID_DAYS = Set.of(
        "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
    );

    public void validate(String group, Map<String, Object> updates) {
        if (!SettingDefinition.getAllowedGroups().contains(group)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_GROUP", "Invalid settings group: " + group);
        }

        Map<String, SettingDefinition> registry = SettingDefinition.getRegistry();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            SettingDefinition def = registry.get(key);
            if (def == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_UNKNOWN_KEY", "Unknown setting key: " + key);
            }

            if (!def.group().equals(group)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_WRONG_GROUP", "Key " + key + " does not belong to group " + group);
            }

            validateIndividualKey(key, value);
        }

        // Cross-key validation for attendance start and end time
        if (updates.containsKey("attendance.officeStart") && updates.containsKey("attendance.officeEnd")) {
            LocalTime start = LocalTime.parse(updates.get("attendance.officeStart").toString());
            LocalTime end = LocalTime.parse(updates.get("attendance.officeEnd").toString());
            if (!start.isBefore(end)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_TIME_ORDER", "attendance.officeStart must be before officeEnd");
            }
        }
    }

    private void validateIndividualKey(String key, Object value) {
        if (value == null) {
            return;
        }

        switch (key) {
            case "attendance.graceMinutes" -> {
                int grace = toInt(value, key);
                if (grace != 15 && grace != 30) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "attendance.graceMinutes must be 15 or 30");
                }
            }
            case "attendance.officeStart", "attendance.officeEnd" -> {
                String timeStr = value.toString();
                if (!TIME_PATTERN.matcher(timeStr).matches()) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "Invalid time format for " + key + " (expected HH:mm)");
                }
                try {
                    LocalTime.parse(timeStr);
                } catch (DateTimeParseException e) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "Invalid time value for " + key);
                }
            }
            case "attendance.weeklyOffDays" -> {
                String daysStr = value.toString();
                String[] parts = daysStr.split(",");
                for (String p : parts) {
                    String clean = p.trim().toUpperCase();
                    if (!VALID_DAYS.contains(clean)) {
                        throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "Invalid day of week in weeklyOffDays: " + clean);
                    }
                }
            }
            case "payroll.dayRateDivisor" -> {
                int divisor = toInt(value, key);
                if (divisor <= 0) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "payroll.dayRateDivisor must be greater than 0");
                }
            }
            case "payroll.workingDays" -> {
                int days = toInt(value, key);
                if (days < 1 || days > 31) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "payroll.workingDays must be between 1 and 31");
                }
            }
            case "payroll.generationDay", "payroll.releaseDay" -> {
                int day = toInt(value, key);
                if (day < 1 || day > 31) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", key + " must be between 1 and 31");
                }
            }
            case "payroll.breakupPercent" -> validateBreakupPercent(value);
            case "security.passwordMinLength" -> {
                int min = toInt(value, key);
                if (min < 8) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "security.passwordMinLength must be at least 8");
                }
                if (min > 64) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "security.passwordMinLength cannot exceed 64");
                }
            }
            case "security.maxLoginAttempts" -> {
                int max = toInt(value, key);
                if (max < 1 || max > 20) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "security.maxLoginAttempts must be between 1 and 20");
                }
            }
            case "security.sessionTimeout" -> {
                int timeout = toInt(value, key);
                if (timeout < 5 || timeout > 1440) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "security.sessionTimeout must be between 5 and 1440 minutes");
                }
            }
            case "security.autoLockMinutes" -> {
                int lockMin = toInt(value, key);
                if (lockMin < 1 || lockMin > 1440) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "security.autoLockMinutes must be between 1 and 1440 minutes");
                }
            }
            case "company.timezone" -> {
                try {
                    ZoneId.of(value.toString());
                } catch (Exception e) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "Invalid timezone: " + value);
                }
            }
        }
    }

    private void validateBreakupPercent(Object value) {
        try {
            Map<String, Number> map;
            if (value instanceof Map<?, ?> m) {
                map = (Map<String, Number>) m;
            } else {
                map = MAPPER.readValue(value.toString(), new TypeReference<>() {});
            }
            double sum = 0.0;
            for (Number num : map.values()) {
                sum += num.doubleValue();
            }
            if (Math.abs(sum - 100.0) > 0.01) {
                throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_BREAKUP", "Breakup percentages must sum to 100 (current sum: " + sum + ")");
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_BREAKUP", "Invalid breakupPercent format: " + e.getMessage());
        }
    }

    private int toInt(Object value, String key) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID_VALUE", "Expected integer for " + key);
        }
    }
}
