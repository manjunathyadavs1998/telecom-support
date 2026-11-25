package com.telecom.support.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * In-memory hosted {@link PaymentGateway} (design 02-customer backend §8, §3.1; CUS-08.2–08.4, NFR-AVAIL, NFR-PRIV).
 *
 * <ul>
 *   <li>{@link Mode#NORMAL}: {@link #createCheckout} opens a session with a 128-bit random id and the redirect URL
 *       {@code /mock-gateway/checkout/{sessionId}}; {@link #getStatus} answers from the sessions by merchant reference
 *       ({@code NOT_FOUND} when none was created; a PENDING session past its {@code session-ttl} reports EXPIRED).</li>
 *   <li>{@link Mode#UNAVAILABLE}: every outbound call is refused ({@link GatewayUnavailableException}); nothing is
 *       created.</li>
 *   <li>{@link Mode#TIMEOUT}: every outbound call waits for {@code app.payment.gateway.timeout} and then throws
 *       {@link GatewayTimeoutException}. {@link #createCheckout} still opens the session first (the gateway got the
 *       request, the answer was lost), so the payment is resolved later by its reference.</li>
 * </ul>
 *
 * <p>Webhooks are signed {@code X-Gateway-Signature: t=<epoch seconds>,v1=<hex HMAC-SHA256(t + "." + raw body)>} with
 * {@code app.payment.gateway.webhook-secret}. {@link #complete} plays the customer's action on the checkout page and
 * {@link #signedWebhook} builds the request the gateway would send. No card data is ever accepted or kept; the session
 * map is bounded ({@link #MAX_SESSIONS}, oldest evicted).
 */
@Component
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class MockPaymentGateway implements PaymentGateway {

    /** Behaviour of outbound calls ({@code app.payment.mock.mode}, switchable by tests). */
    public enum Mode { NORMAL, UNAVAILABLE, TIMEOUT }

    /** Header carrying the webhook signature. */
    public static final String SIGNATURE_HEADER = "X-Gateway-Signature";
    static final String CHECKOUT_PATH = "/mock-gateway/checkout/";
    static final int MAX_SESSIONS = 10_000;

    private static final String HMAC = "HmacSHA256";
    private static final Pattern MONEY = Pattern.compile("^\\d{1,10}\\.\\d{2}$");
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_REFERENCE_LENGTH = 24;
    private static final int MAX_FAILURE_REASON_LENGTH = 100;

    /** One checkout session as the gateway keeps it (no card data). */
    public record Session(String paymentRef, String sessionId, BigDecimal amount, String currency, String returnUrl,
                          GatewayStatus status, Method method, String gatewayTxnId, Instant expiresAt) {

        Session settle(GatewayStatus to, Method paidWith, String txnId) {
            return new Session(paymentRef, sessionId, amount, currency, returnUrl, to, paidWith, txnId, expiresAt);
        }
    }

    /** A webhook request ready to post: the raw body and its {@link #SIGNATURE_HEADER} value. */
    public record SignedWebhook(byte[] body, String signature) {}

    private final Map<String, Session> sessionsByRef = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    private final PaymentGatewayProperties.Gateway config;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;
    private volatile Mode mode;

    MockPaymentGateway(PaymentGatewayProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.config = properties.gateway();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.mode = properties.mock().mode();
        this.secret = config.webhookSecret() != null
                ? config.webhookSecret().getBytes(StandardCharsets.UTF_8)
                : randomBytes(32);
    }

    // ------------------------------------------------------------------ PaymentGateway

    @Override
    public CheckoutSession createCheckout(String paymentRef, BigDecimal amount, String currency, String returnUrl) {
        Mode current = mode;
        if (current == Mode.UNAVAILABLE) {
            throw new GatewayUnavailableException("Mock gateway refused createCheckout for " + paymentRef);
        }
        Session session;
        synchronized (sessionsByRef) {
            session = sessionsByRef.get(paymentRef);
            if (session == null || session.status() != GatewayStatus.PENDING) {
                session = new Session(paymentRef, HexFormat.of().formatHex(randomBytes(16)), amount, currency,
                        returnUrl, GatewayStatus.PENDING, null, null, clock.instant().plus(config.sessionTtl()));
                sessionsByRef.remove(paymentRef);
                sessionsByRef.put(paymentRef, session);
            }
        }
        if (current == Mode.TIMEOUT) {
            timeOut("createCheckout", paymentRef);
        }
        return new CheckoutSession(session.sessionId(), CHECKOUT_PATH + session.sessionId(), session.expiresAt());
    }

    @Override
    public GatewayPaymentStatus getStatus(String paymentRef) {
        Mode current = mode;
        if (current == Mode.UNAVAILABLE) {
            throw new GatewayUnavailableException("Mock gateway refused getStatus for " + paymentRef);
        }
        if (current == Mode.TIMEOUT) {
            timeOut("getStatus", paymentRef);
        }
        Session session;
        synchronized (sessionsByRef) {
            session = sessionsByRef.get(paymentRef);
            if (session == null) {
                return GatewayPaymentStatus.notFound();
            }
            if (session.status() == GatewayStatus.PENDING && !clock.instant().isBefore(session.expiresAt())) {
                session = session.settle(GatewayStatus.EXPIRED, null, null);
                sessionsByRef.put(paymentRef, session);
            }
        }
        return new GatewayPaymentStatus(session.status(), session.sessionId(), session.method(), session.gatewayTxnId(),
                session.amount(), session.currency());
    }

    @Override
    public GatewayEvent verifyWebhook(HttpHeaders headers, byte[] rawBody) {
        verifySignature(headers.getFirst(SIGNATURE_HEADER), rawBody == null ? new byte[0] : rawBody);
        return parseEvent(rawBody);
    }

    // ------------------------------------------------------------------ mock checkout page and tests

    /** The session with this id, if the gateway still knows it (mock checkout page). */
    public Optional<Session> findSession(String sessionId) {
        synchronized (sessionsByRef) {
            return sessionsByRef.values().stream().filter(s -> s.sessionId().equals(sessionId)).findFirst();
        }
    }

    /**
     * Plays the customer's action on the checkout page: settles the PENDING session {@code sessionId} as
     * {@code outcome} (SUCCEEDED, FAILED or EXPIRED) and returns the event the gateway sends for it. A success needs a
     * {@code method} and gets a new gateway transaction id.
     *
     * @throws IllegalArgumentException unknown session, an outcome that is not final, or a success without a method
     * @throws IllegalStateException    the session is no longer PENDING
     */
    public GatewayEvent complete(String sessionId, GatewayStatus outcome, Method method) {
        EventType type = switch (outcome) {
            case SUCCEEDED -> EventType.SUCCEEDED;
            case FAILED -> EventType.FAILED;
            case EXPIRED -> EventType.EXPIRED;
            default -> throw new IllegalArgumentException("Not a final outcome: " + outcome);
        };
        if (type == EventType.SUCCEEDED && method == null) {
            throw new IllegalArgumentException("A successful payment needs a method");
        }
        Session settled;
        synchronized (sessionsByRef) {
            Session session = findSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mock session " + sessionId));
            if (session.status() != GatewayStatus.PENDING) {
                throw new IllegalStateException("Mock session " + sessionId + " is already " + session.status());
            }
            String txnId = type == EventType.SUCCEEDED ? "mocktxn_" + HexFormat.of().formatHex(randomBytes(12)) : null;
            settled = session.settle(outcome, method, txnId);
            sessionsByRef.put(session.paymentRef(), settled);
        }
        return new GatewayEvent("evt_" + HexFormat.of().formatHex(randomBytes(16)), type, settled.paymentRef(),
                settled.sessionId(), settled.gatewayTxnId(), settled.amount(), settled.currency(), settled.method(),
                type == EventType.FAILED ? "declined" : null, clock.instant());
    }

    /** The {@code GatewayWebhookEvent} JSON of {@code event}, signed now. */
    public SignedWebhook signedWebhook(GatewayEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("type", event.type().wireName());
        body.put("paymentReference", event.paymentReference());
        body.put("sessionId", event.sessionId());
        body.put("gatewayTransactionId", event.gatewayTransactionId());
        body.put("amount", event.amount() == null ? null : event.amount().setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString());
        body.put("currency", event.currency());
        body.put("method", event.method() == null ? null : event.method().name());
        body.put("failureReason", event.failureReason());
        body.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        try {
            byte[] raw = objectMapper.writeValueAsBytes(body);
            return new SignedWebhook(raw, signatureHeader(clock.instant().getEpochSecond(), raw));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise a mock webhook", e);
        }
    }

    /** The {@link #SIGNATURE_HEADER} value for {@code rawBody} signed at {@code epochSeconds}. */
    public String signatureHeader(long epochSeconds, byte[] rawBody) {
        return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(hmac(epochSeconds, rawBody));
    }

    public Mode mode() {
        return mode;
    }

    /** Switches the behaviour of following calls (tests, dev). */
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NORMAL : mode;
    }

    /** Forgets every session and returns to {@link Mode#NORMAL} (tests). */
    public void reset() {
        mode = Mode.NORMAL;
        synchronized (sessionsByRef) {
            sessionsByRef.clear();
        }
    }

    int size() {
        synchronized (sessionsByRef) {
            return sessionsByRef.size();
        }
    }

    // ------------------------------------------------------------------ internals

    private void verifySignature(String header, byte[] rawBody) {
        if (header == null || header.isBlank()) {
            throw badSignature("missing " + SIGNATURE_HEADER);
        }
        Long timestamp = null;
        byte[] signature = null;
        for (String part : header.split(",")) {
            String item = part.trim();
            try {
                if (item.startsWith("t=") && timestamp == null) {
                    timestamp = Long.parseLong(item.substring(2));
                } else if (item.startsWith("v1=") && signature == null) {
                    signature = HexFormat.of().parseHex(item.substring(3));
                }
            } catch (IllegalArgumentException e) {
                throw badSignature("malformed " + SIGNATURE_HEADER);
            }
        }
        if (timestamp == null || signature == null) {
            throw badSignature("malformed " + SIGNATURE_HEADER);
        }
        if (!MessageDigest.isEqual(hmac(timestamp, rawBody), signature)) {
            throw badSignature("signature does not match");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
        if (age.compareTo(config.signatureTolerance()) > 0) {
            throw new WebhookSignatureException(WebhookSignatureException.Reason.STALE,
                    "Webhook signature timestamp outside the tolerance by " + age);
        }
    }

    private static WebhookSignatureException badSignature(String why) {
        return new WebhookSignatureException(WebhookSignatureException.Reason.BAD_SIGNATURE, "Webhook rejected: " + why);
    }

    private byte[] hmac(long epochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private GatewayEvent parseEvent(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new WebhookPayloadException("Webhook body is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new WebhookPayloadException("Webhook body is not a JSON object");
        }
        String eventId = requiredText(root, "eventId", MAX_ID_LENGTH);
        String typeName = requiredText(root, "type", MAX_ID_LENGTH);
        EventType type = EventType.fromWireName(typeName);
        if (type == null) {
            throw new WebhookPayloadException("Unknown webhook type " + typeName);
        }
        String paymentReference = requiredText(root, "paymentReference", MAX_REFERENCE_LENGTH);
        String sessionId = requiredText(root, "sessionId", MAX_ID_LENGTH);
        String txnId = optionalText(root, "gatewayTransactionId", MAX_ID_LENGTH);
        String amountText = requiredText(root, "amount", 16);
        if (!MONEY.matcher(amountText).matches()) {
            throw new WebhookPayloadException("Webhook amount is not Money");
        }
        String currency = requiredText(root, "currency", 3);
        if (!"INR".equals(currency)) {
            throw new WebhookPayloadException("Webhook currency must be INR");
        }
        String methodName = optionalText(root, "method", 16);
        Method method;
        try {
            method = methodName == null ? null : Method.valueOf(methodName);
        } catch (IllegalArgumentException e) {
            throw new WebhookPayloadException("Unknown webhook method " + methodName);
        }
        String failureReason = optionalText(root, "failureReason", MAX_FAILURE_REASON_LENGTH);
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(requiredText(root, "occurredAt", 40));
        } catch (DateTimeParseException e) {
            throw new WebhookPayloadException("Webhook occurredAt is not a date-time");
        }
        if (type == EventType.SUCCEEDED && (txnId == null || method == null)) {
            throw new WebhookPayloadException("payment.succeeded needs gatewayTransactionId and method");
        }
        return new GatewayEvent(eventId, type, paymentReference, sessionId, txnId, new BigDecimal(amountText),
                currency, method, failureReason, occurredAt);
    }

    private static String requiredText(JsonNode root, String field, int maxLength) {
        String value = optionalText(root, field, maxLength);
        if (value == null || value.isBlank()) {
            throw new WebhookPayloadException("Webhook field " + field + " is required");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new WebhookPayloadException("Webhook field " + field + " must be a string");
        }
        if (node.textValue().length() > maxLength) {
            throw new WebhookPayloadException("Webhook field " + field + " is longer than " + maxLength);
        }
        return node.textValue();
    }

    private void timeOut(String call, String paymentRef) {
        try {
            Thread.sleep(config.timeout().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new GatewayTimeoutException("Mock gateway " + call + " for " + paymentRef + " timed out after "
                + config.timeout());
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }
}
