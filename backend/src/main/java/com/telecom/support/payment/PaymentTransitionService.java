package com.telecom.support.payment;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.payment.InvoiceRepository.InvoiceBalance;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.payment.PaymentRepository.PaymentState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code payments.status} (design 02-customer backend §3.1, R13, R14; CUS-07.5, CUS-08.3/08.4, BR-08,
 * NFR-INTEG). Callers are the signed webhook, the reconciliation job and the initiate tx2 (B17, B18, B20) — never the
 * browser return.
 *
 * <p>Every transition is one guarded {@code UPDATE payments SET status = :to … WHERE id = :id AND status IN (:from)}.
 * The payment row is locked first ({@code SELECT … FOR UPDATE}, then its invoice), so the audit's from-status is exact
 * and webhook, reconciliation and several instances serialise on the row instead of deadlocking. The side effects —
 * the R14 invoice credit and the SUCCESS audit rows — run in the same transaction <b>only when exactly one row
 * changed</b>; otherwise the event is {@link Result.Kind#IGNORED} and {@code PAYMENT_EVENT_IGNORED} (FAILURE) is
 * written in its own transaction.
 *
 * <table>
 *   <caption>§3.1 transitions applied here</caption>
 *   <tr><th>Event</th><th>From</th><th>To</th></tr>
 *   <tr><td>succeeded, amount + currency + session match</td><td>PENDING, FAILED, EXPIRED</td>
 *       <td>SUCCEEDED + credit (from FAILED/EXPIRED also {@code PAYMENT_LATE_CAPTURE})</td></tr>
 *   <tr><td>succeeded, amount/currency/session mismatch</td><td>PENDING, FAILED, EXPIRED</td>
 *       <td>REVIEW {@code amount_mismatch}, no credit</td></tr>
 *   <tr><td>failed / expired reported by the gateway (or initiate refused)</td><td>PENDING</td>
 *       <td>FAILED / EXPIRED</td></tr>
 *   <tr><td>reconcile: gateway NOT_FOUND</td><td>PENDING</td><td>FAILED {@code not_created}</td></tr>
 *   <tr><td>reconcile: unknown after 24 h</td><td>PENDING</td><td>REVIEW {@code unreconciled}</td></tr>
 *   <tr><td>anything else (incl. any event on SUCCEEDED or REVIEW)</td><td>—</td><td>IGNORED</td></tr>
 * </table>
 *
 * <p>The age conditions of the reconcile rows (10 min, 24 h) are the job's decision (B20). Audit rows have no actor.
 */
@Service
public class PaymentTransitionService {

    static final String PENDING = "PENDING";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final String EXPIRED = "EXPIRED";
    static final String REVIEW = "REVIEW";

    static final String REASON_AMOUNT_MISMATCH = "amount_mismatch";
    static final String REASON_NOT_CREATED = "not_created";
    static final String REASON_UNRECONCILED = "unreconciled";

    static final String AUDIT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    static final String AUDIT_FAILED = "PAYMENT_FAILED";
    static final String AUDIT_EXPIRED = "PAYMENT_EXPIRED";
    static final String AUDIT_REVIEW = "PAYMENT_REVIEW";
    static final String AUDIT_LATE_CAPTURE = "PAYMENT_LATE_CAPTURE";
    static final String AUDIT_OVERPAID = "PAYMENT_OVERPAID";
    static final String AUDIT_EVENT_IGNORED = "PAYMENT_EVENT_IGNORED";
    static final String ENTITY_PAYMENT = "PAYMENT";
    static final String ENTITY_INVOICE = "INVOICE";

    private static final List<String> FROM_SUCCESS = List.of(PENDING, FAILED, EXPIRED);
    private static final List<String> FROM_PENDING = List.of(PENDING);

    /** Who reports the event (audit {@code source}). */
    public enum Source {
        WEBHOOK, RECONCILE, INITIATE;

        String auditValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What happened to the payment. */
    public enum EventType {
        SUCCEEDED("payment.succeeded"), FAILED("payment.failed"), EXPIRED("payment.expired"),
        NOT_CREATED("reconcile.not_found"), UNRECONCILED("reconcile.unreconciled");

        private final String auditValue;

        EventType(String auditValue) {
            this.auditValue = auditValue;
        }
    }

    /** The gateway's report of a capture, compared with the stored payment (amount, currency, session). */
    public record Capture(String sessionId, BigDecimal amount, String currency, Method method, String gatewayTxnId) {}

    /**
     * Outcome of one event: {@code status} is the payment's status after the call (the unchanged current status when
     * IGNORED, null when the payment is unknown).
     */
    public record Result(Kind kind, String status) {

        public enum Kind { APPLIED, IGNORED, UNKNOWN_PAYMENT }

        public boolean applied() {
            return kind == Kind.APPLIED;
        }
    }

    private record Target(EventType event, List<String> from, String to, String reason, String method,
                          String gatewayTxnId, String sessionId) {}

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final AuditService audit;
    private final Clock clock;

    PaymentTransitionService(PaymentRepository payments, InvoiceRepository invoices, AuditService audit, Clock clock) {
        this.payments = payments;
        this.invoices = invoices;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A capture reported by the gateway. From PENDING/FAILED/EXPIRED: SUCCEEDED + R14 credit when amount, currency and
     * session match (an empty stored session — initiate timed out — is filled from the capture), else REVIEW
     * {@code amount_mismatch} without credit.
     */
    @Transactional
    public Result succeeded(String reference, Capture capture, Source source) {
        Objects.requireNonNull(capture, "capture");
        Optional<PaymentState> locked = payments.lockByReference(reference);
        if (locked.isEmpty()) {
            return new Result(Result.Kind.UNKNOWN_PAYMENT, null);
        }
        PaymentState payment = locked.get();
        String method = capture.method() == null ? null : capture.method().name();
        Target target = matches(payment, capture)
                ? new Target(EventType.SUCCEEDED, FROM_SUCCESS, SUCCEEDED, null, method, capture.gatewayTxnId(),
                        capture.sessionId())
                : new Target(EventType.SUCCEEDED, FROM_SUCCESS, REVIEW, REASON_AMOUNT_MISMATCH, method,
                        capture.gatewayTxnId(), null);
        return apply(payment, target, source);
    }

    /** A failure reported by the gateway, or the initiate tx2 after the gateway refused: PENDING → FAILED. */
    @Transactional
    public Result failed(String reference, String reason, Method method, Source source) {
        return apply(reference, new Target(EventType.FAILED, FROM_PENDING, FAILED, reason,
                method == null ? null : method.name(), null, null), source);
    }

    /** An expiry reported by the gateway: PENDING → EXPIRED (never from an unknown or unreachable status). */
    @Transactional
    public Result expired(String reference, Source source) {
        return apply(reference, new Target(EventType.EXPIRED, FROM_PENDING, EXPIRED, null, null, null, null), source);
    }

    /** Reconcile: the gateway never created a session for this reference: PENDING → FAILED {@code not_created}. */
    @Transactional
    public Result notCreated(String reference) {
        return apply(reference, new Target(EventType.NOT_CREATED, FROM_PENDING, FAILED, REASON_NOT_CREATED, null,
                null, null), Source.RECONCILE);
    }

    /** Reconcile: still unknown after 24 h: PENDING → REVIEW {@code unreconciled} (never auto-EXPIRED). */
    @Transactional
    public Result unreconciled(String reference) {
        return apply(reference, new Target(EventType.UNRECONCILED, FROM_PENDING, REVIEW, REASON_UNRECONCILED, null,
                null, null), Source.RECONCILE);
    }

    private Result apply(String reference, Target target, Source source) {
        Optional<PaymentState> locked = payments.lockByReference(reference);
        if (locked.isEmpty()) {
            return new Result(Result.Kind.UNKNOWN_PAYMENT, null);
        }
        return apply(locked.get(), target, source);
    }

    private Result apply(PaymentState payment, Target target, Source source) {
        Instant now = clock.instant();
        Instant completedAt = REVIEW.equals(target.to()) ? null : now;
        int changed = payments.transition(payment.id(), target.from(), target.to(), target.reason(), target.method(),
                target.gatewayTxnId(), target.sessionId(), completedAt, now);
        if (changed != 1) {
            audit.recordIndependently(AUDIT_EVENT_IGNORED, Outcome.FAILURE, null, null, ENTITY_PAYMENT,
                    payment.reference(), details("source", source.auditValue(), "eventType", target.event().auditValue,
                            "currentStatus", payment.status()));
            return new Result(Result.Kind.IGNORED, payment.status());
        }

        String from = payment.status();
        Map<String, Object> details = details("source", source.auditValue(), "from", from, "to", target.to(),
                "method", target.method(), "gatewayTxnId", target.gatewayTxnId(), "reason", target.reason());
        audit.record(auditAction(target.to()), null, null, ENTITY_PAYMENT, payment.reference(), details);
        if (SUCCEEDED.equals(target.to())) {
            if (!PENDING.equals(from)) {
                audit.record(AUDIT_LATE_CAPTURE, null, null, ENTITY_PAYMENT, payment.reference(), details);
            }
            credit(payment, now);
        }
        return new Result(Result.Kind.APPLIED, target.to());
    }

    /** R14: one guarded credit by id + version, in the transition's transaction; overpayment audited. */
    private void credit(PaymentState payment, Instant now) {
        InvoiceBalance invoice = invoices.lockBalance(payment.invoiceId())
                .orElseThrow(() -> new IllegalStateException("Invoice of payment " + payment.reference() + " missing"));
        if (invoices.credit(invoice.id(), invoice.version(), payment.amount(), now) != 1) {
            // Cannot happen while the row is locked; roll the whole transition back rather than lose the credit.
            throw new IllegalStateException("Invoice " + invoice.invoiceRef() + " changed during credit");
        }
        BigDecimal paid = invoice.amountPaid().add(payment.amount());
        if (paid.compareTo(invoice.amountDue()) > 0) {
            audit.record(AUDIT_OVERPAID, null, null, ENTITY_INVOICE, invoice.invoiceRef(),
                    details("paymentReference", payment.reference(), "amountDue", invoice.amountDue().toPlainString(),
                            "amountPaid", paid.toPlainString()));
        }
    }

    private static boolean matches(PaymentState payment, Capture capture) {
        boolean amount = capture.amount() != null && capture.amount().compareTo(payment.amount()) == 0;
        boolean currency = payment.currency().equals(capture.currency());
        boolean session = payment.gatewaySessionId() == null
                ? capture.sessionId() != null
                : payment.gatewaySessionId().equals(capture.sessionId());
        return amount && currency && session;
    }

    private static String auditAction(String to) {
        return switch (to) {
            case SUCCEEDED -> AUDIT_SUCCEEDED;
            case FAILED -> AUDIT_FAILED;
            case EXPIRED -> AUDIT_EXPIRED;
            case REVIEW -> AUDIT_REVIEW;
            default -> throw new IllegalArgumentException("No audit action for " + to);
        };
    }

    /** Key/value pairs in order, skipping null values. */
    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                map.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return map;
    }
}
