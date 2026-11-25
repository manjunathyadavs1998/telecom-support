package com.telecom.support.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.payment.MockPaymentGateway.Mode;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.support.AbstractMySqlIT;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * {@link PaymentReconciliationJob} against the mock gateway (design 02-customer backend §3.1, §8, §9; CUS-07.5,
 * CUS-08.4/08.5, NFR-AVAIL). The job is a scheduled component without an endpoint, so it has no 401/403 cases; its
 * schedules are disabled in tests and each test calls a run directly. Time moves only through {@link PaymentTestClock}
 * (shared with {@code PaymentWebhookIT}, one context); payment ages are set relative to it. Each test creates its own
 * invoices ({@code INV-T-R…}, customer 1) and payments ({@code PAY-TR-…}).
 */
class PaymentReconciliationJobIT extends AbstractMySqlIT {

    private static final String CUSTOMER_1 = "+919900000001";
    private static final Instant T0 = PaymentTestClock.T0;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @TestBean(name = "clock", methodName = "com.telecom.support.payment.PaymentTestClock#clock")
    Clock clock;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockPaymentGateway gateway;

    @Autowired
    PaymentReconciliationJob job;

    @BeforeEach
    void setUp() {
        PaymentTestClock.CLOCK.set(T0);
        gateway.reset();
    }

    @AfterEach
    void cleanUp() {
        PaymentTestClock.CLOCK.set(T0);
        gateway.reset();
        jdbc.update("""
                DELETE p FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE i.invoice_ref LIKE 'INV-T-R%'
                """, Map.of());
        jdbc.update("DELETE FROM invoices WHERE invoice_ref LIKE 'INV-T-R%'", Map.of());
    }

    // ------------------------------------------------------------------ PENDING

    @Test
    void pendingPaidAtTheGatewayBecomesSucceeded() {
        Pay pay = pendingWithSession(Duration.ofMinutes(5));
        gateway.complete(pay.session, GatewayStatus.SUCCEEDED, Method.UPI);

        job.reconcilePending();

        Map<String, Object> row = paymentRow(pay);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("method")).isEqualTo("UPI");
        assertThat(row.get("gateway_txn_id")).asString().startsWith("mocktxn_");
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
        assertThat(auditDetails("PAYMENT_SUCCEEDED", pay)).contains("\"source\": \"reconcile\"");
    }

    @Test
    void pendingWhoseInitiateTimedOutIsResolvedByReference() {
        // initiate timed out: no session stored, but the gateway opened one for the reference
        Pay pay = pending(Duration.ofMinutes(3), null);
        String sessionId = gateway.createCheckout(pay.ref, new BigDecimal("399.00"), "INR", "http://localhost/r")
                .sessionId();
        gateway.complete(sessionId, GatewayStatus.SUCCEEDED, Method.CARD);

        job.reconcilePending();

        assertThat(paymentRow(pay).get("status")).isEqualTo("SUCCEEDED");
        assertThat(paymentRow(pay).get("gateway_session_id")).isEqualTo(sessionId);
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
    }

    @Test
    void pendingYoungerThanTwoMinutesIsNotPolled() {
        Pay pay = pendingWithSession(Duration.ofSeconds(90));
        gateway.complete(pay.session, GatewayStatus.SUCCEEDED, Method.UPI);

        job.reconcilePending();

        assertThat(paymentRow(pay).get("status")).isEqualTo("PENDING");
    }

    @Test
    void gatewayFailedOrExpiredMovesPending() {
        Pay failed = pendingWithSession(Duration.ofMinutes(5));
        Pay expired = pendingWithSession(Duration.ofMinutes(5));
        gateway.complete(failed.session, GatewayStatus.FAILED, null);
        gateway.complete(expired.session, GatewayStatus.EXPIRED, null);

        job.reconcilePending();

        assertThat(paymentRow(failed).get("status")).isEqualTo("FAILED");
        assertThat(paymentRow(expired).get("status")).isEqualTo("EXPIRED");
        assertThat(auditDetails("PAYMENT_EXPIRED", expired)).contains("\"source\": \"reconcile\"");
    }

    @Test
    void unreachableGatewayLeavesPendingUntouched() {
        Pay pay = pendingWithSession(Duration.ofHours(23));
        gateway.setMode(Mode.UNAVAILABLE);

        PaymentReconciliationJob.RunResult result = job.reconcilePending();

        assertThat(result.unreachable()).isPositive();
        assertThat(paymentRow(pay).get("status")).isEqualTo("PENDING");
        assertThat(auditCount(pay)).isZero();
    }

    @Test
    void notFoundFailsOnlyAfterTenMinutes() {
        Pay young = pending(Duration.ofMinutes(9), null);
        Pay old = pending(Duration.ofMinutes(11), null);

        job.reconcilePending();

        assertThat(paymentRow(young).get("status")).isEqualTo("PENDING");
        Map<String, Object> row = paymentRow(old);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("not_created");
    }

    @Test
    void unknownAfterTwentyFourHoursGoesToReviewNeverExpired() {
        Pay sessionExpired = pendingWithSession(Duration.ofHours(25));
        Pay stillPending = pending(Duration.ofHours(25), null);
        // the gateway still reports the reference PENDING (session opened now, not yet expired)
        gateway.createCheckout(stillPending.ref, new BigDecimal("399.00"), "INR", "http://localhost/r");

        job.reconcilePending(); // NORMAL: stillPending is polled; sessionExpired's session (opened 25 h ago) has expired
        gateway.setMode(Mode.UNAVAILABLE);
        Pay overdue = pending(Duration.ofHours(24).plusMinutes(1), null);
        job.reconcilePending();

        Map<String, Object> review = paymentRow(stillPending);
        assertThat(review.get("status")).isEqualTo("REVIEW");
        assertThat(review.get("failure_reason")).isEqualTo("unreconciled");
        assertThat(review.get("completed_at")).isNull();
        Map<String, Object> unreachableRow = paymentRow(overdue);
        assertThat(unreachableRow.get("status")).isEqualTo("REVIEW");
        assertThat(unreachableRow.get("failure_reason")).isEqualTo("unreconciled");
        assertThat(auditDetails("PAYMENT_REVIEW", overdue)).contains("\"source\": \"reconcile\"");
        // an expiry the gateway itself reports is allowed; only unknown states are never turned into EXPIRED
        assertThat(paymentRow(sessionExpired).get("status")).isEqualTo("EXPIRED");
        assertThat(invoiceStatus(overdue)).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ late capture (FAILED / EXPIRED)

    @Test
    void lostWebhookLateCaptureOnExpiredBecomesSucceeded() {
        Pay pay = finished("EXPIRED", Duration.ofHours(2));
        gateway.complete(pay.session, GatewayStatus.SUCCEEDED, Method.UPI);

        job.reconcilePending(); // not PENDING: untouched by the pending run
        assertThat(paymentRow(pay).get("status")).isEqualTo("EXPIRED");
        job.recheckLateCaptures();

        assertThat(paymentRow(pay).get("status")).isEqualTo("SUCCEEDED");
        assertThat(invoiceStatus(pay)).isEqualTo("PAID");
        assertThat(auditDetails("PAYMENT_LATE_CAPTURE", pay)).contains("\"source\": \"reconcile\"",
                "\"from\": \"EXPIRED\"");
    }

    @Test
    void lateCaptureOnFailedWithinTheWindowOnly() {
        Pay recent = finished("FAILED", Duration.ofHours(23));
        Pay old = finished("FAILED", Duration.ofHours(25));
        gateway.complete(recent.session, GatewayStatus.SUCCEEDED, Method.CARD);
        gateway.complete(old.session, GatewayStatus.SUCCEEDED, Method.CARD);

        job.recheckLateCaptures();

        assertThat(paymentRow(recent).get("status")).isEqualTo("SUCCEEDED");
        assertThat(paymentRow(old).get("status")).isEqualTo("FAILED");
        assertThat(invoiceStatus(old)).isEqualTo("OPEN");
    }

    @Test
    void lateCaptureCheckLeavesFailedWhenGatewayUnreachableOrNotPaid() {
        Pay notPaid = finished("FAILED", Duration.ofHours(1));
        gateway.complete(notPaid.session, GatewayStatus.FAILED, null);

        job.recheckLateCaptures();
        gateway.setMode(Mode.UNAVAILABLE);
        job.recheckLateCaptures();

        assertThat(paymentRow(notPaid).get("status")).isEqualTo("FAILED");
        assertThat(auditCount(notPaid)).isZero();
    }

    // ------------------------------------------------------------------ races

    @Test
    void twoRunsInParallelCreditOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Pay pending = pendingWithSession(Duration.ofMinutes(5));
            Pay expired = finished("EXPIRED", Duration.ofHours(1));
            gateway.complete(pending.session, GatewayStatus.SUCCEEDED, Method.UPI);
            gateway.complete(expired.session, GatewayStatus.SUCCEEDED, Method.UPI);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> runs = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    runs.add(pool.submit(() -> {
                        start.await();
                        return job.reconcilePending();
                    }));
                    runs.add(pool.submit(() -> {
                        start.await();
                        return job.recheckLateCaptures();
                    }));
                }
                start.countDown();
                for (Future<?> run : runs) {
                    run.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            for (Pay pay : List.of(pending, expired)) {
                assertThat(paymentRow(pay).get("status")).isEqualTo("SUCCEEDED");
                assertThat(invoiceAmountPaid(pay)).isEqualByComparingTo("399.00");
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_SUCCEEDED' AND entity_id = :ref
                        """, Map.of("ref", pay.ref), Long.class)).isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Pay(String ref, long invoiceId, String session) {}

    /** A PENDING payment initiated {@code age} before T0 whose gateway session was opened then. */
    private Pay pendingWithSession(Duration age) {
        PaymentTestClock.CLOCK.set(T0.minus(age));
        try {
            Pay pay = pending(age, null);
            String sessionId = gateway.createCheckout(pay.ref, new BigDecimal("399.00"), "INR", "http://localhost/r")
                    .sessionId();
            jdbc.update("UPDATE payments SET gateway_session_id = :sid WHERE reference = :ref",
                    Map.of("sid", sessionId, "ref", pay.ref));
            return new Pay(pay.ref, pay.invoiceId, sessionId);
        } finally {
            PaymentTestClock.CLOCK.set(T0);
        }
    }

    private Pay pending(Duration age, String sessionId) {
        return insert("PENDING", T0.minus(age), null, sessionId);
    }

    /** A FAILED/EXPIRED payment completed {@code completedAgo} before T0, with an open (PENDING) gateway session. */
    private Pay finished(String status, Duration completedAgo) {
        Pay pay = insert(status, T0.minus(completedAgo).minus(Duration.ofMinutes(15)), T0.minus(completedAgo), null);
        String sessionId = gateway.createCheckout(pay.ref, new BigDecimal("399.00"), "INR", "http://localhost/r")
                .sessionId();
        jdbc.update("UPDATE payments SET gateway_session_id = :sid WHERE reference = :ref",
                Map.of("sid", sessionId, "ref", pay.ref));
        return new Pay(pay.ref, pay.invoiceId, sessionId);
    }

    /** An OPEN 399.00 invoice of customer 1 with one 399.00 payment. */
    private Pay insert(String status, Instant initiatedAt, Instant completedAt, String sessionId) {
        int n = SEQ.incrementAndGet();
        String invoiceRef = "INV-T-R" + String.format("%05d", n);
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                      amount_due, amount_paid, status, created_at, updated_at)
                SELECT a.id, :ref, CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
                       CURRENT_DATE + INTERVAL 15 DAY, 399.00, 0.00, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM accounts a WHERE a.account_ref = 'ACC-T-0001'
                """, Map.of("ref", invoiceRef));
        long invoiceId = jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref",
                Map.of("ref", invoiceRef), Long.class);
        String ref = "PAY-TR-" + String.format("%06d", n);
        jdbc.update("""
                INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, gateway_session_id,
                                      initiated_by_user_id, initiated_at, completed_at, created_at, updated_at)
                SELECT :ref, i.account_id, i.id, 399.00, 'INR', :status, :sid, u.id, :initiatedAt, :completedAt,
                       :initiatedAt, :initiatedAt
                FROM invoices i JOIN users u ON u.username = :username WHERE i.id = :invoiceId
                """, new MapSqlParameterSource()
                .addValue("ref", ref)
                .addValue("status", status)
                .addValue("sid", sessionId)
                .addValue("initiatedAt", utc(initiatedAt))
                .addValue("completedAt", completedAt == null ? null : utc(completedAt))
                .addValue("username", CUSTOMER_1)
                .addValue("invoiceId", invoiceId));
        return new Pay(ref, invoiceId, sessionId);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Map<String, Object> paymentRow(Pay pay) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE reference = :ref", Map.of("ref", pay.ref));
    }

    private String invoiceStatus(Pay pay) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE id = :id", Map.of("id", pay.invoiceId),
                String.class);
    }

    private BigDecimal invoiceAmountPaid(Pay pay) {
        return jdbc.queryForObject("SELECT amount_paid FROM invoices WHERE id = :id", Map.of("id", pay.invoiceId),
                BigDecimal.class);
    }

    private long auditCount(Pay pay) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_id = :ref",
                Map.of("ref", pay.ref), Long.class);
    }

    private String auditDetails(String action, Pay pay) {
        return jdbc.queryForObject("""
                SELECT CAST(details AS CHAR) FROM audit_events WHERE action = :action AND entity_id = :id
                ORDER BY id DESC LIMIT 1
                """, Map.of("action", action, "id", pay.ref), String.class);
    }
}
