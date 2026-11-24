package com.telecom.support.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login throttling limits {@code app.security.login.*} (design 01-foundation backend §4.1, §12 E2.3; NFR-SEC). The defaults
 * are the approved §4.1 values; an unset property keeps its default and a non-positive value fails startup.
 *
 * @param ipAttemptsPerMinute            L1: attempts per client IP per minute (default 20)
 * @param pairMaxFailures                L2: failures of one (IP, username) pair within 15 min that block it (default 5)
 * @param pairBlock                      L2: how long a blocked pair gets 429 (default 15m)
 * @param usernameAnomalyFailuresPerHour L3: failures of one username per rolling hour above which an anomaly is
 *                                       reported (default 50)
 */
@ConfigurationProperties("app.security.login")
public record LoginLimitProperties(
        Integer ipAttemptsPerMinute,
        Integer pairMaxFailures,
        Duration pairBlock,
        Integer usernameAnomalyFailuresPerHour) {

    static final int DEFAULT_IP_ATTEMPTS_PER_MINUTE = 20;
    static final int DEFAULT_PAIR_MAX_FAILURES = 5;
    static final Duration DEFAULT_PAIR_BLOCK = Duration.ofMinutes(15);
    static final int DEFAULT_USERNAME_ANOMALY_FAILURES_PER_HOUR = 50;

    /** The §4.1 defaults. */
    public static final LoginLimitProperties DEFAULTS = new LoginLimitProperties(null, null, null, null);

    public LoginLimitProperties {
        ipAttemptsPerMinute = positive("ip-attempts-per-minute", ipAttemptsPerMinute, DEFAULT_IP_ATTEMPTS_PER_MINUTE);
        pairMaxFailures = positive("pair-max-failures", pairMaxFailures, DEFAULT_PAIR_MAX_FAILURES);
        pairBlock = pairBlock == null ? DEFAULT_PAIR_BLOCK : pairBlock;
        if (pairBlock.isNegative() || pairBlock.isZero()) {
            throw new IllegalArgumentException("app.security.login.pair-block must be positive: " + pairBlock);
        }
        usernameAnomalyFailuresPerHour = positive("username-anomaly-failures-per-hour", usernameAnomalyFailuresPerHour,
                DEFAULT_USERNAME_ANOMALY_FAILURES_PER_HOUR);
    }

    private static Integer positive(String name, Integer value, int defaultValue) {
        int result = value == null ? defaultValue : value;
        if (result <= 0) {
            throw new IllegalArgumentException("app.security.login." + name + " must be positive: " + result);
        }
        return result;
    }
}
