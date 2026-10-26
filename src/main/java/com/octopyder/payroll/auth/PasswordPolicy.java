package com.octopyder.payroll.auth;

import com.octopyder.payroll.org.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PasswordPolicy {

    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

    private int minLength = 8;
    private final SettingsService settingsService;

    public PasswordPolicy() {
        this.settingsService = null;
    }

    @Autowired
    public PasswordPolicy(@Autowired(required = false) SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public PasswordPolicy(int minLength) {
        this.minLength = minLength;
        this.settingsService = null;
    }

    public boolean isValid(String password) {
        int effectiveMinLength = settingsService != null ? settingsService.getInt("security.passwordMinLength", minLength) : minLength;
        if (password == null || password.length() < effectiveMinLength) {
            return false;
        }
        boolean hasUpper = UPPERCASE_PATTERN.matcher(password).find();
        boolean hasLower = LOWERCASE_PATTERN.matcher(password).find();
        boolean hasDigit = DIGIT_PATTERN.matcher(password).find();
        boolean hasSpecial = SPECIAL_PATTERN.matcher(password).find();

        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    public int getMinLength() {
        return settingsService != null ? settingsService.getInt("security.passwordMinLength", minLength) : minLength;
    }

    public void setMinLength(int minLength) {
        this.minLength = minLength;
    }
}
