package com.octopyder.payroll.audit;

import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final BusinessClock businessClock;
    private final boolean appTrustedProxy;

    public AuditService(
        AuditLogRepository auditLogRepository,
        BusinessClock businessClock,
        @Value("${app.trusted-proxy:false}") boolean appTrustedProxy
    ) {
        this.auditLogRepository = auditLogRepository;
        this.businessClock = businessClock;
        this.appTrustedProxy = appTrustedProxy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String entityType, String entityId, String beforeJson, String afterJson) {
        AuditLog log = new AuditLog();
        log.setAt(businessClock.instant());
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setBefore(sanitize(beforeJson));
        log.setAfter(sanitize(afterJson));

        // Capture actor if authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser currentUser) {
            log.setActorId(currentUser.id());
            log.setActorRole(currentUser.role().name());
        }

        // Capture HTTP metadata if present
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            log.setIp(truncate(getClientIp(request), 45));
            log.setUserAgent(request.getHeader("User-Agent"));
            log.setRequestId(truncate(request.getHeader("X-Request-Id"), 64));
        }

        auditLogRepository.save(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logWithActor(UUID actorId, String actorRole, String action, String entityType, String entityId, String beforeJson, String afterJson, HttpServletRequest request) {
        AuditLog log = new AuditLog();
        log.setAt(businessClock.instant());
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setBefore(sanitize(beforeJson));
        log.setAfter(sanitize(afterJson));

        if (request != null) {
            log.setIp(truncate(getClientIp(request), 45));
            log.setUserAgent(request.getHeader("User-Agent"));
            log.setRequestId(truncate(request.getHeader("X-Request-Id"), 64));
        }

        auditLogRepository.save(log);
    }

    private String getClientIp(HttpServletRequest request) {
        if (appTrustedProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String truncate(String val, int maxLen) {
        if (val == null) {
            return null;
        }
        String trimmed = val.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    private String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        String json;
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            json = trimmed;
        } else {
            String escaped = trimmed
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            json = "{\"message\":\"" + escaped + "\"}";
        }
        // Redaction list per BR-S6: passwords, hashes, tokens, bank numbers
        return json.replaceAll("(?i)\"(password|passwordHash|token|refreshToken|accountNumber|accountNumberEnc)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"[REDACTED]\"");
    }
}
