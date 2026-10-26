package com.octopyder.payroll.people.dto;

public record UpdateProfileRequest(
    String phone,
    String address,
    String emergencyContact,
    String maritalStatus,
    String[] skills
) {
}
