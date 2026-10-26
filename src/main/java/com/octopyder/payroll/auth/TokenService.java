package com.octopyder.payroll.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.SettingsService;
import com.octopyder.payroll.people.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenService {

    private final String jwtSecret;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BusinessClock businessClock;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired(required = false)
    private SettingsService settingsService;

    public TokenService(
        @Value("${jwt.secret:4b0c79d38c645b23d9a19c4b5f8e21a73d9e8b7c6a5d4e3f2a1b0c9d8e7f6a5b}") String jwtSecret,
        RefreshTokenRepository refreshTokenRepository,
        BusinessClock businessClock,
        AuditService auditService
    ) {
        this.jwtSecret = jwtSecret;
        this.refreshTokenRepository = refreshTokenRepository;
        this.businessClock = businessClock;
        this.auditService = auditService;
    }

    public String generateAccessToken(User user) {
        try {
            Instant now = businessClock.instant();
            int timeoutMinutes = settingsService != null ? settingsService.getInt("security.sessionTimeout", 15) : 15;
            Instant expiry = now.plus(Duration.ofMinutes(timeoutMinutes));

            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("code", user.getCode())
                .claim("tv", user.getTokenVersion())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

            JWSSigner signer = new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "TOKEN_ERROR", "Could not sign access token", e);
        }
    }

    public JWTClaimsSet verifyAccessToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(jwtSecret.getBytes(StandardCharsets.UTF_8));
            if (!signedJWT.verify(verifier)) {
                throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid token signature");
            }
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Instant now = businessClock.instant();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(Date.from(now))) {
                throw new AppException(HttpStatus.UNAUTHORIZED, "EXPIRED_TOKEN", "Token has expired");
            }
            return claims;
        } catch (AppException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Failed to parse or verify token", e);
        }
    }

    @Transactional
    public String createRefreshToken(UUID userId, boolean rememberMe, String userAgent, String ip) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = HexFormat.of().formatHex(randomBytes);
        String tokenHash = hashToken(rawToken);

        Instant now = businessClock.instant();
        Instant expiresAt = now.plus(rememberMe ? Duration.ofDays(30) : Duration.ofDays(7));
        UUID familyId = UUID.randomUUID();

        RefreshToken refreshToken = new RefreshToken(userId, tokenHash, familyId, expiresAt, userAgent, ip);
        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    @Transactional
    public RotationResult rotateRefreshToken(String rawToken, String userAgent, String ip) {
        String tokenHash = hashToken(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid refresh token"));

        Instant now = businessClock.instant();

        // Check if token was already revoked
        if (token.getRevokedAt() != null) {
            Duration timeSinceRevocation = Duration.between(token.getRevokedAt(), now);
            if (timeSinceRevocation.toSeconds() <= 20) {
                // Inside 20s grace window for parallel requests
                return new RotationResult(token.getUserId(), rawToken, false);
            } else {
                // Reuse detected after grace window: revoke entire family per BR-S5 / ARCHITECTURE §4
                refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
                auditService.log("REFRESH_TOKEN_REUSE_DETECTED", "REFRESH_TOKEN", token.getUserId().toString(), null, "Family revoked");
                throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Refresh token reuse detected. Session invalidated.");
            }
        }

        if (token.getExpiresAt().isBefore(now)) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Refresh token expired");
        }

        // Generate new raw refresh token
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String newRawToken = HexFormat.of().formatHex(randomBytes);
        String newTokenHash = hashToken(newRawToken);

        // Mark old token revoked and record replacement
        token.setRevokedAt(now);
        token.setReplacedBy(newTokenHash);
        refreshTokenRepository.save(token);

        // Save new token in same family
        RefreshToken newToken = new RefreshToken(
            token.getUserId(),
            newTokenHash,
            token.getFamilyId(),
            now.plus(Duration.ofDays(7)),
            userAgent,
            ip
        );
        refreshTokenRepository.save(newToken);

        return new RotationResult(token.getUserId(), newRawToken, true);
    }

    @Transactional
    public void revokeToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = hashToken(rawToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevokedAt(businessClock.instant());
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, businessClock.instant());
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record RotationResult(UUID userId, String refreshToken, boolean isNew) {}
}
