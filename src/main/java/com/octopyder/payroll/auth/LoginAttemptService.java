package com.octopyder.payroll.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> emailBuckets = new ConcurrentHashMap<>();

    private Bucket createBucket(int capacity, Duration duration) {
        Refill refill = Refill.intervally(capacity, duration);
        Bandwidth limit = Bandwidth.classic(capacity, refill);
        return Bucket.builder().addLimit(limit).build();
    }

    public boolean tryConsume(String ip, String email) {
        Bucket ipBucket = ipBuckets.computeIfAbsent(ip != null ? ip : "unknown", k -> createBucket(30, Duration.ofMinutes(1)));
        if (!ipBucket.tryConsume(1)) {
            return false;
        }
        if (email != null && !email.isBlank()) {
            Bucket emailBucket = emailBuckets.computeIfAbsent(email.toLowerCase(), k -> createBucket(10, Duration.ofMinutes(1)));
            return emailBucket.tryConsume(1);
        }
        return true;
    }
}
