package com.telecom.support.identity;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Login throttling without an account lock (design 01-foundation backend §4.1, NFR-SEC). All counters live in bounded,
 * expiring in-memory maps (single instance, OQ 3); time comes only from the {@code AppClock} bean.
 *
 * <ul>
 *   <li>L1 — client IP: 20 attempts per minute (success or failure, all usernames), a Bucket4j bucket.</li>
 *   <li>L2 — (IP, normalised username): 5 failures within 15 min block the pair for 15 min; success clears it.</li>
 *   <li>L3 — normalised username, all IPs: over 50 failures in a rolling hour reports an anomaly once per window;
 *       nothing is blocked.</li>
 * </ul>
 */
@Component
public class LoginRateLimiter {

    static final int IP_ATTEMPTS_PER_MINUTE = 20;
    static final int PAIR_MAX_FAILURES = 5;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(15);
    static final Duration PAIR_BLOCK = Duration.ofMinutes(15);
    static final int USER_ANOMALY_FAILURES = 50;
    static final Duration USER_WINDOW = Duration.ofHours(1);
    /** Upper bound per map; above it, idle entries are swept before a new key is added. */
    static final int MAX_KEYS = 100_000;

    /** A 429 decision: whole seconds until the caller may retry (at least 1). */
    public record Throttled(String limit, long retryAfterSeconds) {}

    private final Clock clock;
    private final TimeMeter timeMeter;
    private final Map<String, IpState> ips = new ConcurrentHashMap<>();
    private final Map<String, PairState> pairs = new ConcurrentHashMap<>();
    private final Map<String, UserState> usernames = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
        this.timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant now = clock.instant();
                return now.getEpochSecond() * 1_000_000_000L + now.getNano();
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    /** L1 then L2, before the credential check. Counts one attempt against the IP. Empty = allowed. */
    public Optional<Throttled> acquire(String clientIp, String username) {
        Instant now = clock.instant();
        IpState ip = state(ips, clientIp, () -> new IpState(newIpBucket()), s -> s.idleSince(now));
        ip.lastSeen = now;
        ConsumptionProbe probe = ip.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            return Optional.of(new Throttled("L1", seconds(probe.getNanosToWaitForRefill())));
        }
        PairState pair = pairs.get(pairKey(clientIp, username));
        if (pair != null) {
            synchronized (pair) {
                if (pair.blockedUntil != null && now.isBefore(pair.blockedUntil)) {
                    return Optional.of(new Throttled("L2",
                            seconds(Duration.between(now, pair.blockedUntil).toNanos())));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Counts a failed credential check for L2 and L3. Returns the username's failure count in the rolling hour when
     * this failure raises the L3 anomaly (once per window), else empty.
     */
    public OptionalInt recordFailure(String clientIp, String username) {
        Instant now = clock.instant();
        PairState pair = state(pairs, pairKey(clientIp, username), PairState::new, s -> s.idle(now));
        synchronized (pair) {
            prune(pair.failures, now.minus(PAIR_WINDOW));
            pair.failures.addLast(now);
            if (pair.failures.size() >= PAIR_MAX_FAILURES) {
                pair.blockedUntil = now.plus(PAIR_BLOCK);
                pair.failures.clear();
            }
        }
        UserState user = state(usernames, normalise(username), UserState::new, s -> s.idle(now));
        synchronized (user) {
            prune(user.failures, now.minus(USER_WINDOW));
            user.failures.addLast(now);
            boolean alertedInWindow = user.anomalyAt != null && now.isBefore(user.anomalyAt.plus(USER_WINDOW));
            if (user.failures.size() > USER_ANOMALY_FAILURES && !alertedInWindow) {
                user.anomalyAt = now;
                return OptionalInt.of(user.failures.size());
            }
        }
        return OptionalInt.empty();
    }

    /** A successful login clears the (IP, username) pair (L2). */
    public void recordSuccess(String clientIp, String username) {
        pairs.remove(pairKey(clientIp, username));
    }

    static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String pairKey(String clientIp, String username) {
        return clientIp + '\u0000' + normalise(username);
    }

    private Bucket newIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(IP_ATTEMPTS_PER_MINUTE)
                        .refillGreedy(IP_ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1)).build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    /** Gets or creates a key; when the map is full, idle entries are swept first so the map stays bounded. */
    private static <S> S state(Map<String, S> map, String key, Supplier<S> create,
                               Predicate<S> idle) {
        S existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        if (map.size() >= MAX_KEYS) {
            map.values().removeIf(idle);
        }
        return map.computeIfAbsent(key, k -> create.get());
    }

    private static void prune(Deque<Instant> failures, Instant cutoff) {
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) {
            failures.removeFirst();
        }
    }

    private static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    private static final class IpState {
        final Bucket bucket;
        volatile Instant lastSeen;

        IpState(Bucket bucket) {
            this.bucket = bucket;
        }

        /** A full bucket again after a minute without attempts. */
        boolean idleSince(Instant now) {
            return lastSeen == null || !now.isBefore(lastSeen.plus(Duration.ofMinutes(1)));
        }
    }

    private static final class PairState {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant blockedUntil;

        synchronized boolean idle(Instant now) {
            boolean blocked = blockedUntil != null && now.isBefore(blockedUntil);
            Instant last = failures.peekLast();
            return !blocked && (last == null || !now.isBefore(last.plus(PAIR_WINDOW)));
        }
    }

    private static final class UserState {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant anomalyAt;

        synchronized boolean idle(Instant now) {
            Instant last = failures.peekLast();
            return (last == null || !now.isBefore(last.plus(USER_WINDOW)))
                    && (anomalyAt == null || !now.isBefore(anomalyAt.plus(USER_WINDOW)));
        }
    }
}
