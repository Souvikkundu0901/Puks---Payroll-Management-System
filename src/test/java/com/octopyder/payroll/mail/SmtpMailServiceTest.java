package com.octopyder.payroll.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SmtpMailServiceTest {

    private JavaMailSender mailSender;
    private SmtpMailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailService = new SmtpMailService(
                mailSender,
                "noreply@octopyder.com",
                "http://localhost:8080"
        );
    }

    @Test
    void testSendLoginCredentialsSuccess() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = mailService.sendLoginCredentials("user@example.com", "TempSecret123!");

        assertThat(result).isTrue();

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("noreply@octopyder.com");
        assertThat(sent.getTo()).containsExactly("user@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your Payroll System Login Credentials");
        assertThat(sent.getText())
                .contains("user@example.com")
                .contains("TempSecret123!")
                .contains("http://localhost:8080")
                .contains("expires in 7 days and must be changed at first login");
    }

    @Test
    void testSendLoginCredentialsRateLimitPerRecipient() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // 3 allowed per minute per recipient
        assertThat(mailService.sendLoginCredentials("alice@example.com", "Pass1!")).isTrue();
        assertThat(mailService.sendLoginCredentials("alice@example.com", "Pass2!")).isTrue();
        assertThat(mailService.sendLoginCredentials("alice@example.com", "Pass3!")).isTrue();

        // 4th to same recipient must be rejected by rate limiter
        assertThat(mailService.sendLoginCredentials("alice@example.com", "Pass4!")).isFalse();

        // But different recipient still has quota
        assertThat(mailService.sendLoginCredentials("bob@example.com", "PassBob!")).isTrue();

        verify(mailSender, times(4)).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendLoginCredentialsRateLimitGlobal() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // 30 total global limit
        for (int i = 1; i <= 30; i++) {
            boolean sent = mailService.sendLoginCredentials("user" + i + "@example.com", "Pass" + i + "!");
            assertThat(sent).isTrue();
        }

        // 31st call must hit global rate limit
        boolean rejected = mailService.sendLoginCredentials("user31@example.com", "Pass31!");
        assertThat(rejected).isFalse();

        verify(mailSender, times(30)).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendLoginCredentialsInvalidEmail() {
        assertThat(mailService.sendLoginCredentials(null, "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("   ", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("invalid-email", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("@domain.com", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("user@", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("user@example.com\r\nBcc:hacker@evil.com", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("user@example.com\nSubject:Injected", "Pass123!")).isFalse();
        assertThat(mailService.sendLoginCredentials("user\0@example.com", "Pass123!")).isFalse();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendLoginCredentialsSenderException() {
        doThrow(new MailSendException("SMTP connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = mailService.sendLoginCredentials("fail@example.com", "Pass123!");

        // Must return false gracefully without leaking or rethrowing
        assertThat(result).isFalse();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
