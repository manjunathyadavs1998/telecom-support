package com.telecom.support.reactivation;

import com.telecom.support.reactivation.ProvisioningClient.State;
import com.telecom.support.reactivation.ReactivationReconcileJob.JobPages;
import com.telecom.support.reactivation.ReactivationRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends temporary reactivations (design 03-inbound backend §3.1, R15; INB-04.5, BR-03, BR-08, XC-07). Expiry is
 * authoritative here, whatever {@code until} the provider was given. Every status change goes through
 * {@link ReactivationTransitionService} (guarded; parallel runs and instances act once). Each run:
 * <ol>
 *   <li>ACTIVE requests (claim tx, account locked): due and account ACTIVE again → ENDED {@code account_reinstated}
 *       (no provider call); due → EXPIRING {@code expired}; account reason now FRAUD/LEGAL/REGULATORY → EXPIRING
 *       {@code policy_prohibited} (early).</li>
 *   <li>EXPIRING requests whose {@code next_attempt_at} is due, outside any tx: {@code getStatus} SUSPENDED → EXPIRED;
 *       otherwise {@code suspend}, confirmed → EXPIRED; failed → EXPIRING stays, {@code attempts + 1}, backoff 1, 2, 5,
 *       15 min, then hourly, <em>without limit</em>; from {@code app.reactivation.attention-failures} (5) failures on,
 *       {@code needs_attention} (once).</li>
 *   <li>Requests restored for {@link #OVERDUE_AFTER} and not yet EXPIRED → {@code REACTIVATION_OVERDUE} once.</li>
 * </ol>
 * A run pages ({@link #PAGE_SIZE}, keyset by id) and stops starting work after {@link #RUN_BUDGET}. Schedule
 * {@code app.reactivation.expiry-cron} (every 60 s; {@code -} only in tests; refused under {@code prod}).
 */
@Component
public class ReactivationExpiryJob {

    static final Duration OVERDUE_AFTER = Duration.ofHours(24);
    static final int PAGE_SIZE = 100;
    static final Duration RUN_BUDGET = Duration.ofSeconds(50);
    static final String SUSPEND_FAILING = "suspend_failing";

    private static final Logger log = LoggerFactory.getLogger(ReactivationExpiryJob.class);

    /** Counts of one run: ACTIVE closed or moved to EXPIRING, EXPIRED, suspends retried later, overdue alerts. */
    public record RunResult(int claimed, int expired, int retried, int overdue) {}

    private final ReactivationRepository requests;
    private final ReactivationTransitionService transitions;
    private final ProvisioningClient provisioning;
    private final Clock clock;
    private final int attentionFailures;
    /** Test seams (package-private): page size and run budget. */
    int pageSize = PAGE_SIZE;
    Duration runBudget = RUN_BUDGET;

    ReactivationExpiryJob(ReactivationRepository requests, ReactivationTransitionService transitions,
                          ProvisioningClient provisioning, Clock clock, ReactivationProperties properties) {
        this.requests = requests;
        this.transitions = transitions;
        this.provisioning = provisioning;
        this.clock = clock;
        this.attentionFailures = properties.attentionFailures();
    }

    @Scheduled(cron = "${app.reactivation.expiry-cron:0 * * * * *}")
    public RunResult run() {
        int[] counts = new int[4];
        long deadline = System.nanoTime() + runBudget.toNanos();
        boolean inBudget = JobPages.each(after -> requests.findActive(after, pageSize), pageSize, deadline, row -> {
            try {
                if (transitions.closeOrStartExpiring(row) != null) {
                    counts[0]++;
                }
            } catch (RuntimeException e) {
                log.error("Reactivation expiry of {} failed", row.reference(), e);
            }
        });
        Instant now = clock.instant();
        if (inBudget) {
            JobPages.each(after -> requests.findDueExpiring(now, after, pageSize), pageSize, deadline, row -> {
                try {
                    counts[suspend(row, now) ? 1 : 2]++;
                } catch (RuntimeException e) {
                    log.error("Reactivation suspend of {} failed", row.reference(), e);
                    counts[2]++;
                }
            });
        }
        JobPages.each(after -> requests.findOverdue(now.minus(OVERDUE_AFTER), after, pageSize), pageSize, null,
                row -> {
                    if (transitions.alertOverdue(row)) {
                        counts[3]++;
                    }
                });
        return new RunResult(counts[0], counts[1], counts[2], counts[3]);
    }

    /** @return true when EXPIRED, false when retried later (or another run won) */
    private boolean suspend(RequestRow row, Instant now) {
        try {
            if (provisioning.getStatus(row.reference()).state() == State.SUSPENDED) {
                return transitions.expire(row);
            }
        } catch (RuntimeException unreachable) {
            log.warn("Reactivation {}: provider status unavailable ({}); suspending anyway", row.reference(),
                    unreachable.getClass().getSimpleName());
        }
        try {
            provisioning.suspend(row.reference(), row.msisdns());
        } catch (RuntimeException failed) {
            int attempts = row.attempts() + 1;
            log.warn("Reactivation {}: suspend failed ({}), attempt {}; retried with backoff", row.reference(),
                    failed.getClass().getSimpleName(), attempts);
            if (transitions.retry(row, now.plus(ReactivationTransitionService.backoff(attempts - 1)))
                    && attempts >= attentionFailures && !row.needsAttention()) {
                transitions.flagAttention(row, SUSPEND_FAILING, attempts);
            }
            return false;
        }
        return transitions.expire(row);
    }
}
