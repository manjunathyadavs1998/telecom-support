package com.telecom.support.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Result;
import com.telecom.support.payment.PaymentTransitionService.Source;
import com.telecom.support.support.AbstractMySqlIT;
import java.math.BigDecimal;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Guarded payment transitions and the R14 invoice credit (design 02-customer backend §3.1, R13, R14, §7; CUS-07.5,
 * CUS-08.4, BR-08, NFR-INTEG). Each test creates its own invoice ({@code INV-T-Y…}, 399.00 OPEN) and payment
 * ({@code PAY-TY-…}) on customer 1's account and removes them afterwards.
 */
class PaymentTransitionIT extends AbstractMySqlIT {

    private static final BigDecimal DUE = new BigDecimal("399.00");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final List<String> STATUSES = List.of("PENDING", "SUCCEEDED", "FAILED", "EXPIRED", "REVIEW");

    @Autowired
    PaymentTransitionService transitions;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payments WHERE reference LIKE 'PAY-TY-%'", Map.of());
        jdbc.update("DELETE FROM invoices WHERE invoice_ref LIKE 'INV-T-Y%'", Map.of());
    }

    // ------------------------------------------------------------------ success + credit

    @Test
    void pendingSucceededCreditsInvoiceAndAudits() {
        Pay pay = payment("PENDING", DUE, DUE, BigDecimal.ZERO);

        Result result = transitions.succeeded(pay.ref, capture(pay, "txn-1"), Source.WEBHOOK);

        assertThat(result).isEqualTo(new Result(Result.Kind.APPLIED, "SUCCEEDED"));
        Map<String, Object> row = paymentRow(pay.ref);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("method")).isEqualTo("UPI");
        assertThat(row.get("gateway_txn_id")).isEqualTo("txn-1");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(1);
        assertInvoice(pay, "399.00", "PAID", 1);
        assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_LATE_CAPTURE", pay.ref)).isZero();
        assertThat(auditDetails("PAYMENT_SUCCEEDED", pay.ref))
                .contains("\"source\": \"webhook\"", "\"from\": \"PENDING\"", "\"to\": \"SUCCEEDED\"",
                        "\"gatewayTxnId\": \"txn-1\"");
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM audit_events WHERE action = 'PAYMENT_SUCCEEDED' AND entity_id = :ref
                """, Map.of("ref", pay.ref), String.class)).isEqualTo("SUCCESS");
    }

    @Test
    void parallelSucceededFromWebhookAndReconcileCreditsOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Pay pay = payment("PENDING", DUE, DUE, BigDecimal.ZERO);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Result> webhook = pool.submit(race(start,
                        () -> transitions.succeeded(pay.ref, capture(pay, "txn-w"), Source.WEBHOOK)));
                Future<Result> reconcile = pool.submit(race(start,
                        () -> transitions.succeeded(pay.ref, capture(pay, "txn-w"), Source.RECONCILE)));
                start.countDown();
                List<Result.Kind> kinds = List.of(webhook.get(30, TimeUnit.SECONDS).kind(),
                        reconcile.get(30, TimeUnit.SECONDS).kind());
                assertThat(kinds).containsExactlyInAnyOrder(Result.Kind.APPLIED, Result.Kind.IGNORED);
            } finally {
                pool.shutdownNow();
            }
            assertThat(paymentRow(pay.ref).get("status")).isEqualTo("SUCCEEDED");
            assertInvoice(pay, "399.00", "PAID", 1);
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
            assertThat(auditCount("PAYMENT_EVENT_IGNORED", pay.ref)).isEqualTo(1);
            assertThat(auditCount("PAYMENT_OVERPAID", pay.invoiceRef)).isZero();
        }
    }

    @Test
    void twoDistinctSuccessEventsCreditOnce() {
        Pay pay = payment("PENDING", DUE, DUE, BigDecimal.ZERO);

        Result first = transitions.succeeded(pay.ref, capture(pay, "txn-a"), Source.WEBHOOK);
        Result second = transitions.succeeded(pay.ref, capture(pay, "txn-b"), Source.WEBHOOK);

        assertThat(first.kind()).isEqualTo(Result.Kind.APPLIED);
        assertThat(second).isEqualTo(new Result(Result.Kind.IGNORED, "SUCCEEDED"));
        assertThat(paymentRow(pay.ref).get("gateway_txn_id")).isEqualTo("txn-a");
        assertInvoice(pay, "399.00", "PAID", 1);
        assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_EVENT_IGNORED", pay.ref)).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_EVENT_IGNORED", pay.ref))
                .contains("\"eventType\": \"payment.succeeded\"", "\"currentStatus\": \"SUCCEEDED\"");
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM audit_events WHERE action = 'PAYMENT_EVENT_IGNORED' AND entity_id = :ref
                """, Map.of("ref", pay.ref), String.class)).isEqualTo("FAILURE");
    }

    @Test
    void lateCaptureFromFailedAndExpiredSucceedsAndCredits() {
        for (String from : List.of("FAILED", "EXPIRED")) {
            Pay pay = payment(from, DUE, DUE, BigDecimal.ZERO);

            Result result = transitions.succeeded(pay.ref, capture(pay, "txn-late"), Source.RECONCILE);

            assertThat(result).isEqualTo(new Result(Result.Kind.APPLIED, "SUCCEEDED"));
            assertThat(paymentRow(pay.ref).get("failure_reason")).isNull();
            assertInvoice(pay, "399.00", "PAID", 1);
            assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isEqualTo(1);
            assertThat(auditCount("PAYMENT_LATE_CAPTURE", pay.ref)).isEqualTo(1);
            assertThat(auditDetails("PAYMENT_LATE_CAPTURE", pay.ref))
                    .contains("\"source\": \"reconcile\"", "\"from\": \"" + from + "\"");
        }
    }

    @Test
    void emptyStoredSessionIsFilledFromTheCapture() {
        Pay pay = payment("PENDING", DUE, DUE, BigDecimal.ZERO, null);

        Result result = transitions.succeeded(pay.ref,
                new Capture("sess-late", DUE, "INR", Method.CARD, "txn-t"), Source.RECONCILE);

        assertThat(result.kind()).isEqualTo(Result.Kind.APPLIED);
        assertThat(paymentRow(pay.ref).get("gateway_session_id")).isEqualTo("sess-late");
        assertInvoice(pay, "399.00", "PAID", 1);
    }

    @Test
    void creditKeepsDecimalMoneyAndOverpaymentIsCreditedAndAudited() {
        Pay partial = payment("PENDING", new BigDecimal("100.50"), DUE, BigDecimal.ZERO);
        transitions.succeeded(partial.ref, capture(partial, "txn-p"), Source.WEBHOOK);
        assertInvoice(partial, "100.50", "OPEN", 1);

        Pay over = payment("PENDING", DUE, DUE, new BigDecimal("200.25"));
        transitions.succeeded(over.ref, capture(over, "txn-o"), Source.WEBHOOK);
        assertInvoice(over, "599.25", "PAID", 1);
        assertThat(auditCount("PAYMENT_OVERPAID", over.invoiceRef)).isEqualTo(1);
        assertThat(auditDetails("PAYMENT_OVERPAID", over.invoiceRef))
                .contains("\"amountDue\": \"399.00\"", "\"amountPaid\": \"599.25\"");
    }

    // ------------------------------------------------------------------ mismatch → REVIEW

    @Test
    void amountCurrencyOrSessionMismatchGoesToReviewWithoutCredit() {
        List<Function<Pay, Capture>> mismatches = List.of(
                p -> new Capture(p.session, new BigDecimal("398.99"), "INR", Method.UPI, "txn-m"),
                p -> new Capture(p.session, p.amount, "USD", Method.UPI, "txn-m"),
                p -> new Capture("other-session", p.amount, "INR", Method.UPI, "txn-m"));
        for (Function<Pay, Capture> mismatch : mismatches) {
            for (String from : List.of("PENDING", "FAILED", "EXPIRED")) {
                Pay pay = payment(from, DUE, DUE, BigDecimal.ZERO);
                Object completedBefore = paymentRow(pay.ref).get("completed_at");

                Result result = transitions.succeeded(pay.ref, mismatch.apply(pay), Source.WEBHOOK);

                assertThat(result).isEqualTo(new Result(Result.Kind.APPLIED, "REVIEW"));
                Map<String, Object> row = paymentRow(pay.ref);
                assertThat(row.get("status")).isEqualTo("REVIEW");
                assertThat(row.get("failure_reason")).isEqualTo("amount_mismatch");
                assertThat(row.get("gateway_session_id")).isEqualTo(pay.session);
                assertThat(row.get("completed_at")).isEqualTo(completedBefore);
                assertInvoice(pay, "0.00", "OPEN", 0);
                assertThat(auditCount("PAYMENT_REVIEW", pay.ref)).isEqualTo(1);
                assertThat(auditCount("PAYMENT_SUCCEEDED", pay.ref)).isZero();
            }
        }
    }

    @Test
    void mismatchedLateCaptureGoesToReviewEvenWithASiblingPendingPayment() {
        // Erratum E1: the open-payment key covers PENDING only, so REVIEW is applied next to a PENDING sibling.
        Pay failed = payment("FAILED", DUE, DUE, BigDecimal.ZERO);
        String siblingRef = "PAY-TY-" + SEQ.incrementAndGet();
        insertPayment(siblingRef, failed.invoiceId, "PENDING", DUE, "y-sess-" + SEQ.incrementAndGet());

        Result result = transitions.succeeded(failed.ref,
                new Capture(failed.session, new BigDecimal("398.99"), "INR", Method.UPI, "txn-sib"), Source.WEBHOOK);

        assertThat(result).isEqualTo(new Result(Result.Kind.APPLIED, "REVIEW"));
        assertThat(paymentRow(failed.ref)).containsEntry("status", "REVIEW")
                .containsEntry("failure_reason", "amount_mismatch");
        assertThat(paymentRow(siblingRef)).containsEntry("status", "PENDING");
        assertInvoice(failed, "0.00", "OPEN", 0);
        assertThat(auditCount("PAYMENT_REVIEW", failed.ref)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_SUCCEEDED", failed.ref)).isZero();
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    void pendingFailuresAndReconcileOutcomes() {
        Pay failed = payment("PENDING", DUE, DUE, BigDecimal.ZERO);
        assertThat(transitions.failed(failed.ref, "declined", Method.CARD, Source.WEBHOOK))
                .isEqualTo(new Result(Result.Kind.APPLIED, "FAILED"));
        assertThat(paymentRow(failed.ref)).containsEntry("failure_reason", "declined").containsEntry("method", "CARD");
        assertThat(paymentRow(failed.ref).get("completed_at")).isNotNull();
        assertThat(auditCount("PAYMENT_FAILED", failed.ref)).isEqualTo(1);

        Pay refused = payment("PENDING", DUE, DUE, BigDecimal.ZERO, null);
        transitions.failed(refused.ref, "gateway_unavailable", null, Source.INITIATE);
        assertThat(paymentRow(refused.ref)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "gateway_unavailable");
        assertThat(auditDetails("PAYMENT_FAILED", refused.ref)).contains("\"source\": \"initiate\"");

        Pay expired = payment("PENDING", DUE, DUE, BigDecimal.ZERO);
        assertThat(transitions.expired(expired.ref, Source.WEBHOOK).status()).isEqualTo("EXPIRED");
        assertThat(auditCount("PAYMENT_EXPIRED", expired.ref)).isEqualTo(1);

        Pay notCreated = payment("PENDING", DUE, DUE, BigDecimal.ZERO, null);
        assertThat(transitions.notCreated(notCreated.ref).status()).isEqualTo("FAILED");
        assertThat(paymentRow(notCreated.ref)).containsEntry("failure_reason", "not_created");

        Pay unreconciled = payment("PENDING", DUE, DUE, BigDecimal.ZERO);
        assertThat(transitions.unreconciled(unreconciled.ref).status()).isEqualTo("REVIEW");
        assertThat(paymentRow(unreconciled.ref)).containsEntry("failure_reason", "unreconciled")
                .containsEntry("completed_at", null);
        assertThat(auditDetails("PAYMENT_REVIEW", unreconciled.ref)).contains("\"reason\": \"unreconciled\"");

        for (Pay pay : List.of(failed, refused, expired, notCreated, unreconciled)) {
            assertInvoice(pay, "0.00", "OPEN", 0);
        }
    }

    @Test
    void everyDisallowedFromToPairIsIgnoredAndChangesNothing() {
        Map<String, Function<Pay, Result>> events = Map.of(
                "succeeded", p -> transitions.succeeded(p.ref, capture(p, "txn-x"), Source.WEBHOOK),
                "succeededMismatch", p -> transitions.succeeded(p.ref,
                        new Capture(p.session, new BigDecimal("1.00"), "INR", Method.UPI, "txn-x"), Source.WEBHOOK),
                "failed", p -> transitions.failed(p.ref, "declined", Method.CARD, Source.WEBHOOK),
                "expired", p -> transitions.expired(p.ref, Source.WEBHOOK),
                "notCreated", p -> transitions.notCreated(p.ref),
                "unreconciled", p -> transitions.unreconciled(p.ref));
        int checked = 0;
        for (String from : STATUSES) {
            for (Map.Entry<String, Function<Pay, Result>> event : events.entrySet()) {
                boolean allowed = event.getKey().startsWith("succeeded")
                        ? List.of("PENDING", "FAILED", "EXPIRED").contains(from)
                        : from.equals("PENDING");
                if (allowed) {
                    continue;
                }
                Pay pay = payment(from, DUE, DUE, BigDecimal.ZERO);
                Map<String, Object> before = paymentRow(pay.ref);

                Result result = event.getValue().apply(pay);

                assertThat(result).as(from + " + " + event.getKey())
                        .isEqualTo(new Result(Result.Kind.IGNORED, from));
                assertThat(paymentRow(pay.ref)).as(from + " + " + event.getKey()).isEqualTo(before);
                assertInvoice(pay, "0.00", "OPEN", 0);
                assertThat(auditCount("PAYMENT_EVENT_IGNORED", pay.ref)).isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_events WHERE entity_id = :ref AND action <> 'PAYMENT_EVENT_IGNORED'
                        """, Map.of("ref", pay.ref), Integer.class)).isZero();
                checked++;
                cleanUp();
            }
        }
        // 2 succeeded variants × {SUCCEEDED, REVIEW} + 4 PENDING-only events × 4 non-PENDING statuses
        assertThat(checked).isEqualTo(2 * 2 + 4 * 4);
    }

    @Test
    void unknownReferenceIsReportedAndNotAudited() {
        String ref = "PAY-TY-UNKNOWN";
        assertThat(transitions.succeeded(ref, new Capture("s", DUE, "INR", Method.UPI, "t"), Source.WEBHOOK))
                .isEqualTo(new Result(Result.Kind.UNKNOWN_PAYMENT, null));
        assertThat(transitions.failed(ref, "declined", null, Source.WEBHOOK).kind())
                .isEqualTo(Result.Kind.UNKNOWN_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_id = :ref",
                Map.of("ref", ref), Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Pay(String ref, long invoiceId, String invoiceRef, BigDecimal amount, String session) {}

    private Pay payment(String status, BigDecimal amount, BigDecimal due, BigDecimal paid) {
        return payment(status, amount, due, paid, "y-sess-" + SEQ.incrementAndGet());
    }

    private Pay payment(String status, BigDecimal amount, BigDecimal due, BigDecimal paid, String session) {
        int n = SEQ.incrementAndGet();
        String invoiceRef = "INV-T-Y" + n;
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                      amount_due, amount_paid, status, created_at, updated_at)
                SELECT id, :ref, CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
                       CURRENT_DATE + INTERVAL 15 DAY, :due, :paid, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM accounts WHERE account_ref = 'ACC-T-0001'
                """, new MapSqlParameterSource().addValue("ref", invoiceRef).addValue("due", due)
                .addValue("paid", paid));
        long invoiceId = jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref",
                Map.of("ref", invoiceRef), Long.class);
        String ref = "PAY-TY-" + n;
        insertPayment(ref, invoiceId, status, amount, session);
        return new Pay(ref, invoiceId, invoiceRef, amount, session);
    }

    private void insertPayment(String ref, long invoiceId, String status, BigDecimal amount, String session) {
        boolean completed = !status.equals("PENDING") && !status.equals("REVIEW");
        jdbc.update("""
                INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, failure_reason,
                                      gateway_session_id, initiated_by_user_id, initiated_at, completed_at,
                                      created_at, updated_at)
                SELECT :ref, i.account_id, i.id, :amount, 'INR', :status, :reason, :session, u.id,
                       UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE, :completedAt, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM invoices i JOIN users u ON u.username = '+919900000001'
                WHERE i.id = :invoiceId
                """, new MapSqlParameterSource()
                .addValue("ref", ref)
                .addValue("invoiceId", invoiceId)
                .addValue("amount", amount)
                .addValue("status", status)
                .addValue("reason", status.equals("FAILED") ? "declined" : null)
                .addValue("session", session)
                .addValue("completedAt", completed ? java.time.LocalDateTime.of(2026, 1, 1, 0, 0) : null));
    }

    private static Capture capture(Pay pay, String txnId) {
        return new Capture(pay.session, pay.amount, "INR", Method.UPI, txnId);
    }

    private static Callable<Result> race(CountDownLatch start, Callable<Result> body) {
        return () -> {
            start.await();
            return body.call();
        };
    }

    private Map<String, Object> paymentRow(String ref) {
        return jdbc.queryForMap("""
                SELECT status, failure_reason, method, gateway_txn_id, gateway_session_id, completed_at, version
                FROM payments WHERE reference = :ref
                """, Map.of("ref", ref));
    }

    private void assertInvoice(Pay pay, String paid, String status, int version) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT amount_paid, status, version FROM invoices WHERE id = :id", Map.of("id", pay.invoiceId));
        assertThat(row.get("amount_paid")).isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal(paid));
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("version")).isEqualTo(version);
    }

    private int auditCount(String action, String entityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = :action AND entity_id = :id",
                Map.of("action", action, "id", entityId), Integer.class);
    }

    private String auditDetails(String action, String entityId) {
        List<String> rows = new ArrayList<>(jdbc.queryForList(
                "SELECT CAST(details AS CHAR) FROM audit_events WHERE action = :action AND entity_id = :id",
                Map.of("action", action, "id", entityId), String.class));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
