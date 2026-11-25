package com.telecom.support.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-client-IP limits of the public webhook E18 (design 02-customer backend §6, §7, §8; NFR-SEC):
 * <ul>
 *   <li>{@link #acquire}: at most {@code app.payment.webhook.ip-requests-per-minute} (default 60) calls per IP in a
 *       one-minute window that starts at the IP's first call; the next call is refused (429) until the window ends.
 *       Checked first, before the body is read.</li>
 *   <li>{@link #sampleRejection}: {@code PAYMENT_WEBHOOK_REJECTED} for {@code bad_signature}/{@code stale} is written
 *       (and WARN-logged) at most once per IP per minute, so a flood of forged calls cannot flood the audit log.</li>
 * </ul>
 * State is in memory (single instance, as the login limiter); time comes only from the {@code AppClock} bean. Each map
 * holds at most {@code maxKeys} IPs: when full, expired entries are swept; if it is still full, a new IP is refused
 * (limit) or not sampled, so the limits are never weakened.
 */
@Component
class WebhookRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int MAX_KEYS = 100_000;

    private static final Logger log = LoggerFactory.getLogger(WebhookRateLimiter.class);

    private record Window(Instant start, int count) {}

    private final Clock clock;
    private final int perMinute;
    private final int maxKeys;
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Instant> sampled = new HashMap<>();

    @Autowired
    WebhookRateLimiter(Clock clock, PaymentGatewayProperties properties) {
        this(clock, properties.webhook().ipRequestsPerMinute(), MAX_KEYS);
    }

    WebhookRateLimiter(Clock clock, int perMinute, int maxKeys) {
        this.clock = clock;
        this.perMinute = perMinute;
        this.maxKeys = maxKeys;
    }

    /**
     * Counts one call from {@code clientIp}.
     *
     * @return empty when allowed; otherwise the whole seconds (at least 1) until the IP may call again
     */
    synchronized OptionalLong acquire(String clientIp) {
        Instant now = clock.instant();
        Window window = windows.get(clientIp);
        if (window == null || !now.isBefore(window.start().plus(WINDOW))) {
            if (window == null && !hasRoom(windows, now)) {
                log.warn("Webhook limiter full ({} IPs); refusing a call from a new IP", windows.size());
                return OptionalLong.of(WINDOW.toSeconds());
            }
            windows.put(clientIp, new Window(now, 1));
            return OptionalLong.empty();
        }
        if (window.count() >= perMinute) {
            long millis = Duration.between(now, window.start().plus(WINDOW)).toMillis();
            return OptionalLong.of(Math.max(1, (millis + 999) / 1000));
        }
        windows.put(clientIp, new Window(window.start(), window.count() + 1));
        return OptionalLong.empty();
    }

    /** True for the first signature rejection of {@code clientIp} in a minute: audit and log it; false: skip. */
    synchronized boolean sampleRejection(String clientIp) {
        Instant now = clock.instant();
        Instant last = sampled.get(clientIp);
        if (last != null && now.isBefore(last.plus(WINDOW))) {
            return false;
        }
        if (last == null && !hasRoom(sampled, now)) {
            return false;
        }
        sampled.put(clientIp, now);
        return true;
    }

    /** Forgets every IP (tests). */
    synchronized void reset() {
        windows.clear();
        sampled.clear();
    }

    /** Room for one more key, after sweeping entries whose minute has passed when the map is full. */
    private boolean hasRoom(Map<String, ?> map, Instant now) {
        if (map.size() < maxKeys) {
            return true;
        }
        map.values().removeIf(v -> {
            Instant start = v instanceof Window w ? w.start() : (Instant) v;
            return !now.isBefore(start.plus(WINDOW));
        });
        return map.size() < maxKeys;
    }
}
