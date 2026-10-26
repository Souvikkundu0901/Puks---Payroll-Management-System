package com.octopyder.payroll.people.dto;

public record UserPreferenceDto(
    String theme,
    boolean notifyEmail,
    boolean notifyPush,
    boolean notifyPayroll
) {
}
