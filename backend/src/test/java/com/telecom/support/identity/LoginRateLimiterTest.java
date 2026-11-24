package com.telecom.support.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.common.IdempotencyServiceIT.MutableClock;
import com.telecom.support.identity.LoginRateLimiter.Throttled;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * C4: the limiter maps are really bounded (01 backend §4.1 "bounded, expiring in-memory maps"; NFR-SEC). A new key that
 * does not fit after the idle sweep is throttled (429 {@code capacity}); existing keys keep their counters.
 */
class LoginRateLimiterTest {

    private static final int CAPACITY = 3;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T08:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock, CAPACITY);

    @Test
    void mapsNeverExceedTheCapAndNewKeysAtCapacityAreThrottled() {
        for (int i = 0; i < 500; i++) {
            Optional<Throttled> throttled = limiter.acquire("198.51.100." + i, "user-" + i);
            if (i < CAPACITY) {
                assertThat(throttled).as("attempt %d", i).isEmpty();
                limiter.recordFailure("198.51.100." + i, "user-" + i);
            } else {
                assertThat(throttled).as("attempt %d", i).get()
                        .satisfies(t -> {
                            assertThat(t.limit()).isEqualTo(LoginRateLimiter.CAPACITY);
                            assertThat(t.retryAfterSeconds()).isPositive();
                        });
                limiter.recordFailure("198.51.100." + i, "user-" + i); // a racing failure still adds nothing
            }
            assertThat(java.util.Arrays.stream(limiter.trackedKeys()).max().orElseThrow()).isLessThanOrEqualTo(CAPACITY);
        }
    }

    @Test
    void newPairOrUsernameAtCapacityIsThrottledEvenFromAKnownIp() {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.acquire("203.0.113.1", "user-" + i)).isEmpty();
            limiter.recordFailure("203.0.113.1", "user-" + i);
        }

        assertThat(limiter.acquire("203.0.113.1", "someone-new")).get()
                .extracting(Throttled::limit).isEqualTo(LoginRateLimiter.CAPACITY);
        assertThat(java.util.Arrays.stream(limiter.trackedKeys()).max().orElseThrow()).isLessThanOrEqualTo(CAPACITY);
    }

    @Test
    void existingKeysKeepTheirLimitsWhileTheMapsAreFull() {
        String ip = "192.0.2.10";
        for (int i = 0; i < LoginRateLimiter.PAIR_MAX_FAILURES; i++) {
            assertThat(limiter.acquire(ip, "victim")).isEmpty();
            limiter.recordFailure(ip, "victim");
        }
        // fill the rest of every map, then flood with new keys
        for (int i = 0; i < CAPACITY - 1; i++) {
            limiter.acquire("192.0.2." + (20 + i), "filler-" + i);
            limiter.recordFailure("192.0.2." + (20 + i), "filler-" + i);
        }
        for (int i = 0; i < 100; i++) {
            limiter.acquire("2001:db8::" + i, "spray-" + i);
        }

        assertThat(limiter.acquire(ip, "victim")).get().extracting(Throttled::limit).isEqualTo("L2");
        // L1 still counts for the existing IP: 20 attempts per minute in total
        for (int i = LoginRateLimiter.PAIR_MAX_FAILURES + 1; i < LoginRateLimiter.IP_ATTEMPTS_PER_MINUTE; i++) {
            limiter.acquire(ip, "victim");
        }
        assertThat(limiter.acquire(ip, "victim")).get().extracting(Throttled::limit).isEqualTo("L1");
    }

    @Test
    void idleEntriesAreSweptSoNewKeysFitAgain() {
        for (int i = 0; i < CAPACITY; i++) {
            limiter.acquire("198.51.100." + i, "user-" + i);
            limiter.recordFailure("198.51.100." + i, "user-" + i);
        }
        assertThat(limiter.acquire("198.51.100.99", "late")).isPresent();

        clock.set(clock.instant().plus(LoginRateLimiter.USER_WINDOW).plus(Duration.ofSeconds(1)));

        assertThat(limiter.acquire("198.51.100.99", "late")).isEmpty();
        assertThat(java.util.Arrays.stream(limiter.trackedKeys()).max().orElseThrow()).isLessThanOrEqualTo(CAPACITY);
    }

    @Test
    void configuredIpLimitIsApplied() {
        LoginRateLimiter custom = new LoginRateLimiter(clock, new LoginLimitProperties(3, null, null, null));
        for (int i = 0; i < 3; i++) {
            assertThat(custom.acquire("192.0.2.50", "user-" + i)).as("attempt %d", i + 1).isEmpty();
        }

        assertThat(custom.acquire("192.0.2.50", "user-x")).get().extracting(Throttled::limit).isEqualTo("L1");
    }

    @Test
    void unsetPropertiesKeepTheApprovedDefaults() {
        assertThat(LoginLimitProperties.DEFAULTS).isEqualTo(
                new LoginLimitProperties(20, 5, Duration.ofMinutes(15), 50));
    }
}
