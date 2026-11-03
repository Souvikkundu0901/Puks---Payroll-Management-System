package com.octopyder.payroll.auth;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.auth.dto.ChangePasswordRequest;
import com.octopyder.payroll.auth.dto.LoginRequest;
import com.octopyder.payroll.auth.dto.LoginResponse;
import com.octopyder.payroll.auth.dto.UserDto;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService implements ApplicationRunner {

    // Dummy hash for constant-time comparison on non-existent users (BR-S9)
    private static final String DUMMY_BCRYPT_HASH = "$2a$12$e68Y2T3fTdQ6zZk89yNfce7yq9Yp.sO1wzU/oZ52V8bO0aQ3JqUu2";
    private static final String REFRESH_COOKIE_NAME = "refreshToken";
    private static final String AUTH_COOKIE_PATH = "/api/v1/auth";

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordPolicy passwordPolicy;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    @Autowired(required = false)
    private SettingsService settingsService;

    @Value("${ADMIN_BOOTSTRAP_EMAIL:admin@octopyder.com}")
    private String adminBootstrapEmail;

    @Value("${ADMIN_BOOTSTRAP_PASSWORD:AdminBootstrap123!}")
    private String adminBootstrapPassword;

    @Value("${app.trusted-proxy:false}")
    private boolean appTrustedProxy;

    public AuthService(
        UserRepository userRepository,
        TokenService tokenService,
        PasswordPolicy passwordPolicy,
        LoginAttemptService loginAttemptService,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.passwordPolicy = passwordPolicy;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Bootstrap one ADMIN on first start if not already created
        if (adminBootstrapEmail != null && !adminBootstrapEmail.isBlank()) {
            Optional<User> existing = userRepository.findByEmailIgnoreCase(adminBootstrapEmail);
            if (existing.isEmpty() && userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) == 0) {
                User admin = new User();
                admin.setCode("ADM001");
                admin.setEmail(adminBootstrapEmail.toLowerCase().trim());
                admin.setPasswordHash(passwordEncoder.encode(adminBootstrapPassword));
                admin.setRole(Role.ADMIN);
                admin.setStatus(UserStatus.ACTIVE);
                admin.setMustChangePassword(true);
                admin.setTokenVersion(1);
                userRepository.save(admin);
                auditService.log("BOOTSTRAP_ADMIN", "USER", admin.getId().toString(), null, "Initial admin bootstrapped");
            }
        }
    }

    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);
        String email = request.email() != null ? request.email().trim().toLowerCase() : "";

        // 1. Rate Limiting via Bucket4j
        if (!loginAttemptService.tryConsume(clientIp, email)) {
            auditService.log("RATE_LIMITED", "AUTH", email, null, "Rate limit exceeded on IP: " + clientIp);
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many login attempts. Please try again later.");
        }

        Instant now = businessClock.instant();
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);

        // Constant-time check for non-existent users (BR-S9)
        if (userOpt.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            auditService.log("LOGIN_FAILED", "AUTH", email, null, "Unknown user attempt");
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid credentials or account temporarily locked");
        }

        User user = userOpt.get();

        // 2. Account lockout check (BR-S1, BR-S9)
        if (user.getLockedUntil() != null) {
            if (user.getLockedUntil().isAfter(now)) {
                auditService.log("LOGIN_FAILED", "AUTH", user.getId().toString(), null, "Account currently locked until " + user.getLockedUntil());
                throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid credentials or account temporarily locked");
            } else {
                // Lockout period has elapsed: reset failed attempts counter
                user.setLockedUntil(null);
                user.setFailedAttempts(0);
            }
        }

        if (user.getStatus() != UserStatus.ACTIVE || user.getDeletedAt() != null) {
            auditService.log("LOGIN_FAILED", "AUTH", user.getId().toString(), null, "User inactive or deleted");
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid credentials or account temporarily locked");
        }

        // Temporary password expiration (7-day expiry per BR-S8)
        if (user.isMustChangePassword() && user.getTemporaryPasswordExpiresAt() != null && user.getTemporaryPasswordExpiresAt().isBefore(now)) {
            auditService.log("LOGIN_FAILED", "AUTH", user.getId().toString(), null, "Temporary password expired");
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_TEMP_PASSWORD_EXPIRED", "Temporary password has expired. Please contact an administrator.");
        }

        // 3. Password verification
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int newFailed = user.getFailedAttempts() + 1;
            user.setFailedAttempts(newFailed);
            int maxAttempts = settingsService != null ? settingsService.getInt("security.maxLoginAttempts", 5) : 5;
            int lockMinutes = settingsService != null ? settingsService.getInt("security.autoLockMinutes", 15) : 15;
            if (newFailed >= maxAttempts) {
                user.setLockedUntil(now.plus(Duration.ofMinutes(lockMinutes)));
                auditService.log("ACCOUNT_LOCKED", "USER", user.getId().toString(), null, "Locked after " + newFailed + " failed attempts");
            }
            userRepository.save(user);
            auditService.log("LOGIN_FAILED", "AUTH", user.getId().toString(), null, "Incorrect password, failed attempts: " + newFailed);
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid credentials or account temporarily locked");
        }

        // Login success: reset lockout counters
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        // Issue tokens
        String accessToken = tokenService.generateAccessToken(user);
        String rawRefreshToken = tokenService.createRefreshToken(
            user.getId(),
            request.isRememberMe(),
            httpRequest.getHeader("User-Agent"),
            clientIp
        );

        setRefreshTokenCookie(httpResponse, rawRefreshToken, request.isRememberMe(), httpRequest.isSecure());
        auditService.logWithActor(user.getId(), user.getRole().name(), "LOGIN_SUCCESS", "AUTH", user.getId().toString(), null, null, httpRequest);

        return new LoginResponse(accessToken, 900, toDto(user));
    }

    @Transactional
    public LoginResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawRefreshToken = getRefreshTokenFromCookie(httpRequest)
            .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Refresh token missing"));

        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        TokenService.RotationResult result = tokenService.rotateRefreshToken(rawRefreshToken, userAgent, clientIp);
        User user = userRepository.findById(result.userId())
            .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "User not found"));

        if (user.getStatus() != UserStatus.ACTIVE || user.getDeletedAt() != null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "User inactive or offboarded");
        }

        String accessToken = tokenService.generateAccessToken(user);
        if (result.isNew()) {
            setRefreshTokenCookie(httpResponse, result.refreshToken(), false, httpRequest.isSecure());
        }

        return new LoginResponse(accessToken, 900, toDto(user));
    }

    @Transactional
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        getRefreshTokenFromCookie(httpRequest).ifPresent(tokenService::revokeToken);
        clearRefreshTokenCookie(httpResponse, httpRequest.isSecure());
        auditService.log("LOGOUT", "AUTH", null, null, "User logged out");
    }

    @Transactional(readOnly = true)
    public UserDto getMe(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
        return toDto(user);
    }

    @Transactional
    public void changePassword(CurrentUser currentUser, ChangePasswordRequest request) {
        User user = userRepository.findById(currentUser.id())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "AUTH_INVALID_CREDENTIALS", "Current password does not match");
        }

        if (!passwordPolicy.isValid(request.newPassword())) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH_PASSWORD_POLICY", "Password does not meet complexity requirements");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_POLICY", "New password cannot be identical to current password");
        }

        Instant now = businessClock.instant();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(now);
        // Increment token version to invalidate all outstanding access tokens (BR-S5)
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        // Invalidate all refresh tokens for user
        tokenService.revokeAllForUser(user.getId());
        auditService.log("PASSWORD_CHANGE", "USER", user.getId().toString(), null, "Password successfully changed");
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String token, boolean rememberMe, boolean isSecure) {
        Duration maxAge = rememberMe ? Duration.ofDays(30) : Duration.ofDays(7);
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(isSecure)
            .sameSite("Lax")
            .path(AUTH_COOKIE_PATH)
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response, boolean isSecure) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(isSecure)
            .sameSite("Lax")
            .path(AUTH_COOKIE_PATH)
            .maxAge(0)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Optional<String> getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
            .filter(c -> REFRESH_COOKIE_NAME.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst();
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

    private UserDto toDto(User user) {
        String name = user.getCode(); // Default name until employee profile is attached in Phase 2
        return new UserDto(
            user.getId(),
            user.getCode(),
            name,
            user.getEmail(),
            user.getRole(),
            "Engineering", // Default until Phase 2
            null,
            user.isMustChangePassword(),
            "default"
        );
    }
}
