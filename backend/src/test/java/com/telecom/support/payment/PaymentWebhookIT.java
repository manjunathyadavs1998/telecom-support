package com.telecom.support.payment;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.payment.PaymentGateway.EventType;
import com.telecom.support.payment.PaymentGateway.GatewayEvent;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Result;
import com.telecom.support.payment.PaymentTransitionService.Source;
import com.telecom.support.support.AbstractMySqlIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E18 signed webhook end to end (design 02-customer backend §3.1, §4, §7, §8, §9; CUS-08.3/08.4, CUS-07.5, BR-08,
 * NFR-SEC, NFR-INTEG). The endpoint is public and CSRF-exempt by design (authenticated only by its HMAC), so it has no
 * session 401/403 cases: every request here is sent without a session or CSRF token. Events are signed with the mock
 * gateway's own key; time moves only through {@link PaymentTestClock}. Each test creates its own invoices
 * ({@code INV-T-W…}, customer 1) and payments ({@code PAY-TW-…}) and uses its own client IPs.
 */
class PaymentWebhookIT extends AbstractMySqlIT {

    private static final String PATH = PaymentWebhookController.PATH;
    private static final String CUSTOMER_1 = "+919900000001";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger IPS = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.payment.PaymentTestClock#clock")
    Clock clock;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockPaymentGateway gateway;

    @Autowired
    WebhookRateLimiter limiter;

    @Autowired
    PaymentTransitionService transitions;

    @BeforeEach
    void setUp() {
        PaymentTestClock.CLOCK.set(PaymentTestClock.T0);
        limiter.reset();
    }

    @AfterEach
    void cleanUp() {
        gateway.reset();
        jdbc.update("""
                DELETE e FROM payment_gateway_events e JOIN payments p ON p.id = e.payment_id
                JOIN invoices i ON i.id = p.invoice_id WHERE i.invoice_ref LIKE 'INV-T-W%'
                """, Map.of());
        jdbc.update("DELETE FROM payment_gateway_events WHERE gateway_event_id LIKE 'evt_tw_%'", Map.of());
        jdbc.update("""
                DELETE p FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE i.invoice_ref LIKE 'INV-T-W%'
                """, Map.of());
        jdbc.update("DELETE FROM invoices WHERE invoice_ref LIKE 'INV-T-W%'", Map.of());
    }

    // ------------------------------------------------------------------ applied events

    @Test
    void validSuccessMarksSucceededPaysInvoiceAndAudits() throws Exception {
        Pay pay = payment("PENDING");

        MvcResult result = send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip())
                .andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> row = paymentRow(pay.ref);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("method")).isEqualTo("UPI");
        assertThat(row.get("gateway_txn_id")).isEqualTo("txn-" + pay.ref);
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
        assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_SUCCEEDED", pay.ref)).contains("\"source\": \"webhook\"",
                "\"from\": \"PENDING\"", "\"to\": \"SUCCEEDED\"");
        assertThat(events(pay)).containsExactly("APPLIED");
    }

    @Test
    void mockCheckoutPayEventIsAccepted() throws Exception {
        // the event exactly as the mock checkout page posts it to app.payment.mock.webhook-url (B19)
        Pay pay = payment("PENDING", null);
        String sessionId = gateway.createCheckout(pay.ref, new BigDecimal("399.00"), "INR", "http://localhost/r")
                .sessionId();
        jdbc.update("UPDATE payments SET gateway_session_id = :sid WHERE reference = :ref",
                Map.of("sid", sessionId, "ref", pay.ref));
        GatewayEvent event = gateway.complete(sessionId, PaymentGateway.GatewayStatus.SUCCEEDED, Method.CARD);

        send(gateway.signedWebhook(event), ip()).andExpect(status().isNoContent());

        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
    }

    @Test
    void failedAndExpiredEventsMovePendingPayments() throws Exception {
        Pay failed = payment("PENDING");
        Pay expired = payment("PENDING");

        send(sign(event(failed, EventType.FAILED, null, null, "399.00", failed.session, "declined")), ip())
                .andExpect(status().isNoContent());
        send(sign(event(expired, EventType.EXPIRED, null, null, "399.00", expired.session, null)), ip())
                .andExpect(status().isNoContent());

        assertThat(paymentRow(failed.ref).get("status")).isEqualTo("FAILED");
        assertThat(paymentRow(failed.ref).get("failure_reason")).isEqualTo("declined");
        assertThat(paymentRow(expired.ref).get("status")).isEqualTo("EXPIRED");
        assertThat(auditCount("PAYMENT_FAILED", failed.ref)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_EXPIRED", expired.ref)).isEqualTo(1);
        assertThat(invoiceStatus(failed)).isEqualTo("OPEN");
    }

    @Test
    void successAfterFailedIsALateCapture() throws Exception {
        Pay pay = payment("FAILED");

        send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip()).andExpect(status().isNoContent());

        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
        assertThat(auditCount("PAYMENT_LATE_CAPTURE", pay.ref)).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_LATE_CAPTURE", pay.ref)).contains("\"from\": \"FAILED\"");
    }

    @Test
    void sessionOrAmountMismatchGoesToReviewWithoutCredit() throws Exception {
        Pay wrongSession = payment("PENDING");
        Pay wrongAmount = payment("PENDING");

        send(sign(event(wrongSession, EventType.SUCCEEDED, "txn-a", Method.UPI, "399.00", "other-session", null)),
                ip()).andExpect(status().isNoContent());
        send(sign(event(wrongAmount, EventType.SUCCEEDED, "txn-b", Method.UPI, "1.00", wrongAmount.session, null)),
                ip()).andExpect(status().isNoContent());

        for (Pay pay : List.of(wrongSession, wrongAmount)) {
            Map<String, Object> row = paymentRow(pay.ref);
            assertThat(row.get("status")).isEqualTo("REVIEW");
            assertThat(row.get("failure_reason")).isEqualTo("amount_mismatch");
            assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("0.00");
            assertThat(auditCount("PAYMENT_REVIEW", pay.ref)).isEqualTo(1);
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isZero();
        }
    }

    // ------------------------------------------------------------------ no-ops

    @Test
    void duplicateEventIdIsANoOp() throws Exception {
        Pay pay = payment("PENDING");
        MockPaymentGateway.SignedWebhook webhook = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));

        send(webhook, ip()).andExpect(status().isNoContent());
        send(webhook, ip()).andExpect(status().isNoContent());

        assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("399.00");
        assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_EVENT_IGNORED", pay.ref)).isZero();
        assertThat(events(pay)).containsExactly("APPLIED");
    }

    @Test
    void sameEventDeliveredConcurrentlyIsProcessedOnce() throws Exception {
        // review C22(b): the second delivery waits on the event id's unique key, then no-ops
        for (int round = 0; round < 3; round++) {
            Pay pay = payment("PENDING");
            MockPaymentGateway.SignedWebhook webhook = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));

            List<Integer> statuses = inParallel(
                    () -> send(webhook, ip()).andReturn().getResponse().getStatus(),
                    () -> send(webhook, ip()).andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(204);
            assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("399.00");
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
            assertThat(events(pay)).containsExactly("APPLIED");
        }
    }

    @Test
    void failedAfterSucceededIsIgnored() throws Exception {
        Pay pay = payment("PENDING");
        send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip()).andExpect(status().isNoContent());

        send(sign(event(pay, EventType.FAILED, null, null, "399.00", pay.session, "declined")), ip())
                .andExpect(status().isNoContent());

        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
        assertThat(auditCount("PAYMENT_EVENT_IGNORED", pay.ref)).isEqualTo(1);
        assertThat(events(pay)).containsExactlyInAnyOrder("APPLIED", "IGNORED");
    }

    @Test
    void unknownPaymentIsAcknowledgedAndRejected() throws Exception {
        String eventId = "evt_tw_" + SEQ.incrementAndGet();
        GatewayEvent event = new GatewayEvent(eventId, EventType.SUCCEEDED, "PAY-TW-NOPE", "sess-nope", "txn-nope",
                new BigDecimal("399.00"), "INR", Method.UPI, null, PaymentTestClock.T0);

        send(sign(event), ip()).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM payment_gateway_events WHERE gateway_event_id = :id AND payment_id IS NULL
                """, Map.of("id", eventId), String.class)).isEqualTo("REJECTED");
        assertThat(auditCount("PAYMENT_WEBHOOK_REJECTED", "PAY-TW-NOPE")).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_WEBHOOK_REJECTED", "PAY-TW-NOPE")).contains("\"reason\": \"unknown_payment\"");
    }

    // ------------------------------------------------------------------ races (one credit)

    @Test
    void twoDistinctSuccessEventsCreditOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Pay pay = payment("PENDING");
            MockPaymentGateway.SignedWebhook first = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));
            MockPaymentGateway.SignedWebhook second = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));

            List<Integer> statuses = inParallel(
                    () -> send(first, ip()).andReturn().getResponse().getStatus(),
                    () -> send(second, ip()).andReturn().getResponse().getStatus());

            assertThat(statuses).containsOnly(204);
            assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("399.00");
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
            assertThat(events(pay)).containsExactlyInAnyOrder("APPLIED", "IGNORED");
        }
    }

    @Test
    void webhookAndReconcileConcurrentlyCreditOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Pay pay = payment("PENDING");
            MockPaymentGateway.SignedWebhook webhook = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));
            Capture capture = new Capture(pay.session, new BigDecimal("399.00"), "INR", Method.UPI, "txn-" + pay.ref);

            List<Object> outcomes = inParallel(
                    () -> send(webhook, ip()).andReturn().getResponse().getStatus(),
                    () -> transitions.succeeded(pay.ref, capture, Source.RECONCILE).kind());

            assertThat(outcomes.get(0)).isEqualTo(204);
            assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
            assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("399.00");
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
            boolean reconcileWon = outcomes.get(1) == Result.Kind.APPLIED;
            assertThat(events(pay)).containsExactly(reconcileWon ? "IGNORED" : "APPLIED");
        }
    }

    // ------------------------------------------------------------------ verification order (§8)

    @Test
    void badSignatureIs401EvenOnAMalformedBody() throws Exception {
        byte[] malformed = "{not json".getBytes(StandardCharsets.UTF_8);
        long now = PaymentTestClock.T0.getEpochSecond();
        String forged = "t=" + now + ",v1=" + "ab".repeat(32);

        assertProblem(send(malformed, forged, ip()).andReturn(), 401, "unauthenticated", PATH);
        assertProblem(send(malformed, null, ip()).andReturn(), 401, "unauthenticated", PATH);
        assertProblem(send(malformed, "garbage", ip()).andReturn(), 401, "unauthenticated", PATH);
        // the same body correctly signed is past the signature check and fails validation
        assertProblem(send(malformed, gateway.signatureHeader(now, malformed), ip()).andReturn(), 400,
                "bad-request", PATH);
    }

    @Test
    void validSignatureOnATamperedBodyIs401() throws Exception {
        Pay pay = payment("PENDING");
        MockPaymentGateway.SignedWebhook webhook = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));
        byte[] tampered = new String(webhook.body(), StandardCharsets.UTF_8).replace("399.00", "1.00")
                .getBytes(StandardCharsets.UTF_8);

        assertProblem(send(tampered, webhook.signature(), ip()).andReturn(), 401, "unauthenticated", PATH);
        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("PENDING");
        assertThat(events(pay)).isEmpty();
    }

    @Test
    void staleTimestampIs401AndAudited() throws Exception {
        Pay pay = payment("PENDING");
        MockPaymentGateway.SignedWebhook fresh = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet()));
        String ip = ip();
        long sixMinutesAgo = PaymentTestClock.T0.minus(Duration.ofMinutes(6)).getEpochSecond();

        assertProblem(send(fresh.body(), gateway.signatureHeader(sixMinutesAgo, fresh.body()), ip).andReturn(), 401,
                "unauthenticated", PATH);

        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("PENDING");
        assertThat(rejectedAudits(ip)).containsExactly("{\"reason\": \"stale\"}");
        // within the tolerance it is accepted
        long fourMinutesAgo = PaymentTestClock.T0.minus(Duration.ofMinutes(4)).getEpochSecond();
        send(fresh.body(), gateway.signatureHeader(fourMinutesAgo, fresh.body()), ip())
                .andExpect(status().isNoContent());
        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void successWithoutTransactionIdOrMethodIs400() throws Exception {
        Pay pay = payment("PENDING");

        assertProblem(send(sign(event(pay, EventType.SUCCEEDED, null, Method.UPI, "399.00", pay.session, null)), ip())
                .andReturn(), 400, "bad-request", PATH);
        assertProblem(send(sign(event(pay, EventType.SUCCEEDED, "txn-x", null, "399.00", pay.session, null)), ip())
                .andReturn(), 400, "bad-request", PATH);

        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("PENDING");
        assertThat(events(pay)).isEmpty();
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        assertProblem(mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content("x")).andReturn(), 415,
                "unsupported-media-type", PATH);
    }

    @Test
    void publicAndCsrfExemptionCoversOnlyPostOnTheWebhookPath() throws Exception {
        // review C22(a), §4: only POST on exactly this path is public and CSRF-exempt
        Pay pay = payment("PENDING");
        byte[] body = sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())).body();

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (var request : List.of(put(PATH), delete(PATH), patch(PATH), post(PATH + "/x"),
                post("/api/v1/payment-gateway/other"), post("/api/v1/customers/me/payments"))) {
            int status = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(request.toString()).isIn(401, 403);
        }
        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ abuse controls

    @Test
    void nonJsonCallsCountTowardTheIpLimit() throws Exception {
        // review C21: the IP limit runs before the content-type check (§8 order)
        String ip = ip();
        for (int i = 0; i < 60; i++) {
            mvc.perform(post(PATH).with(r -> {
                r.setRemoteAddr(ip);
                return r;
            }).contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isUnsupportedMediaType());
        }
        Pay pay = payment("PENDING");
        assertProblem(send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip).andReturn(), 429,
                "too-many-requests", PATH);
    }

    @Test
    void rejectedAuditIsSampledOncePerIpPerMinute() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String forged = "t=" + PaymentTestClock.T0.getEpochSecond() + ",v1=" + "cd".repeat(32);
        String first = ip();
        String second = ip();

        for (int i = 0; i < 3; i++) {
            assertProblem(send(body, forged, first).andReturn(), 401, "unauthenticated", PATH);
        }
        assertProblem(send(body, forged, second).andReturn(), 401, "unauthenticated", PATH);
        assertThat(rejectedAudits(first)).containsExactly("{\"reason\": \"bad_signature\"}");
        assertThat(rejectedAudits(second)).hasSize(1);

        PaymentTestClock.CLOCK.set(PaymentTestClock.T0.plus(Duration.ofSeconds(61)));
        String forgedLater = "t=" + PaymentTestClock.CLOCK.instant().getEpochSecond() + ",v1=" + "cd".repeat(32);
        assertProblem(send(body, forgedLater, first).andReturn(), 401, "unauthenticated", PATH);
        assertThat(rejectedAudits(first)).hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_WEBHOOK_REJECTED' AND client_ip = :ip
                  AND entity_type = 'PAYMENT' AND entity_id IS NULL AND outcome = 'FAILURE'
                """, Map.of("ip", first), Long.class)).isEqualTo(2);
    }

    @Test
    void sixtyFirstCallInAMinuteIs429() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String forged = "t=" + PaymentTestClock.T0.getEpochSecond() + ",v1=" + "ef".repeat(32);
        String ip = ip();

        for (int i = 0; i < 60; i++) {
            send(body, forged, ip).andExpect(status().isUnauthorized());
        }
        MvcResult limited = send(body, forged, ip).andReturn();
        assertProblem(limited, 429, "too-many-requests", PATH);
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        // the limit is checked before the signature: a valid event from that IP is refused too
        Pay pay = payment("PENDING");
        assertProblem(send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip).andReturn(), 429,
                "too-many-requests", PATH);
        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("PENDING");

        // other IPs are unaffected; the IP may call again after the minute
        send(body, forged, ip()).andExpect(status().isUnauthorized());
        PaymentTestClock.CLOCK.set(PaymentTestClock.T0.plus(Duration.ofSeconds(60)));
        send(sign(succeeded(pay, "evt_tw_" + SEQ.incrementAndGet())), ip).andExpect(status().isNoContent());
        assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ helpers

    private record Pay(String ref, long invoiceId, String session) {}

    private Pay payment(String status) {
        return payment(status, "tw-sess-" + SEQ.incrementAndGet());
    }

    /** An OPEN 399.00 invoice of customer 1 with one 399.00 payment in {@code status}. */
    private Pay payment(String status, String sessionId) {
        int n = SEQ.incrementAndGet();
        String invoiceRef = "INV-T-W" + String.format("%05d", n);
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                      amount_due, amount_paid, status, created_at, updated_at)
                SELECT a.id, :ref, CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
                       CURRENT_DATE + INTERVAL 15 DAY, 399.00, 0.00, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM accounts a WHERE a.account_ref = 'ACC-T-0001'
                """, Map.of("ref", invoiceRef));
        long invoiceId = jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref",
                Map.of("ref", invoiceRef), Long.class);
        String ref = "PAY-TW-" + String.format("%06d", n);
        jdbc.update("""
                INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, gateway_session_id,
                                      initiated_by_user_id, initiated_at, completed_at, created_at, updated_at)
                SELECT :ref, i.account_id, i.id, 399.00, 'INR', :status, :sid, u.id,
                       UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE,
                       IF(:status = 'PENDING', NULL, UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE),
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM invoices i JOIN users u ON u.username = :username WHERE i.id = :invoiceId
                """, new MapSqlParameterSource()
                .addValue("ref", ref)
                .addValue("status", status)
                .addValue("sid", sessionId)
                .addValue("username", CUSTOMER_1)
                .addValue("invoiceId", invoiceId));
        return new Pay(ref, invoiceId, sessionId);
    }

    private static GatewayEvent succeeded(Pay pay, String eventId) {
        return new GatewayEvent(eventId, EventType.SUCCEEDED, pay.ref, pay.session, "txn-" + pay.ref,
                new BigDecimal("399.00"), "INR", Method.UPI, null, PaymentTestClock.T0);
    }

    private static GatewayEvent event(Pay pay, EventType type, String txnId, Method method, String amount,
                                      String sessionId, String failureReason) {
        return new GatewayEvent("evt_tw_" + SEQ.incrementAndGet(), type, pay.ref, sessionId, txnId,
                new BigDecimal(amount), "INR", method, failureReason, PaymentTestClock.T0);
    }

    private MockPaymentGateway.SignedWebhook sign(GatewayEvent event) {
        return gateway.signedWebhook(event);
    }

    private ResultActions send(MockPaymentGateway.SignedWebhook webhook, String ip) throws Exception {
        return send(webhook.body(), webhook.signature(), ip);
    }

    /** Posts like the gateway: no session, no CSRF token, from {@code ip}. */
    private ResultActions send(byte[] body, String signature, String ip) throws Exception {
        var request = post(PATH)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (signature != null) {
            request.header(MockPaymentGateway.SIGNATURE_HEADER, signature);
        }
        return mvc.perform(request);
    }

    @SafeVarargs
    private static <T> List<T> inParallel(Callable<? extends T>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<? extends T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String ip() {
        int n = IPS.incrementAndGet();
        return "10.18." + (n / 250) + "." + (n % 250 + 1);
    }

    private Map<String, Object> paymentRow(String reference) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE reference = :ref", Map.of("ref", reference));
    }

    private String invoiceStatus(Pay pay) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE id = :id", Map.of("id", pay.invoiceId),
                String.class);
    }

    private BigDecimal invoiceAmountPaid(Pay pay) {
        return jdbc.queryForObject("SELECT amount_paid FROM invoices WHERE id = :id", Map.of("id", pay.invoiceId),
                BigDecimal.class);
    }

    private List<String> events(Pay pay) {
        return jdbc.queryForList("""
                SELECT e.outcome FROM payment_gateway_events e JOIN payments p ON p.id = e.payment_id
                WHERE p.reference = :ref ORDER BY e.id
                """, Map.of("ref", pay.ref), String.class);
    }

    private List<String> rejectedAudits(String ip) {
        return jdbc.queryForList("""
                SELECT CAST(details AS CHAR) FROM audit_events WHERE action = 'PAYMENT_WEBHOOK_REJECTED'
                  AND client_ip = :ip ORDER BY id
                """, Map.of("ip", ip), String.class);
    }

    private long auditCount(String action, String entityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = :action AND entity_id = :id",
                Map.of("action", action, "id", entityId), Long.class);
    }

    private String auditDetails(String action, String entityId) {
        return jdbc.queryForObject("""
                SELECT CAST(details AS CHAR) FROM audit_events WHERE action = :action AND entity_id = :id
                ORDER BY id DESC LIMIT 1
                """, Map.of("action", action, "id", entityId), String.class);
    }
}
