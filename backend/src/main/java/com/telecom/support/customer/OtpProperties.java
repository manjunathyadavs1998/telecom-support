package com.telecom.support.customer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contact verification settings {@code app.customer.otp.*} (design 02-customer backend R3, §6; CUS-02.2, NFR-SEC). An unset
 * property keeps its default; an invalid value fails startup.
 *
 * @param ttl           how long a code is valid (default 10m)
 * @param maxAttempts   confirm attempts per verification before it is LOCKED (default 5; at most 10, the column CHECK)
 * @param startsPerHour verification starts per customer per rolling hour; more → 429 (default 5)
 * @param hmacSecret    key of the HMAC-SHA256 the code is stored as; blank → a random per-process key (codes pending
 *                      at a restart then no longer match, which only means asking for a new code)
 * @param devFixedCode  optional 6-digit code used instead of a random one (dev only: {@code ProfileGuard} refuses it
 *                      with the {@code prod} profile, OQ 11); blank = random codes
 */
@ConfigurationProperties("app.customer.otp")
public record OtpProperties(Duration ttl, Integer maxAttempts, Integer startsPerHour, String hmacSecret,
                            String devFixedCode) {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int DEFAULT_STARTS_PER_HOUR = 5;
    /** {@code contact_verifications.attempts} is {@code CHECK (attempts BETWEEN 0 AND 10)}. */
    static final int MAX_ATTEMPTS_LIMIT = 10;

    public OtpProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("app.customer.otp.ttl must be positive: " + ttl);
        }
        maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("app.customer.otp.max-attempts must be 1.." + MAX_ATTEMPTS_LIMIT + ": "
                    + maxAttempts);
        }
        startsPerHour = startsPerHour == null ? DEFAULT_STARTS_PER_HOUR : startsPerHour;
        if (startsPerHour < 1) {
            throw new IllegalArgumentException("app.customer.otp.starts-per-hour must be positive: " + startsPerHour);
        }
        hmacSecret = hmacSecret == null || hmacSecret.isBlank() ? null : hmacSecret;
        devFixedCode = devFixedCode == null || devFixedCode.isBlank() ? null : devFixedCode;
        if (devFixedCode != null && !devFixedCode.matches("\\d{6}")) {
            // the value is a dev placeholder, but keep it out of the message like any code
            throw new IllegalArgumentException("app.customer.otp.dev-fixed-code must be exactly 6 digits");
        }
    }

    /** Hides the secret and the fixed code (configuration dumps, logs). */
    @Override
    public String toString() {
        return "OtpProperties[ttl=" + ttl + ", maxAttempts=" + maxAttempts + ", startsPerHour=" + startsPerHour
                + ", hmacSecret=" + (hmacSecret == null ? "<random>" : "***") + ", devFixedCode="
                + (devFixedCode == null ? "<none>" : "***") + "]";
    }
}
