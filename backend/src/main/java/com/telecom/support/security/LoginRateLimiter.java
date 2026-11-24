package com.telecom.support.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Token bucket per client IP and per username to slow credential stuffing (NFR-SEC). In-memory is sufficient for
 * the single-instance monolith; account lockout in the DB covers multi-instance deployments.
 */
@Component
public class LoginRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int attemptsPerMinute;

    public LoginRateLimiter(@Value("${app.security.login-attempts-per-minute:10}") int attemptsPerMinute) {
        this.attemptsPerMinute = attemptsPerMinute;
    }

    public boolean tryAcquire(String clientIp, String username) {
        return bucket("ip:" + clientIp).tryConsume(1) & bucket("user:" + username.toLowerCase()).tryConsume(1);
    }

    private Bucket bucket(String key) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(attemptsPerMinute)
                        .refillGreedy(attemptsPerMinute, Duration.ofMinutes(1)).build())
                .build());
    }

    /** Drop idle buckets so the map cannot grow without bound. */
    @Scheduled(fixedDelay = 10, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void evictFullBuckets() {
        buckets.entrySet().removeIf(e -> e.getValue().getAvailableTokens() >= attemptsPerMinute);
    }
}
