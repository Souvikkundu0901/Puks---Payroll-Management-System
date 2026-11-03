package com.octopyder.payroll.audit;

import com.octopyder.payroll.audit.dto.AuditLogDto;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.common.PageResponse;
import com.octopyder.payroll.org.SystemSetting;
import com.octopyder.payroll.org.SystemSettingRepository;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.EmployeeProfileRepository;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final BusinessClock businessClock;
    private final boolean appTrustedProxy;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final SystemSettingRepository settingRepository;

    public AuditService(
        AuditLogRepository auditLogRepository,
        BusinessClock businessClock,
        @Value("${app.trusted-proxy:false}") boolean appTrustedProxy,
        UserRepository userRepository,
        EmployeeProfileRepository profileRepository,
        SystemSettingRepository settingRepository
    ) {
        this.auditLogRepository = auditLogRepository;
        this.businessClock = businessClock;
        this.appTrustedProxy = appTrustedProxy;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> getAuditLogs(
        UUID actorId,
        String action,
        String entityType,
        LocalDate from,
        LocalDate to,
        Pageable pageable
    ) {
        int pageNumber = Math.max(pageable.getPageNumber(), 0);
        int pageSize = Math.min(Math.max(pageable.getPageSize(), 1), 100);

        // Validate sort properties against allowlist
        Set<String> allowedSorts = Set.of("at", "action", "entityType", "actorRole");
        Sort sort = Sort.by("at").descending();
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedSorts.contains(order.getProperty())) {
                throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SORT_FIELD", "Sort property '" + order.getProperty() + "' is not permitted");
            }
            sort = Sort.by(order.getDirection(), order.getProperty());
        }

        String tz = settingRepository.findById("company.timezone")
            .map(SystemSetting::getValue)
            .map(v -> v.replace("\"", "").trim())
            .filter(v -> !v.isBlank())
            .orElse("Asia/Kolkata");
        ZoneId parsedZoneId;
        try {
            parsedZoneId = ZoneId.of(tz);
        } catch (Exception e) {
            parsedZoneId = ZoneId.of("Asia/Kolkata");
        }
        final ZoneId zoneId = parsedZoneId;

        Specification<AuditLog> spec = (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (actorId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("action"), action.trim()));
            }
            if (entityType != null && !entityType.isBlank()) {
                predicates = cb.and(predicates, cb.equal(root.get("entityType"), entityType.trim()));
            }
            if (from != null) {
                predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("at"), from.atStartOfDay(zoneId).toInstant()));
            }
            if (to != null) {
                predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("at"), to.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1)));
            }
            return predicates;
        };

        Page<AuditLog> page = auditLogRepository.findAll(spec, PageRequest.of(pageNumber, pageSize, sort));

        // Preload actors
        Set<UUID> actorIds = page.getContent().stream()
            .map(AuditLog::getActorId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

        Map<UUID, User> userMap = actorIds.isEmpty() ? Map.of() : userRepository.findAllById(actorIds).stream()
            .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, EmployeeProfile> profileMap = actorIds.isEmpty() ? Map.of() : profileRepository.findAllById(actorIds).stream()
            .collect(Collectors.toMap(EmployeeProfile::getUserId, p -> p));

        List<AuditLogDto> dtos = page.getContent().stream().map(log -> {
            String actorCode;
            String actorName;
            String actorRole;

            if (log.getActorId() == null) {
                actorCode = "SYSTEM";
                actorName = "System";
                actorRole = "SYSTEM";
            } else {
                User u = userMap.get(log.getActorId());
                EmployeeProfile p = profileMap.get(log.getActorId());
                actorCode = u != null ? u.getCode() : log.getActorId().toString();
                actorName = p != null && p.getFirstName() != null
                    ? p.getFirstName() + (p.getLastName() != null && !p.getLastName().isBlank() ? " " + p.getLastName() : "")
                    : (u != null ? u.getCode() : "Unknown");
                actorRole = log.getActorRole() != null ? log.getActorRole() : (u != null ? u.getRole().name() : "UNKNOWN");
            }

            String redactedBefore = redactAndTruncate(log.getBefore());
            String redactedAfter = redactAndTruncate(log.getAfter());

            return new AuditLogDto(
                log.getId(),
                log.getAt(),
                log.getActorId(),
                actorCode,
                actorName,
                actorRole,
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                redactedBefore,
                redactedAfter,
                log.getIp(),
                log.getUserAgent(),
                log.getRequestId()
            );
        }).toList();

        return PageResponse.of(dtos, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private String redactAndTruncate(String json) {
        if (json == null) return null;
        String redacted = json.replaceAll("(?i)\"(password|passwordHash|token|refreshToken|accountNumber|accountNumberEnc|aadhaar|pan|secret)\"\\s*:\\s*(\"[^\"]*\"|[^,}\\]\\s]+)", "\"$1\":\"[REDACTED]\"");
        if (redacted.length() > 2000) {
            return redacted.substring(0, 2000) + "... [TRUNCATED]";
        }
        return redacted;
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
                String firstIp = xff.split(",")[0].trim();
                if (firstIp.matches("^[a-fA-F0-9.:%]+$")) {
                    return firstIp;
                }
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
