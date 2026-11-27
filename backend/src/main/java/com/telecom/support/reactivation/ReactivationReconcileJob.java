package com.telecom.support.reactivation;

import com.telecom.support.reactivation.ProvisioningClient.ProviderStatus;
import com.telecom.support.reactivation.ProvisioningClient.State;
import com.telecom.support.reactivation.ReactivationRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves PENDING reactivations whose restore outcome is not known (design 03-inbound backend §3.1; INB-04.4/04.5,
 * BR-08, NFR-REL). It never writes a status itself: every change goes through {@link ReactivationTransitionService},
 * whose guarded updates make parallel runs, the request thread and several instances act once.
 *
 * <p>Each run first flags PENDING requests unresolved for {@code app.reactivation.attention-after} (30 min;
 * {@code needs_attention}, once, still reconciled), then asks the provider about every PENDING request whose
 * {@code next_attempt_at} is due:
 * <ul>
 *   <li>RESTORED, {@code restoredAt + hours} still ahead → ACTIVE with {@code expires_at = restoredAt + hours};</li>
 *   <li>RESTORED, window already over → EXPIRING (the expiry job suspends it next; the 24 h cap holds);</li>
 *   <li>SUSPENDED → FAILED {@code not_restored}; NOT_FOUND older than {@link #NOT_FOUND_AGE} → FAILED
 *       {@code not_created} (younger: retried);</li>
 *   <li>unreachable / no answer → PENDING stays, {@code attempts + 1}, backoff 2, 5, 15 min, then hourly, without
 *       limit (the first 1 min wait is set at creation).</li>
 * </ul>
 * Then (E2) every FAILED request that failed within the last 24 h and whose re-check is due is asked about again:
 * RESTORED -> FAILED -> EXPIRING ({@code REACTIVATION_LATE_RESTORE}; the expiry job suspends it next); otherwise the
 * next re-check follows the same backoff, and none is scheduled past 24 h after the failure.
 * Provider calls run outside any transaction. A run pages ({@link #PAGE_SIZE}, keyset by id) and stops starting calls
 * after {@link #RUN_BUDGET}; the rest is handled next run. Schedule {@code app.reactivation.reconcile-cron} ({@code -}
 * only in tests; refused under {@code prod}).
 */
@Component
public class ReactivationReconcileJob {

    static final Duration NOT_FOUND_AGE = Duration.ofMinutes(2);
    /** E2: how long after failing a request is still re-checked for a late provider restore. */
    static final Duration FAILED_RECHECK_FOR = Duration.ofHours(24);
    static final int PAGE_SIZE = 100;
    static final Duration RUN_BUDGET = Duration.ofSeconds(50);
    static final String UNRESOLVED_RESTORE = "unresolved_restore";

    private static final Logger log = LoggerFactory.getLogger(ReactivationReconcileJob.class);

    /** Counts of one run: transitions made, requests retried later, alerts raised. */
    public record RunResult(int applied, int retried, int flagged) {}

    private final ReactivationRepository requests;
    private final ReactivationTransitionService transitions;
    private final ProvisioningClient provisioning;
    private final Clock clock;
    private final Duration attentionAfter;
    /** Test seams (package-private): page size and run budget. */
    int pageSize = PAGE_SIZE;
    Duration runBudget = RUN_BUDGET;

    ReactivationReconcileJob(ReactivationRepository requests, ReactivationTransitionService transitions,
                             ProvisioningClient provisioning, Clock clock, ReactivationProperties properties) {
        this.requests = requests;
        this.transitions = transitions;
        this.provisioning = provisioning;
        this.clock = clock;
        this.attentionAfter = properties.attentionAfter();
    }

    @Scheduled(cron = "${app.reactivation.reconcile-cron:30 * * * * *}")
    public RunResult run() {
        Instant now = clock.instant();
        int[] counts = new int[3];
        Instant cutoff = now.minus(attentionAfter);
        JobPages.each(after -> requests.findUnflaggedPending(cutoff, after, pageSize), pageSize, null, row -> {
            if (transitions.flagAttention(row, UNRESOLVED_RESTORE, row.attempts())) {
                counts[2]++;
            }
        });
        long deadline = System.nanoTime() + runBudget.toNanos();
        boolean inBudget = JobPages.each(after -> requests.findDuePending(now, after, pageSize), pageSize, deadline,
                row -> {
                    try {
                        counts[reconcile(row, now) ? 0 : 1]++;
                    } catch (RuntimeException e) {
                        log.error("Reactivation reconcile of {} failed", row.reference(), e);
                        counts[1]++;
                    }
                });
        if (inBudget) {
            // E2: FAILED requests are re-checked for a late provider restore for 24 h after they failed
            JobPages.each(after -> requests.findDueFailed(now, now.minus(FAILED_RECHECK_FOR), after, pageSize),
                    pageSize, deadline, row -> {
                        try {
                            if (recheckFailed(row, now)) {
                                counts[0]++;
                            }
                        } catch (RuntimeException e) {
                            log.error("Reactivation late-restore re-check of {} failed", row.reference(), e);
                        }
                    });
        }
        return new RunResult(counts[0], counts[1], counts[2]);
    }

    /**
     * E2: a FAILED request whose line the provider reports RESTORED moves to EXPIRING (suspended by the expiry job);
     * otherwise the next re-check is scheduled with the section 3.1 backoff, none after 24 h.
     *
     * @return true when the late restore was handled by this call
     */
    private boolean recheckFailed(RequestRow row, Instant now) {
        ProviderStatus status;
        try {
            status = provisioning.getStatus(row.reference());
        } catch (RuntimeException unreachable) {
            log.warn("Reactivation {}: provider status unavailable for the late-restore re-check ({})",
                    row.reference(), unreachable.getClass().getSimpleName());
            status = null;
        }
        if (status != null && status.state() == State.RESTORED) {
            Instant restoredAt = (status.restoredAt() == null ? now : status.restoredAt())
                    .truncatedTo(ChronoUnit.MICROS);
            try {
                if (transitions.lateRestore(row, status.providerRef(), restoredAt,
                        restoredAt.plus(Duration.ofHours(row.requestedHours())),
                        ReactivationTransitionService.SOURCE_RECONCILE, null)) {
                    return true;
                }
            } catch (DuplicateKeyException openRequest) {
                log.error("Reactivation {}: provider restored the line after FAILED, but the account has another open"
                        + " request; re-checked later", row.reference());
            }
        }
        transitions.recheckFailedLater(row, now.plus(ReactivationTransitionService.backoff(row.attempts() + 1)));
        return false;
    }

    /** @return true when a transition was made, false when the request is retried later (or another run won) */
    private boolean reconcile(RequestRow row, Instant now) {
        ProviderStatus status;
        try {
            status = provisioning.getStatus(row.reference());
        } catch (RuntimeException unreachable) {
            log.warn("Reactivation {}: provider status unavailable ({}); retried with backoff", row.reference(),
                    unreachable.getClass().getSimpleName());
            retry(row, now);
            return false;
        }
        return switch (status.state()) {
            case RESTORED -> {
                Instant restoredAt = (status.restoredAt() == null ? now : status.restoredAt())
                        .truncatedTo(ChronoUnit.MICROS);
                Instant expiresAt = restoredAt.plus(Duration.ofHours(row.requestedHours()));
                yield expiresAt.isAfter(now)
                        ? transitions.activate(row, status.providerRef(), restoredAt, expiresAt,
                                ReactivationTransitionService.SOURCE_RECONCILE, null)
                        : transitions.restoredLate(row, status.providerRef(), restoredAt, expiresAt);
            }
            case SUSPENDED -> transitions.fail(row, "not_restored", ReactivationTransitionService.SOURCE_RECONCILE,
                    null);
            case NOT_FOUND -> {
                if (!row.createdAt().plus(NOT_FOUND_AGE).isAfter(now)) {
                    yield transitions.fail(row, "not_created", ReactivationTransitionService.SOURCE_RECONCILE, null);
                }
                retry(row, now);
                yield false;
            }
        };
    }

    private void retry(RequestRow row, Instant now) {
        // step 0 (1 min) was the wait set at creation, so the n-th failed reconcile waits step n
        transitions.retry(row, now.plus(ReactivationTransitionService.backoff(row.attempts() + 1)));
    }

    /** Keyset paging shared by the reactivation jobs. */
    static final class JobPages {

        private JobPages() {
        }

        /**
         * Hands every row of every page to {@code handle} until a short page, or until {@code deadline}
         * ({@link System#nanoTime()}, null = none) has passed (at least one row is always handled).
         *
         * @return false when the budget stopped the run
         */
        static boolean each(LongFunction<List<RequestRow>> page, int pageSize, Long deadline,
                            Consumer<RequestRow> handle) {
            long after = 0;
            while (true) {
                List<RequestRow> rows = page.apply(after);
                for (RequestRow row : rows) {
                    handle.accept(row);
                    if (deadline != null && System.nanoTime() - deadline >= 0) {
                        log.warn("Reactivation job stopped after its budget; the rest is handled next run");
                        return false;
                    }
                }
                if (rows.size() < pageSize) {
                    return true;
                }
                after = rows.get(rows.size() - 1).id();
            }
        }
    }
}
