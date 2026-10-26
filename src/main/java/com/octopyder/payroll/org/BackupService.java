package com.octopyder.payroll.org;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.dto.BackupRunDto;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class BackupService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("RUNNING", "SUCCESS", "FAILED");

    private final BackupRunRepository repository;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final String configuredSecret;
    private final Bucket rateLimiter;

    public BackupService(
        BackupRunRepository repository,
        AuditService auditService,
        BusinessClock businessClock,
        @Value("${BACKUP_WEBHOOK_SECRET:}") String configuredSecret
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.businessClock = businessClock;
        this.configuredSecret = configuredSecret != null ? configuredSecret.trim() : "";
        this.rateLimiter = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build())
            .build();
    }

    public List<BackupRunDto> list() {
        return repository.findAllByOrderByStartedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional
    public BackupRunDto handleWebhook(String secretHeader, BackupRunDto.WebhookPayload payload) {
        // 1. Rate limiting
        if (!rateLimiter.tryConsume(1)) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many backup webhook calls");
        }

        // 2. Fail closed: check if secret is configured
        String secretToMatch = getEffectiveSecret();
        if (secretToMatch.isEmpty()) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Backup webhook secret not configured on server");
        }

        // 3. Extract and validate incoming secret
        if (secretHeader == null || secretHeader.isBlank()) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing backup webhook secret");
        }

        String providedSecret = secretHeader.trim();
        if (providedSecret.startsWith("Bearer ") || providedSecret.startsWith("bearer ")) {
            providedSecret = providedSecret.substring(7).trim();
        }

        // Constant-time comparison
        boolean match = MessageDigest.isEqual(
            secretToMatch.getBytes(StandardCharsets.UTF_8),
            providedSecret.getBytes(StandardCharsets.UTF_8)
        );

        if (!match) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid backup webhook secret");
        }

        // 4. Validate payload contents to prevent 500 DB constraint errors
        if (payload.startedAt() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TIMESTAMP", "startedAt timestamp is required");
        }
        if (payload.status() == null || !ALLOWED_STATUSES.contains(payload.status().trim().toUpperCase())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status must be RUNNING, SUCCESS, or FAILED");
        }
        if (payload.sizeBytes() != null && payload.sizeBytes() < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SIZE", "SizeBytes cannot be negative");
        }
        if (payload.location() != null && payload.location().length() > 255) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "Location cannot exceed 255 characters");
        }
        String triggeredBy = payload.triggeredBy() != null ? payload.triggeredBy().trim() : "GITHUB_ACTION";
        if (triggeredBy.length() > 64) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TRIGGERED_BY", "TriggeredBy cannot exceed 64 characters");
        }

        // 5. Save backup run
        BackupRun run = new BackupRun(
            payload.startedAt(),
            payload.finishedAt(),
            payload.sizeBytes(),
            payload.status().trim().toUpperCase(),
            payload.location(),
            triggeredBy
        );
        run.setCreatedAt(businessClock.instant());

        BackupRun saved = repository.save(run);
        auditService.log("BACKUP_RECORDED", "BACKUP", saved.getId().toString(), null, "{\"status\":\"" + saved.getStatus() + "\"}");

        return toDto(saved);
    }

    private String getEffectiveSecret() {
        if (!configuredSecret.isEmpty()) {
            return configuredSecret;
        }
        String sysProp = System.getProperty("BACKUP_WEBHOOK_SECRET");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String env = System.getenv("BACKUP_WEBHOOK_SECRET");
        return env != null ? env.trim() : "";
    }

    private BackupRunDto toDto(BackupRun b) {
        return new BackupRunDto(
            b.getId(),
            b.getStartedAt(),
            b.getFinishedAt(),
            b.getSizeBytes(),
            b.getStatus(),
            b.getLocation(),
            b.getTriggeredBy(),
            b.getCreatedAt()
        );
    }
}
