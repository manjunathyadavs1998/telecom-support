package com.telecom.support.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.payment.MockPaymentGateway.Mode;
import com.telecom.support.payment.MockPaymentGateway.SignedWebhook;
import com.telecom.support.payment.PaymentGateway.CheckoutSession;
import com.telecom.support.payment.PaymentGateway.EventType;
import com.telecom.support.payment.PaymentGateway.GatewayEvent;
import com.telecom.support.payment.PaymentGateway.GatewayPaymentStatus;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.GatewayTimeoutException;
import com.telecom.support.payment.PaymentGateway.GatewayUnavailableException;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentGateway.WebhookPayloadException;
import com.telecom.support.payment.PaymentGateway.WebhookSignatureException;
import com.telecom.support.payment.PaymentGateway.WebhookSignatureException.Reason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * B14: the {@link PaymentGateway} port's mock (design 02-customer backend §8, §3.1; CUS-08.2, NFR-AVAIL, NFR-PRIV): modes,
 * NOT_FOUND, client-generated reference after a timeout, session expiry, and the webhook signature round trip.
 */
class MockPaymentGatewayTest {

    private static final BigDecimal AMOUNT = new BigDecimal("399.00");
    private static final String RETURN_URL = "http://localhost:8080/payments/return";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T06:00:00Z"));

    // ------------------------------------------------------------------ createCheckout / getStatus

    @Test
    void normalModeOpensARandomSessionAndReportsItPending() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));

        CheckoutSession session = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);

        assertThat(session.sessionId()).matches("[0-9a-f]{32}"); // 128-bit random
        assertThat(session.redirectUrl()).isEqualTo("/mock-gateway/checkout/" + session.sessionId());
        assertThat(session.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(gateway.createCheckout("PAY-20261125-000002", AMOUNT, "INR", RETURN_URL).sessionId())
                .isNotEqualTo(session.sessionId());

        GatewayPaymentStatus status = gateway.getStatus("PAY-20261125-000001");
        assertThat(status).isEqualTo(new GatewayPaymentStatus(GatewayStatus.PENDING, session.sessionId(), null, null,
                AMOUNT, "INR"));
        assertThat(gateway.findSession(session.sessionId())).get()
                .satisfies(s -> assertThat(s.returnUrl()).isEqualTo(RETURN_URL));
    }

    @Test
    void sameOpenReferenceReturnsTheSameSession() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));

        CheckoutSession first = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);
        CheckoutSession again = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);

        assertThat(again).isEqualTo(first);
    }

    @Test
    void unknownReferenceIsNotFound() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));

        GatewayPaymentStatus status = gateway.getStatus("PAY-20261125-000099");

        assertThat(status.status()).isEqualTo(GatewayStatus.NOT_FOUND);
        assertThat(status.sessionId()).isNull();
        assertThat(status.amount()).isNull();
    }

    @Test
    void unavailableModeRefusesEveryCallAndCreatesNothing() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        gateway.setMode(Mode.UNAVAILABLE);

        assertThatThrownBy(() -> gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL))
                .isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> gateway.getStatus("PAY-20261125-000001"))
                .isInstanceOf(GatewayUnavailableException.class);

        gateway.setMode(Mode.NORMAL);
        assertThat(gateway.getStatus("PAY-20261125-000001").status()).isEqualTo(GatewayStatus.NOT_FOUND);
    }

    @Test
    void timeoutModeWaitsPastTheTimeoutButTheSessionStaysReconcilableByReference() {
        Duration timeout = Duration.ofMillis(200);
        MockPaymentGateway gateway = gateway(null, timeout);
        gateway.setMode(Mode.TIMEOUT);

        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL))
                .isInstanceOf(GatewayTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(timeout);
        assertThatThrownBy(() -> gateway.getStatus("PAY-20261125-000001"))
                .isInstanceOf(GatewayTimeoutException.class);

        // the reference was ours, so once the gateway answers again the session is found by it
        gateway.setMode(Mode.NORMAL);
        GatewayPaymentStatus status = gateway.getStatus("PAY-20261125-000001");
        assertThat(status.status()).isEqualTo(GatewayStatus.PENDING);
        assertThat(status.sessionId()).isNotBlank();
    }

    @Test
    void initialModeComesFromTheProperties() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties(null, null, null,
                new PaymentGatewayProperties.Mock(true, Mode.UNAVAILABLE, null));

        assertThat(new MockPaymentGateway(properties, new ObjectMapper(), clock).mode()).isEqualTo(Mode.UNAVAILABLE);

        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        gateway.setMode(Mode.TIMEOUT);
        gateway.reset();
        assertThat(gateway.mode()).isEqualTo(Mode.NORMAL);
    }

    @Test
    void pendingSessionPastItsTtlReportsExpired() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);

        clock.advance(Duration.ofMinutes(15));

        assertThat(gateway.getStatus("PAY-20261125-000001").status()).isEqualTo(GatewayStatus.EXPIRED);
    }

    @Test
    void completeSettlesTheSessionAndStatusFollows() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        CheckoutSession paid = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);
        CheckoutSession failed = gateway.createCheckout("PAY-20261125-000002", AMOUNT, "INR", RETURN_URL);

        GatewayEvent success = gateway.complete(paid.sessionId(), GatewayStatus.SUCCEEDED, Method.UPI);
        GatewayEvent failure = gateway.complete(failed.sessionId(), GatewayStatus.FAILED, Method.CARD);

        assertThat(success.type()).isEqualTo(EventType.SUCCEEDED);
        assertThat(success.paymentReference()).isEqualTo("PAY-20261125-000001");
        assertThat(success.sessionId()).isEqualTo(paid.sessionId());
        assertThat(success.gatewayTransactionId()).isNotBlank();
        assertThat(success.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(success.method()).isEqualTo(Method.UPI);
        GatewayPaymentStatus status = gateway.getStatus("PAY-20261125-000001");
        assertThat(status.status()).isEqualTo(GatewayStatus.SUCCEEDED);
        assertThat(status.gatewayTxnId()).isEqualTo(success.gatewayTransactionId());
        assertThat(status.method()).isEqualTo(Method.UPI);

        assertThat(failure.type()).isEqualTo(EventType.FAILED);
        assertThat(failure.gatewayTransactionId()).isNull();
        assertThat(gateway.getStatus("PAY-20261125-000002").status()).isEqualTo(GatewayStatus.FAILED);

        assertThatThrownBy(() -> gateway.complete(paid.sessionId(), GatewayStatus.FAILED, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.complete("nope", GatewayStatus.FAILED, null))
                .isInstanceOf(IllegalArgumentException.class);
        CheckoutSession other = gateway.createCheckout("PAY-20261125-000003", AMOUNT, "INR", RETURN_URL);
        assertThatThrownBy(() -> gateway.complete(other.sessionId(), GatewayStatus.SUCCEEDED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.complete(other.sessionId(), GatewayStatus.PENDING, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settledReferenceGetsANewSessionOnTheNextCheckout() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        CheckoutSession first = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);
        gateway.complete(first.sessionId(), GatewayStatus.FAILED, null);

        CheckoutSession second = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);

        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(gateway.getStatus("PAY-20261125-000001").status()).isEqualTo(GatewayStatus.PENDING);
    }

    @Test
    void sessionMemoryIsBounded() {
        MockPaymentGateway gateway = gateway(null, Duration.ofSeconds(5));
        for (int i = 0; i <= MockPaymentGateway.MAX_SESSIONS; i++) {
            gateway.createCheckout("PAY-20261125-" + String.format("%06d", i), AMOUNT, "INR", RETURN_URL);
        }

        assertThat(gateway.size()).isEqualTo(MockPaymentGateway.MAX_SESSIONS);
        assertThat(gateway.getStatus("PAY-20261125-000000").status()).isEqualTo(GatewayStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ webhook signature

    @Test
    void signedWebhookRoundTripsThroughVerify() {
        MockPaymentGateway gateway = gateway("test-webhook-secret", Duration.ofSeconds(5));
        CheckoutSession session = gateway.createCheckout("PAY-20261125-000001", AMOUNT, "INR", RETURN_URL);
        GatewayEvent sent = gateway.complete(session.sessionId(), GatewayStatus.SUCCEEDED, Method.CARD);

        SignedWebhook webhook = gateway.signedWebhook(sent);
        GatewayEvent received = gateway.verifyWebhook(headers(webhook.signature()), webhook.body());

        assertThat(webhook.signature()).matches("t=" + clock.instant().getEpochSecond() + ",v1=[0-9a-f]{64}");
        assertThat(received).isEqualTo(new GatewayEvent(sent.eventId(), EventType.SUCCEEDED, "PAY-20261125-000001",
                session.sessionId(), sent.gatewayTransactionId(), new BigDecimal("399.00"), "INR", Method.CARD, null,
                sent.occurredAt()));
        // no card data on the wire
        assertThat(new String(webhook.body(), StandardCharsets.UTF_8))
                .doesNotContainIgnoringCase("card_number").doesNotContainIgnoringCase("cvv");
    }

    @Test
    void anotherInstanceWithTheSameSecretVerifiesAndADifferentSecretDoesNot() {
        MockPaymentGateway signer = gateway("shared-secret", Duration.ofSeconds(5));
        MockPaymentGateway sameSecret = gateway("shared-secret", Duration.ofSeconds(5));
        MockPaymentGateway otherSecret = gateway("other-secret", Duration.ofSeconds(5));
        byte[] body = eventJson("payment.failed", null, null).getBytes(StandardCharsets.UTF_8);
        String signature = signer.signatureHeader(clock.instant().getEpochSecond(), body);

        assertThat(sameSecret.verifyWebhook(headers(signature), body).type()).isEqualTo(EventType.FAILED);
        assertRejected(() -> otherSecret.verifyWebhook(headers(signature), body), Reason.BAD_SIGNATURE);
    }

    @Test
    void tamperedMissingOrMalformedSignatureIsRejected() {
        MockPaymentGateway gateway = gateway("test-webhook-secret", Duration.ofSeconds(5));
        byte[] body = eventJson("payment.failed", null, null).getBytes(StandardCharsets.UTF_8);
        String signature = gateway.signatureHeader(clock.instant().getEpochSecond(), body);
        byte[] tampered = eventJson("payment.failed", null, null).replace("399.00", "1.00")
                .getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> gateway.verifyWebhook(headers(signature), tampered), Reason.BAD_SIGNATURE);
        assertRejected(() -> gateway.verifyWebhook(new HttpHeaders(), body), Reason.BAD_SIGNATURE);
        assertRejected(() -> gateway.verifyWebhook(headers("v1=abcd"), body), Reason.BAD_SIGNATURE);
        assertRejected(() -> gateway.verifyWebhook(headers("t=abc,v1=zz"), body), Reason.BAD_SIGNATURE);
        // the timestamp is signed: moving it breaks the signature
        String moved = signature.replaceFirst("t=\\d+", "t=" + (clock.instant().getEpochSecond() + 1));
        assertRejected(() -> gateway.verifyWebhook(headers(moved), body), Reason.BAD_SIGNATURE);
    }

    @Test
    void signatureOutsideTheToleranceIsStale() {
        MockPaymentGateway gateway = gateway("test-webhook-secret", Duration.ofSeconds(5));
        byte[] body = eventJson("payment.failed", null, null).getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();

        assertThat(gateway.verifyWebhook(headers(gateway.signatureHeader(now - 300, body)), body)).isNotNull();
        assertRejected(() -> gateway.verifyWebhook(headers(gateway.signatureHeader(now - 301, body)), body),
                Reason.STALE);
        assertRejected(() -> gateway.verifyWebhook(headers(gateway.signatureHeader(now + 301, body)), body),
                Reason.STALE);
    }

    @Test
    void badSignatureIsCheckedBeforeTheBodyIsParsed() {
        MockPaymentGateway gateway = gateway("test-webhook-secret", Duration.ofSeconds(5));
        byte[] malformed = "{not json".getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> gateway.verifyWebhook(headers("t=1,v1=00"), malformed), Reason.BAD_SIGNATURE);
        assertThatThrownBy(() -> gateway.verifyWebhook(
                headers(gateway.signatureHeader(clock.instant().getEpochSecond(), malformed)), malformed))
                .isInstanceOf(WebhookPayloadException.class);
    }

    @Test
    void signedButInvalidEventsAreRejectedAsPayloadErrors() {
        MockPaymentGateway gateway = gateway("test-webhook-secret", Duration.ofSeconds(5));

        assertInvalid(gateway, eventJson("payment.succeeded", null, "UPI"));          // no gatewayTransactionId
        assertInvalid(gateway, eventJson("payment.succeeded", "txn-1", null));        // no method
        assertInvalid(gateway, eventJson("payment.refunded", null, null));            // unknown type
        assertInvalid(gateway, eventJson("payment.failed", null, "CASH"));            // unknown method
        assertInvalid(gateway, eventJson("payment.failed", null, null).replace("399.00", "399"));
        assertInvalid(gateway, eventJson("payment.failed", null, null).replace("\"INR\"", "\"USD\""));
        assertInvalid(gateway, eventJson("payment.failed", null, null).replace("\"evt-1\"", "null"));
        assertInvalid(gateway, eventJson("payment.failed", null, null).replace("\"evt-1\"", "\"" + "e".repeat(65) + "\""));
        assertInvalid(gateway, eventJson("payment.failed", null, null).replace("2026-11-25T05:59:00Z", "yesterday"));
        assertInvalid(gateway, "[]");

        GatewayEvent ok = verify(gateway, eventJson("payment.succeeded", "txn-1", "NETBANKING"));
        assertThat(ok.gatewayTransactionId()).isEqualTo("txn-1");
        assertThat(ok.method()).isEqualTo(Method.NETBANKING);
        assertThat(ok.occurredAt()).isEqualTo(Instant.parse("2026-11-25T05:59:00Z"));
        assertThat(verify(gateway, eventJson("payment.expired", null, null)).type()).isEqualTo(EventType.EXPIRED);
    }

    @Test
    void propertiesDefaultAndHideTheSecret() {
        PaymentGatewayProperties defaults = new PaymentGatewayProperties(null, null, " ", null);

        assertThat(defaults.gateway().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.gateway().signatureTolerance()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.gateway().sessionTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defaults.gateway().lateCaptureWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.gateway().webhookSecret()).isNull();
        assertThat(defaults.webhook().ipRequestsPerMinute()).isEqualTo(60);
        assertThat(defaults.returnUrlBase()).isNull();
        assertThat(defaults.mock().enabled()).isFalse();
        assertThat(defaults.mock().mode()).isEqualTo(Mode.NORMAL);

        PaymentGatewayProperties.Gateway secret =
                new PaymentGatewayProperties.Gateway(null, "very-secret-value", null, null, null);
        assertThat(secret.toString()).doesNotContain("very-secret-value");
        assertThatThrownBy(() -> new PaymentGatewayProperties.Gateway(Duration.ZERO, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewayProperties.Webhook(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private MockPaymentGateway gateway(String secret, Duration timeout) {
        PaymentGatewayProperties properties = new PaymentGatewayProperties(
                new PaymentGatewayProperties.Gateway(timeout, secret, null, null, null), null, null, null);
        return new MockPaymentGateway(properties, new ObjectMapper(), clock);
    }

    private static HttpHeaders headers(String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(MockPaymentGateway.SIGNATURE_HEADER, signature);
        return headers;
    }

    private static String eventJson(String type, String txnId, String method) {
        return """
                {"eventId":"evt-1","type":"%s","paymentReference":"PAY-20261125-000001","sessionId":"sess-1",\
                "gatewayTransactionId":%s,"amount":"399.00","currency":"INR","method":%s,"failureReason":null,\
                "occurredAt":"2026-11-25T05:59:00Z"}""".formatted(type, quoted(txnId), quoted(method));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private GatewayEvent verify(MockPaymentGateway gateway, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return gateway.verifyWebhook(headers(gateway.signatureHeader(clock.instant().getEpochSecond(), body)), body);
    }

    private void assertInvalid(MockPaymentGateway gateway, String json) {
        assertThatThrownBy(() -> verify(gateway, json)).as(json).isInstanceOf(WebhookPayloadException.class);
    }

    private static void assertRejected(Runnable call, Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(WebhookSignatureException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    /** A fixed UTC clock the test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
