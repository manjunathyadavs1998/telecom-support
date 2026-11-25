package com.telecom.support.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment gateway settings {@code app.payment.*} (design 02-customer backend §6, §8, §3.1; CUS-08.2–08.4, NFR-AVAIL,
 * NFR-SEC). An unset property keeps its default; an invalid value fails startup.
 *
 * @param gateway       {@code app.payment.gateway.*}
 * @param webhook       {@code app.payment.webhook.*}
 * @param returnUrlBase {@code app.payment.return-url-base}: where the gateway sends the browser back (used by E15)
 * @param mock          {@code app.payment.mock.*}
 */
@ConfigurationProperties("app.payment")
public record PaymentGatewayProperties(Gateway gateway, Webhook webhook, String returnUrlBase, Mock mock) {

    public PaymentGatewayProperties {
        gateway = gateway == null ? new Gateway(null, null, null, null, null) : gateway;
        webhook = webhook == null ? new Webhook(null) : webhook;
        returnUrlBase = returnUrlBase == null || returnUrlBase.isBlank() ? null : returnUrlBase;
        mock = mock == null ? new Mock(null, null, null) : mock;
    }

    /**
     * @param timeout            client timeout of a gateway call (default 5s); past it the outcome is unknown (§3.1)
     * @param webhookSecret      HMAC-SHA256 key of {@code X-Gateway-Signature}; blank → a random per-process key (enough
     *                           for the mock, which both signs and verifies)
     * @param signatureTolerance largest accepted distance between the signature timestamp and now (default 5m)
     * @param sessionTtl         lifetime of a hosted checkout session (default 15m)
     * @param lateCaptureWindow  how long FAILED/EXPIRED payments are re-checked for a late capture (default 24h)
     */
    public record Gateway(Duration timeout, String webhookSecret, Duration signatureTolerance, Duration sessionTtl,
                          Duration lateCaptureWindow) {

        static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
        static final Duration DEFAULT_SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
        static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(15);
        static final Duration DEFAULT_LATE_CAPTURE_WINDOW = Duration.ofHours(24);

        public Gateway {
            timeout = positive("timeout", timeout, DEFAULT_TIMEOUT);
            webhookSecret = webhookSecret == null || webhookSecret.isBlank() ? null : webhookSecret;
            signatureTolerance = positive("signature-tolerance", signatureTolerance, DEFAULT_SIGNATURE_TOLERANCE);
            sessionTtl = positive("session-ttl", sessionTtl, DEFAULT_SESSION_TTL);
            lateCaptureWindow = positive("late-capture-window", lateCaptureWindow, DEFAULT_LATE_CAPTURE_WINDOW);
        }

        /** Hides the webhook secret (configuration dumps, logs). */
        @Override
        public String toString() {
            return "Gateway[timeout=" + timeout + ", webhookSecret=" + (webhookSecret == null ? "<random>" : "***")
                    + ", signatureTolerance=" + signatureTolerance + ", sessionTtl=" + sessionTtl
                    + ", lateCaptureWindow=" + lateCaptureWindow + "]";
        }
    }

    /** @param ipRequestsPerMinute webhook calls per client IP per minute; more → 429 (default 60) */
    public record Webhook(Integer ipRequestsPerMinute) {

        static final int DEFAULT_IP_REQUESTS_PER_MINUTE = 60;

        public Webhook {
            ipRequestsPerMinute = ipRequestsPerMinute == null ? DEFAULT_IP_REQUESTS_PER_MINUTE : ipRequestsPerMinute;
            if (ipRequestsPerMinute < 1) {
                throw new IllegalArgumentException("app.payment.webhook.ip-requests-per-minute must be positive: "
                        + ipRequestsPerMinute);
            }
        }
    }

    /**
     * @param enabled    registers the mock checkout page (dev/test only; {@code ProfileGuard} refuses it with {@code prod})
     * @param mode       initial {@link MockPaymentGateway.Mode} (default NORMAL)
     * @param webhookUrl where the mock checkout page posts its signed webhook
     */
    public record Mock(Boolean enabled, MockPaymentGateway.Mode mode, String webhookUrl) {

        public Mock {
            enabled = enabled != null && enabled;
            mode = mode == null ? MockPaymentGateway.Mode.NORMAL : mode;
            webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl;
        }
    }

    private static Duration positive(String name, Duration value, Duration fallback) {
        Duration result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero()) {
            throw new IllegalArgumentException("app.payment.gateway." + name + " must be positive: " + result);
        }
        return result;
    }
}
