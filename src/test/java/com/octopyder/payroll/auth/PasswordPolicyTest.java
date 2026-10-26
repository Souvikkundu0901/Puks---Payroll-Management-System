package com.octopyder.payroll.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private PasswordPolicy passwordPolicy;

    @BeforeEach
    void setUp() {
        passwordPolicy = new PasswordPolicy();
    }

    @Test
    void acceptsCompliantPassword() {
        assertThat(passwordPolicy.isValid("SecureP@ss123")).isTrue();
    }

    @Test
    void rejectsTooShortPassword() {
        assertThat(passwordPolicy.isValid("Sh0!rt")).isFalse();
    }

    @Test
    void rejectsPasswordWithoutUppercase() {
        assertThat(passwordPolicy.isValid("securep@ss123")).isFalse();
    }

    @Test
    void rejectsPasswordWithoutLowercase() {
        assertThat(passwordPolicy.isValid("SECUREP@SS123")).isFalse();
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        assertThat(passwordPolicy.isValid("SecureP@ssword")).isFalse();
    }

    @Test
    void rejectsPasswordWithoutSpecialChar() {
        assertThat(passwordPolicy.isValid("SecurePass123")).isFalse();
    }
}
