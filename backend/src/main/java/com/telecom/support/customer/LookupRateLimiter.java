package com.telecom.support.customer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Customer search throttle (design 03-inbound backend R1; INB-01.4, NFR-SEC): at most {@code app.inbound.searches-per-minute}
 * searches per agent in any rolling minute, in memory per instance (OQ 15). Time comes only from the {@code AppClock}
 * bean. The map is keyed by staff user id (a bounded set); idle agents are swept when it grows past {@link #SWEEP_AT}.
 * Every update of one agent's window is atomic ({@link ConcurrentHashMap#compute}), so parallel searches cannot pass
 * the limit together.
 */
@Component
@EnableConfigurationProperties(InboundProperties.class)
class LookupRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    static final int SWEEP_AT = 10_000;

    private final Clock clock;
    private final int limit;
    private final Map<Long, Deque<Instant>> searches = new ConcurrentHashMap<>();

    LookupRateLimiter(Clock clock, InboundProperties properties) {
        this.clock = clock;
        this.limit = properties.searchesPerMinute();
    }

    /** Counts one search of {@code agentUserId}; empty = allowed, else the whole seconds until a retry may pass (≥ 1). */
    OptionalLong acquire(long agentUserId) {
        Instant now = clock.instant();
        if (searches.size() >= SWEEP_AT) {
            sweep(now);
        }
        long[] retryAfter = {0};
        searches.compute(agentUserId, (id, window) -> {
            Deque<Instant> calls = window == null ? new ArrayDeque<>() : window;
            prune(calls, now);
            if (calls.size() >= limit) {
                Duration wait = Duration.between(now, calls.peekFirst().plus(WINDOW));
                retryAfter[0] = Math.max(1, (wait.toMillis() + 999) / 1000);
            } else {
                calls.addLast(now);
            }
            return calls;
        });
        return retryAfter[0] > 0 ? OptionalLong.of(retryAfter[0]) : OptionalLong.empty();
    }

    /** Forgets every window (tests). */
    void clear() {
        searches.clear();
    }

    private void sweep(Instant now) {
        for (Long id : searches.keySet()) {
            searches.computeIfPresent(id, (k, calls) -> {
                prune(calls, now);
                return calls.isEmpty() ? null : calls;
            });
        }
    }

    private static void prune(Deque<Instant> calls, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(cutoff)) {
            calls.removeFirst();
        }
    }
}
