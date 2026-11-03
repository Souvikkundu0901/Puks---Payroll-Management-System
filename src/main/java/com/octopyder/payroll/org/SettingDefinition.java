package com.octopyder.payroll.org;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record SettingDefinition(
    String key,
    String group,
    SettingType type,
    Object defaultValue
) {
    public enum SettingType {
        STRING, INT, BOOLEAN, TIME, JSON
    }

    private static final Map<String, SettingDefinition> REGISTRY = new HashMap<>();
    private static final Set<String> ALLOWED_GROUPS = Set.of(
        "company", "payroll", "leave", "attendance", "security", "appearance", "tasks", "backup"
    );

    private static final Set<String> SAFE_APP_CONFIG_KEYS = Set.of(
        "company.name", "company.gst", "company.address", "company.email", "company.phone",
        "company.website", "company.logo", "company.timezone",
        "payroll.cycle", "payroll.workingDays", "payroll.generationDay", "payroll.releaseDay",
        "leave.monthlyCredit", "leave.resetDate", "leave.carryForwardOnReset",
        "attendance.officeStart", "attendance.officeEnd", "attendance.graceMinutes", "attendance.weeklyOffDays",
        "appearance.theme", "appearance.animations"
    );

    static {
        // Company
        reg("company.name", "company", SettingType.STRING, "Puk's");
        reg("company.gst", "company", SettingType.STRING, "19ABCDE1234F1Z5");
        reg("company.address", "company", SettingType.STRING, "Sector V, Salt Lake, Kolkata");
        reg("company.email", "company", SettingType.STRING, "admin@octopyder.com");
        reg("company.phone", "company", SettingType.STRING, "+91 9876543210");
        reg("company.website", "company", SettingType.STRING, "https://www.octopyder.com");
        reg("company.logo", "company", SettingType.STRING, null);
        reg("company.timezone", "company", SettingType.STRING, "Asia/Kolkata");

        // Payroll
        reg("payroll.cycle", "payroll", SettingType.STRING, "MONTHLY");
        reg("payroll.workingDays", "payroll", SettingType.INT, 26);
        reg("payroll.dayRateDivisor", "payroll", SettingType.INT, 30);
        reg("payroll.generationDay", "payroll", SettingType.INT, 28);
        reg("payroll.releaseDay", "payroll", SettingType.INT, 30);
        reg("payroll.overtimeEnabled", "payroll", SettingType.BOOLEAN, false);
        reg("payroll.noTasksCountsAs100", "payroll", SettingType.BOOLEAN, true);
        reg("payroll.allowMidMonthGeneration", "payroll", SettingType.BOOLEAN, false);
        reg("payroll.breakupPercent", "payroll", SettingType.JSON, "{\"basic\":50,\"hra\":20,\"medical\":10,\"travel\":10,\"bonus\":10}");
        reg("payroll.includeAdmins", "payroll", SettingType.BOOLEAN, false);

        // Leave
        reg("leave.monthlyCredit", "leave", SettingType.INT, 1);
        reg("leave.resetDate", "leave", SettingType.STRING, "04-01");
        reg("leave.carryForwardOnReset", "leave", SettingType.INT, 0);
        reg("leave.managerApproval", "leave", SettingType.BOOLEAN, true);

        // Attendance
        reg("attendance.officeStart", "attendance", SettingType.TIME, "09:00");
        reg("attendance.officeEnd", "attendance", SettingType.TIME, "18:00");
        reg("attendance.graceMinutes", "attendance", SettingType.INT, 15);
        reg("attendance.gpsVerification", "attendance", SettingType.BOOLEAN, true);
        reg("attendance.remoteAllowed", "attendance", SettingType.BOOLEAN, true);
        reg("attendance.managerApproval", "attendance", SettingType.BOOLEAN, true);
        reg("attendance.weeklyOffDays", "attendance", SettingType.STRING, "SAT,SUN");
        reg("attendance.halfDayMinutes", "attendance", SettingType.INT, 240);
        reg("attendance.lowAccuracyM", "attendance", SettingType.INT, 200);

        // Security
        reg("security.sessionTimeout", "security", SettingType.INT, 30);
        reg("security.maxLoginAttempts", "security", SettingType.INT, 5);
        reg("security.autoLockMinutes", "security", SettingType.INT, 15);
        reg("security.passwordMinLength", "security", SettingType.INT, 8);
        reg("security.passwordRules", "security", SettingType.STRING, "all_four");
        reg("security.adminTwoFactor", "security", SettingType.BOOLEAN, false);

        // Appearance
        reg("appearance.theme", "appearance", SettingType.STRING, "default");
        reg("appearance.animations", "appearance", SettingType.BOOLEAN, true);

        // Tasks
        reg("tasks.employeeSelfAssign", "tasks", SettingType.BOOLEAN, false);

        // Backup
        reg("backup.frequency", "backup", SettingType.STRING, "daily");
        reg("backup.cloudAuto", "backup", SettingType.BOOLEAN, true);
    }

    private static void reg(String key, String group, SettingType type, Object defaultValue) {
        REGISTRY.put(key, new SettingDefinition(key, group, type, defaultValue));
    }

    public static Map<String, SettingDefinition> getRegistry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public static Set<String> getAllowedGroups() {
        return ALLOWED_GROUPS;
    }

    public static Set<String> getSafeAppConfigKeys() {
        return SAFE_APP_CONFIG_KEYS;
    }
}
