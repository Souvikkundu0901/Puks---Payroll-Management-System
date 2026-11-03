package com.octopyder.payroll.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpMailServiceTest {

    @Test
    void testSendLoginCredentialsReturnsFalse() {
        NoOpMailService noOpMailService = new NoOpMailService();
        boolean result = noOpMailService.sendLoginCredentials("test@example.com", "Secret123!");
        assertThat(result).isFalse();
    }
}
