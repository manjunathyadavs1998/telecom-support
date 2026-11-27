package com.telecom.support.customer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound workspace settings {@code app.inbound.*} (design 03-inbound backend §6 config, R1–R5, R10; INB-01, NFR-SEC).
 * An unset property keeps its default; an invalid value fails startup. The identity check OTP reuses
 * {@code app.customer.otp.*} ({@link OtpProperties}).
 *
 * @param searchesPerMinute  customer searches per agent per minute; more → 429 (default 30, per instance, OQ 15)
 * @param lookupTokenTtl     how long a search hit's lookup token is valid (default 15m)
 * @param identityCheck      identity check settings
 * @param kbaCustomerPerHour KBA attempts (CHECKING or FAILED) per customer per rolling hour (default 3)
 * @param kbaAgentPerHour    KBA attempts (CHECKING or FAILED) per agent per rolling hour (default 10)
 * @param relatedWindow      possible duplicate window around a case, ± (default 30d, R10)
 */
@ConfigurationProperties("app.inbound")
public record InboundProperties(Integer searchesPerMinute, Duration lookupTokenTtl, IdentityCheck identityCheck,
                                Integer kbaCustomerPerHour, Integer kbaAgentPerHour, Duration relatedWindow) {

    static final int DEFAULT_SEARCHES_PER_MINUTE = 30;
    static final Duration DEFAULT_LOOKUP_TOKEN_TTL = Duration.ofMinutes(15);
    static final int DEFAULT_KBA_CUSTOMER_PER_HOUR = 3;
    static final int DEFAULT_KBA_AGENT_PER_HOUR = 10;
    static final Duration DEFAULT_RELATED_WINDOW = Duration.ofDays(30);

    /**
     * @param validity how long a VERIFIED check gives the agent access to the customer (default 30m, never extended)
     */
    public record IdentityCheck(Duration validity) {

        static final Duration DEFAULT_VALIDITY = Duration.ofMinutes(30);

        public IdentityCheck {
            validity = positive("identity-check.validity", validity == null ? DEFAULT_VALIDITY : validity);
        }
    }

    public InboundProperties {
        searchesPerMinute = atLeastOne("searches-per-minute",
                searchesPerMinute == null ? DEFAULT_SEARCHES_PER_MINUTE : searchesPerMinute);
        lookupTokenTtl = positive("lookup-token-ttl", lookupTokenTtl == null ? DEFAULT_LOOKUP_TOKEN_TTL : lookupTokenTtl);
        identityCheck = identityCheck == null ? new IdentityCheck(null) : identityCheck;
        kbaCustomerPerHour = atLeastOne("kba-customer-per-hour",
                kbaCustomerPerHour == null ? DEFAULT_KBA_CUSTOMER_PER_HOUR : kbaCustomerPerHour);
        kbaAgentPerHour = atLeastOne("kba-agent-per-hour",
                kbaAgentPerHour == null ? DEFAULT_KBA_AGENT_PER_HOUR : kbaAgentPerHour);
        relatedWindow = positive("related-window", relatedWindow == null ? DEFAULT_RELATED_WINDOW : relatedWindow);
    }

    private static int atLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("app.inbound." + name + " must be positive: " + value);
        }
        return value;
    }

    private static Duration positive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.inbound." + name + " must be positive: " + value);
        }
        return value;
    }
}
