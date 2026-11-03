package com.octopyder.payroll.mail;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@ConditionalOnProperty(name = "mail.enabled", havingValue = "true")
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final String appBaseUrl;

    // Global rate limit: 30 emails / minute
    private final Bucket globalBucket;
    // Per-recipient rate limit: 3 emails / minute with expiring entries
    private final Map<String, ExpiringBucket> recipientBuckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupTime = new AtomicLong(0);

    private static class ExpiringBucket {
        final Bucket bucket;
        volatile long lastAccessMillis;

        ExpiringBucket(Bucket bucket, long lastAccessMillis) {
            this.bucket = bucket;
            this.lastAccessMillis = lastAccessMillis;
        }
    }

    public SmtpMailService(
            JavaMailSender mailSender,
            @Value("${mail.from:noreply@octopyder.com}") String fromEmail,
            @Value("${app.base-url:http://localhost:8080}") String appBaseUrl
    ) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.appBaseUrl = appBaseUrl;
        this.globalBucket = Bucket.builder()
                .addLimit(Bandwidth.classic(30, Refill.intervally(30, Duration.ofMinutes(1))))
                .build();
    }

    @Override
    public boolean sendLoginCredentials(String toEmail, String temporaryPassword) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send login credentials: recipient email is empty");
            return false;
        }

        String cleanedEmail = toEmail.trim();

        // Defense-in-depth: check for prohibited control characters (CR, LF, null byte)
        if (cleanedEmail.indexOf('\r') != -1 || cleanedEmail.indexOf('\n') != -1 || cleanedEmail.indexOf('\0') != -1) {
            log.warn("Cannot send login credentials: email '{}' contains prohibited control characters", cleanedEmail);
            return false;
        }

        // Strict address validation
        try {
            InternetAddress address = new InternetAddress(cleanedEmail);
            address.validate();
            if (!cleanedEmail.contains("@") || cleanedEmail.indexOf('@') == 0 || cleanedEmail.indexOf('@') == cleanedEmail.length() - 1) {
                log.warn("Cannot send login credentials: email '{}' does not have valid format", cleanedEmail);
                return false;
            }
        } catch (Exception e) {
            log.warn("Cannot send login credentials: email '{}' failed strict address validation: {}", cleanedEmail, e.getMessage());
            return false;
        }

        // Rate limiting check
        if (!checkRateLimit(cleanedEmail.toLowerCase())) {
            log.warn("Rate limit exceeded for outgoing email to '{}' or globally", cleanedEmail);
            return false;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(cleanedEmail);
            message.setSubject("Your Payroll System Login Credentials");
            message.setText(buildEmailBody(cleanedEmail, temporaryPassword));

            mailSender.send(message);
            log.info("Login credentials email sent successfully to {}", cleanedEmail);
            return true;
        } catch (Exception e) {
            // NEVER log the temporary password!
            log.warn("Failed to send login credentials email to {}: {}", cleanedEmail, e.getMessage());
            return false;
        }
    }

    public String buildEmailBody(String email, String temporaryPassword) {
        return "Welcome to Payroll System!\n\n" +
                "Your account has been created or your password has been reset.\n\n" +
                "Login URL: " + appBaseUrl + "\n" +
                "Email: " + email + "\n" +
                "Temporary Password: " + temporaryPassword + "\n\n" +
                "Note: This temporary password expires in 7 days and must be changed at first login.\n";
    }

    private boolean checkRateLimit(String emailKey) {
        cleanExpiredBucketsIfNeeded();

        if (!globalBucket.tryConsume(1)) {
            return false;
        }

        long now = System.currentTimeMillis();
        ExpiringBucket recipientBucket = recipientBuckets.compute(emailKey, (k, existing) -> {
            if (existing == null) {
                Bucket b = Bucket.builder()
                        .addLimit(Bandwidth.classic(3, Refill.intervally(3, Duration.ofMinutes(1))))
                        .build();
                return new ExpiringBucket(b, now);
            }
            existing.lastAccessMillis = now;
            return existing;
        });

        return recipientBucket.bucket.tryConsume(1);
    }

    private void cleanExpiredBucketsIfNeeded() {
        if (recipientBuckets.size() > 100) {
            long now = System.currentTimeMillis();
            long last = lastCleanupTime.get();
            if (now - last > 60_000L && lastCleanupTime.compareAndSet(last, now)) {
                long cutoff = now - Duration.ofMinutes(5).toMillis();
                recipientBuckets.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis < cutoff);
            }
        }
    }
}
