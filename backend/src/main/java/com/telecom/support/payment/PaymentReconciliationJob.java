package com.telecom.support.payment;

import com.telecom.support.payment.PaymentGateway.GatewayException;
import com.telecom.support.payment.PaymentGateway.GatewayPaymentStatus;
import com.telecom.support.payment.PaymentRepository.ReconcileCandidate;
import com.telecom.support.payment.PaymentTransitionService.Capture;
import com.telecom.support.payment.PaymentTransitionService.Result;
import com.telecom.support.payment.PaymentTransitionService.Source;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves payments whose webhook was lost or never sent, by polling the gateway by merchant reference (design
 * 02-customer backend §3.1; CUS-07.5, CUS-08.4/08.5, NFR-AVAIL). It never writes a status itself: every change goes
 * through {@link PaymentTransitionService}, whose guarded updates make parallel runs, the webhook and several instances
 * safe (one credit at most).
 *
 * <p>{@link #reconcilePending()} (every 60 s): PENDING payments initiated more than {@link #PENDING_MIN_AGE} ago.
 * <ul>
 *   <li>gateway SUCCEEDED → {@code succeeded} (SUCCEEDED + credit, or REVIEW on a mismatch);</li>
 *   <li>gateway FAILED / EXPIRED → {@code failed} / {@code expired};</li>
 *   <li>gateway NOT_FOUND and initiated more than {@link #NOT_FOUND_AGE} ago → FAILED {@code not_created};</li>
 *   <li>gateway PENDING or unreachable: unchanged and retried next run (unreachable is WARN-logged); once initiated
 *       more than {@link #UNRECONCILED_AGE} ago → REVIEW {@code unreconciled}. Never auto-EXPIRED.</li>
 * </ul>
 *
 * <p>{@link #recheckLateCaptures()} (every 15 min): FAILED/EXPIRED payments completed within
 * {@code app.payment.gateway.late-capture-window} (24 h); a gateway SUCCEEDED is applied as a late capture. Anything
 * else, or an unreachable gateway, leaves them unchanged.
 *
 * <p>Gateway calls run outside any transaction; each payment is handled on its own, so one failure does not stop a run.
 * A run pages through <em>all</em> eligible payments in keyset order ({@link #BATCH_SIZE} per page), so none is starved
 * by a full first page. A run also stops once it has used {@link #RUN_BUDGET}: Spring's scheduler has one thread, and a
 * slow or timing-out gateway (5 s per call) must not hold it, and with it the other scheduled jobs, for long. Payments
 * not reached are handled by the next run.
 *
 * <p>The schedules are {@code app.payment.reconcile.pending-cron} and {@code app.payment.reconcile.late-capture-cron}
 * ({@code -} disables one, as the tests do; {@code ProfileGuard} refuses a disabled schedule under {@code prod}).
 */
@Component
public class PaymentReconciliationJob {

    static final Duration PENDING_MIN_AGE = Duration.ofMinutes(2);
    static final Duration NOT_FOUND_AGE = Duration.ofMinutes(10);
    static final Duration UNRECONCILED_AGE = Duration.ofHours(24);
    static final int BATCH_SIZE = 500;
    /** Longest a run keeps starting gateway calls; below the 60 s pending schedule so runs never pile up. */
    static final Duration RUN_BUDGET = Duration.ofSeconds(50);

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    /** Counts of one run: transitions applied, payments left unchanged, payments that could not be checked. */
    public record RunResult(int applied, int unchanged, int unreachable) {}

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentTransitionService transitions;
    private final Clock clock;
    private final Duration lateCaptureWindow;
    /** Test seams (package-private): page size and run budget. */
    int batchSize = BATCH_SIZE;
    Duration runBudget = RUN_BUDGET;

    PaymentReconciliationJob(PaymentRepository payments, PaymentGateway gateway, PaymentTransitionService transitions,
                             Clock clock, PaymentGatewayProperties properties) {
        this.payments = payments;
        this.gateway = gateway;
        this.transitions = transitions;
        this.clock = clock;
        this.lateCaptureWindow = properties.gateway().lateCaptureWindow();
    }

    /** Polls the gateway for PENDING payments older than {@link #PENDING_MIN_AGE} (§3.1 reconcile rows). */
    @Scheduled(cron = "${app.payment.reconcile.pending-cron:0 * * * * *}")
    public RunResult reconcilePending() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(PENDING_MIN_AGE);
        return run("Reconciliation", after -> payments.findPendingInitiatedBefore(cutoff, after, batchSize), payment -> {
            try {
                return reconcile(payment, now);
            } catch (RuntimeException e) {
                log.error("Payment reconciliation of {} failed", payment.reference(), e);
                return Outcome.UNREACHABLE;
            }
        });
    }

    /** Re-checks FAILED/EXPIRED payments completed within the late-capture window for a capture (§3.1). */
    @Scheduled(cron = "${app.payment.reconcile.late-capture-cron:0 */15 * * * *}")
    public RunResult recheckLateCaptures() {
        Instant since = clock.instant().minus(lateCaptureWindow);
        return run("Late-capture check", after -> payments.findFailedOrExpiredSince(since, after, batchSize),
                payment -> {
                    try {
                        GatewayPaymentStatus status = gateway.getStatus(payment.reference());
                        return status.status() == PaymentGateway.GatewayStatus.SUCCEEDED
                                ? applied(transitions.succeeded(payment.reference(), capture(status), Source.RECONCILE))
                                : Outcome.UNCHANGED;
                    } catch (GatewayException unreachable) {
                        log.warn("Late-capture check of {}: gateway unreachable ({}); retried next run",
                                payment.reference(), unreachable.getClass().getSimpleName());
                        return Outcome.UNREACHABLE;
                    } catch (RuntimeException e) {
                        log.error("Late-capture check of {} failed", payment.reference(), e);
                        return Outcome.UNREACHABLE;
                    }
                });
    }

    private enum Outcome { APPLIED, UNCHANGED, UNREACHABLE }

    /**
     * Handles every candidate page by page (keyset: each page starts after the last row of the previous one) until a
     * short page ends the run, or until {@link #runBudget} is used up (at least one payment is always handled).
     */
    private RunResult run(String name, Function<ReconcileCandidate, List<ReconcileCandidate>> page,
                          Function<ReconcileCandidate, Outcome> handle) {
        long deadline = System.nanoTime() + runBudget.toNanos();
        Counter counter = new Counter();
        ReconcileCandidate last = null;
        while (true) {
            List<ReconcileCandidate> batch = page.apply(last);
            for (ReconcileCandidate payment : batch) {
                counter.add(handle.apply(payment));
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("{} run stopped after its {} budget; the rest is handled next run", name, runBudget);
                    return counter.result();
                }
            }
            if (batch.size() < batchSize) {
                return counter.result();
            }
            last = batch.get(batch.size() - 1);
        }
    }

    private Outcome reconcile(ReconcileCandidate payment, Instant now) {
        String reference = payment.reference();
        boolean overdue = !payment.initiatedAt().plus(UNRECONCILED_AGE).isAfter(now);
        GatewayPaymentStatus status;
        try {
            status = gateway.getStatus(reference);
        } catch (GatewayException unreachable) {
            if (overdue) {
                return applied(transitions.unreconciled(reference));
            }
            log.warn("Reconciliation of {}: gateway unreachable ({}); retried next run", reference,
                    unreachable.getClass().getSimpleName());
            return Outcome.UNREACHABLE;
        }
        return switch (status.status()) {
            case SUCCEEDED -> applied(transitions.succeeded(reference, capture(status), Source.RECONCILE));
            case FAILED -> applied(transitions.failed(reference, null, status.method(), Source.RECONCILE));
            case EXPIRED -> applied(transitions.expired(reference, Source.RECONCILE));
            case NOT_FOUND -> !payment.initiatedAt().plus(NOT_FOUND_AGE).isAfter(now)
                    ? applied(transitions.notCreated(reference))
                    : Outcome.UNCHANGED;
            case PENDING -> overdue ? applied(transitions.unreconciled(reference)) : Outcome.UNCHANGED;
        };
    }

    private static Capture capture(GatewayPaymentStatus status) {
        return new Capture(status.sessionId(), status.amount(), status.currency(), status.method(),
                status.gatewayTxnId());
    }

    private static Outcome applied(Result result) {
        return result.applied() ? Outcome.APPLIED : Outcome.UNCHANGED;
    }

    private static final class Counter {
        private int applied;
        private int unchanged;
        private int unreachable;

        void add(Outcome outcome) {
            switch (outcome) {
                case APPLIED -> applied++;
                case UNCHANGED -> unchanged++;
                case UNREACHABLE -> unreachable++;
            }
        }

        RunResult result() {
            return new RunResult(applied, unchanged, unreachable);
        }
    }
}
