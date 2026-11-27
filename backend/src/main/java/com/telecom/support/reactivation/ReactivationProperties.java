package com.telecom.support.reactivation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Temporary reactivation settings {@code app.reactivation.*} (design 03-inbound backend §6 config, R13, R14, §3.1, §8;
 * INB-04, NFR-AVAIL). An unset property keeps its default; an invalid value fails startup.
 *
 * @param accountLimit     non-FAILED requests per account within {@code accountWindow} (default 1, OQ 13)
 * @param accountWindow    window of the account limit (default 30d)
 * @param agentDailyLimit  non-FAILED requests per agent per day in Asia/Kolkata (default 10)
 * @param attentionAfter   an unresolved PENDING request is flagged {@code needs_attention} after this (default 30m)
 * @param attentionFailures failed suspends before {@code needs_attention} (default 5); retries never stop
 * @param reconcileCron    {@code ReactivationReconcileJob} schedule ({@code -} only in tests; refused in prod)
 * @param expiryCron       {@code ReactivationExpiryJob} schedule ({@code -} only in tests; refused in prod)
 * @param provisioning     {@code app.reactivation.provisioning.*}
 */
@ConfigurationProperties("app.reactivation")
public record ReactivationProperties(Integer accountLimit, Duration accountWindow, Integer agentDailyLimit,
                                     Duration attentionAfter, Integer attentionFailures, String reconcileCron,
                                     String expiryCron, Provisioning provisioning) {

    static final int DEFAULT_ACCOUNT_LIMIT = 1;
    static final Duration DEFAULT_ACCOUNT_WINDOW = Duration.ofDays(30);
    static final int DEFAULT_AGENT_DAILY_LIMIT = 10;
    static final Duration DEFAULT_ATTENTION_AFTER = Duration.ofMinutes(30);
    static final int DEFAULT_ATTENTION_FAILURES = 5;

    public ReactivationProperties {
        accountLimit = atLeastOne("account-limit", accountLimit == null ? DEFAULT_ACCOUNT_LIMIT : accountLimit);
        accountWindow = positive("account-window", accountWindow == null ? DEFAULT_ACCOUNT_WINDOW : accountWindow);
        agentDailyLimit = atLeastOne("agent-daily-limit",
                agentDailyLimit == null ? DEFAULT_AGENT_DAILY_LIMIT : agentDailyLimit);
        attentionAfter = positive("attention-after", attentionAfter == null ? DEFAULT_ATTENTION_AFTER : attentionAfter);
        attentionFailures = atLeastOne("attention-failures",
                attentionFailures == null ? DEFAULT_ATTENTION_FAILURES : attentionFailures);
        provisioning = provisioning == null ? new Provisioning(null, null) : provisioning;
    }

    /**
     * @param timeout client timeout of a provisioning call (default 5s); past it the outcome is unknown (§3.1)
     * @param mock    {@code app.reactivation.provisioning.mock.*}
     */
    public record Provisioning(Duration timeout, Mock mock) {

        static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

        public Provisioning {
            timeout = positive("provisioning.timeout", timeout == null ? DEFAULT_TIMEOUT : timeout);
            mock = mock == null ? new Mock(null, null) : mock;
        }
    }

    /**
     * @param enabled registers {@link MockProvisioningClient} (dev/test only; {@code ProfileGuard} refuses it with prod)
     * @param mode    initial {@link MockProvisioningClient.Mode} (default NORMAL)
     */
    public record Mock(Boolean enabled, MockProvisioningClient.Mode mode) {

        public Mock {
            enabled = enabled != null && enabled;
            mode = mode == null ? MockProvisioningClient.Mode.NORMAL : mode;
        }
    }

    private static int atLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("app.reactivation." + name + " must be positive: " + value);
        }
        return value;
    }

    private static Duration positive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.reactivation." + name + " must be positive: " + value);
        }
        return value;
    }
}
