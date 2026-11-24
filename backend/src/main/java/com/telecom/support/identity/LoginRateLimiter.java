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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Login throttling without an account lock (design 01-foundation backend §4.1, NFR-SEC). All counters live in bounded,
 * expiring in-memory maps (single instance, OQ 3); time comes only from the {@code AppClock} bean. The client IP key is
 * always {@code ClientIp.resolve(request)} (§4.4): the remote address, or the forwarded client only behind a trusted proxy.
 *
 * <p>The limits come from {@link LoginLimitProperties} ({@code app.security.login.*}, §12 E2.3); the defaults are:
 * <ul>
 *   <li>L1 — client IP: 20 attempts per minute (success or failure, all usernames), a Bucket4j bucket.</li>
 *   <li>L2 — (IP, normalised username): 5 failures within 15 min block the pair for 15 min; success clears it.</li>
 *   <li>L3 — normalised username, all IPs: over 50 failures in a rolling hour reports an anomaly once per window;
 *       nothing is blocked.</li>
 * </ul>
 *
 * <p>Each map holds at most {@code maxKeys} keys. When a new key arrives at capacity, idle entries are swept (at most
 * once per {@link #SWEEP_INTERVAL}); if the map is still full, the new key is not added and the attempt is throttled
 * (429, limit {@code capacity}, logged at WARN). Existing keys keep their counters, so the limits are never weakened.
 */
@Component
@EnableConfigurationProperties(LoginLimitProperties.class)
public class LoginRateLimiter {

    /** Default L1 limit (tests); the running value is {@link LoginLimitProperties#ipAttemptsPerMinute()}. */
    static final int IP_ATTEMPTS_PER_MINUTE = LoginLimitProperties.DEFAULT_IP_ATTEMPTS_PER_MINUTE;
    /** Default L2 limit (tests); the running value is {@link LoginLimitProperties#pairMaxFailures()}. */
    static final int PAIR_MAX_FAILURES = LoginLimitProperties.DEFAULT_PAIR_MAX_FAILURES;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(15);
    static final Duration USER_WINDOW = Duration.ofHours(1);
    /** Upper bound per map; at it, idle entries are swept, and a new key that still does not fit is throttled. */
    static final int MAX_KEYS = 100_000;
    static final String CAPACITY = "capacity";
    static final Duration CAPACITY_RETRY_AFTER = Duration.ofMinutes(1);
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** A 429 decision: whole seconds until the caller may retry (at least 1). */
    public record Throttled(String limit, long retryAfterSeconds) {}

    private final Clock clock;
    private final int ipAttemptsPerMinute;
    private final int pairMaxFailures;
    private final Duration pairBlock;
    private final int usernameAnomalyFailures;
    private final TimeMeter timeMeter;
    private final BoundedMap<IpState> ips;
    private final BoundedMap<PairState> pairs;
    private final BoundedMap<UserState> usernames;

    @Autowired
    public LoginRateLimiter(Clock clock, LoginLimitProperties limits) {
        this(clock, limits, MAX_KEYS);
    }

    /** The §4.1 default limits. */
    public LoginRateLimiter(Clock clock) {
        this(clock, LoginLimitProperties.DEFAULTS);
    }

    LoginRateLimiter(Clock clock, int maxKeys) {
        this(clock, LoginLimitProperties.DEFAULTS, maxKeys);
    }

    LoginRateLimiter(Clock clock, LoginLimitProperties limits, int maxKeys) {
        this.clock = clock;
        this.ipAttemptsPerMinute = limits.ipAttemptsPerMinute();
        this.pairMaxFailures = limits.pairMaxFailures();
        this.pairBlock = limits.pairBlock();
        this.usernameAnomalyFailures = limits.usernameAnomalyFailuresPerHour();
        this.ips = new BoundedMap<>("ips", maxKeys);
        this.pairs = new BoundedMap<>("pairs", maxKeys);
        this.usernames = new BoundedMap<>("usernames", maxKeys);
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
        IpState ip = ips.getOrCreate(clientIp, () -> new IpState(newIpBucket()), s -> s.idleSince(now), now);
        if (ip == null) {
            return Optional.of(capacityThrottle());
        }
        ip.lastSeen = now;
        ConsumptionProbe probe = ip.bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            return Optional.of(new Throttled("L1", seconds(probe.getNanosToWaitForRefill())));
        }
        PairState pair = pairs.get(pairKey(clientIp, username));
        // a failure would need new pair / username entries: refuse the attempt now if they could not be tracked
        if ((pair == null && !pairs.hasRoom(s -> s.idle(now), now))
                || (usernames.get(normalise(username)) == null && !usernames.hasRoom(s -> s.idle(now), now))) {
            return Optional.of(capacityThrottle());
        }
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
        PairState pair = pairs.getOrCreate(pairKey(clientIp, username), PairState::new, s -> s.idle(now), now);
        if (pair != null) {
            synchronized (pair) {
                prune(pair.failures, now.minus(PAIR_WINDOW));
                pair.failures.addLast(now);
                if (pair.failures.size() >= pairMaxFailures) {
                    pair.blockedUntil = now.plus(pairBlock);
                    pair.failures.clear();
                }
            }
        }
        UserState user = usernames.getOrCreate(normalise(username), UserState::new, s -> s.idle(now), now);
        if (user == null) {
            return OptionalInt.empty();
        }
        synchronized (user) {
            prune(user.failures, now.minus(USER_WINDOW));
            user.failures.addLast(now);
            boolean alertedInWindow = user.anomalyAt != null && now.isBefore(user.anomalyAt.plus(USER_WINDOW));
            if (user.failures.size() > usernameAnomalyFailures && !alertedInWindow) {
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
                .addLimit(Bandwidth.builder().capacity(ipAttemptsPerMinute)
                        .refillGreedy(ipAttemptsPerMinute, Duration.ofMinutes(1)).build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    /** Keys held per map: ips, pairs, usernames (each bounded by {@code maxKeys}); for tests. */
    int[] trackedKeys() {
        return new int[] {ips.size(), pairs.size(), usernames.size()};
    }

    private static Throttled capacityThrottle() {
        return new Throttled(CAPACITY, CAPACITY_RETRY_AFTER.toSeconds());
    }

    /**
     * A map that never holds more than {@code maxKeys} keys. Lookups of existing keys are lock-free; adding a key is
     * serialised, sweeps idle entries at capacity (at most once per {@link #SWEEP_INTERVAL}) and refuses the key if the
     * map is still full.
     */
    private static final class BoundedMap<S> {
        private final String name;
        private final int maxKeys;
        private final Map<String, S> map = new ConcurrentHashMap<>();
        private Instant nextSweep = Instant.MIN;
        private Instant nextWarning = Instant.MIN;

        BoundedMap(String name, int maxKeys) {
            this.name = name;
            this.maxKeys = maxKeys;
        }

        S get(String key) {
            return map.get(key);
        }

        int size() {
            return map.size();
        }

        void remove(String key) {
            map.remove(key);
        }

        /** True when a new key could be added now (sweeping idle entries if the map is full). */
        synchronized boolean hasRoom(Predicate<S> idle, Instant now) {
            return makeRoom(idle, now);
        }

        /** The existing state, a new one, or null when the map is full of live entries. */
        S getOrCreate(String key, Supplier<S> create, Predicate<S> idle, Instant now) {
            S existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            synchronized (this) {
                existing = map.get(key);
                if (existing != null) {
                    return existing;
                }
                if (!makeRoom(idle, now)) {
                    return null;
                }
                S created = create.get();
                map.put(key, created);
                return created;
            }
        }

        private boolean makeRoom(Predicate<S> idle, Instant now) {
            if (map.size() < maxKeys) {
                return true;
            }
            if (!now.isBefore(nextSweep)) {
                nextSweep = now.plus(SWEEP_INTERVAL);
                map.values().removeIf(idle);
            }
            if (map.size() < maxKeys) {
                return true;
            }
            if (!now.isBefore(nextWarning)) {
                nextWarning = now.plus(Duration.ofMinutes(1));
                log.warn("Login limiter map '{}' is full ({} live keys); new keys are throttled", name, maxKeys);
            }
            return false;
        }
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
