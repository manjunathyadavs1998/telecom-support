package com.telecom.support.notification;

import com.telecom.support.notification.NotificationRepository.Due;
import com.telecom.support.notification.NotificationService.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued customer notifications (design 03-inbound backend R16, §3.1; INB-04.7, NFR-AVAIL). Every 30 s
 * ({@code app.notification.dispatch-cron}; {@code -} disables it, tests only, refused under {@code prod}): first resets
 * SENDING rows claimed more than {@link #STALE_AFTER} ago (a crashed run; at-least-once, accepted), then pages through
 * the due PENDING rows ({@link #PAGE_SIZE}, keyset by id) and hands each to {@link NotificationService#dispatch}, whose
 * guarded claim makes parallel runs and instances send a row once. A run stops starting sends after its
 * {@link #RUN_BUDGET} (below the 30 s schedule); the rest is sent by the next run. Never changes reactivation state.
 */
@Component
@EnableScheduling
public class NotificationDispatchJob {

    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    static final int PAGE_SIZE = 100;
    static final Duration RUN_BUDGET = Duration.ofSeconds(25);

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    /** Counts of one run. */
    public record RunResult(int sent, int retried, int failed, int skipped, int reset) {}

    private final NotificationRepository notifications;
    private final NotificationService service;
    private final Clock clock;
    /** Test seams (package-private): page size and run budget. */
    int pageSize = PAGE_SIZE;
    Duration runBudget = RUN_BUDGET;

    NotificationDispatchJob(NotificationRepository notifications, NotificationService service, Clock clock) {
        this.notifications = notifications;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.notification.dispatch-cron:*/30 * * * * *}")
    public RunResult run() {
        int reset = service.resetStale(STALE_AFTER);
        long deadline = System.nanoTime() + runBudget.toNanos();
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int skipped = 0;
        long after = 0;
        while (true) {
            List<Due> page = notifications.findDue(clock.instant(), after, pageSize);
            for (Due row : page) {
                Outcome outcome;
                try {
                    outcome = service.dispatch(row);
                } catch (RuntimeException e) {
                    log.error("Notification dispatch of {} failed", row.id(), e);
                    outcome = Outcome.NOT_CLAIMED;
                }
                switch (outcome) {
                    case SENT -> sent++;
                    case RETRY -> retried++;
                    case FAILED -> failed++;
                    case NOT_CLAIMED -> skipped++;
                }
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("Notification dispatch stopped after its {} budget; the rest is sent next run", runBudget);
                    return new RunResult(sent, retried, failed, skipped, reset);
                }
            }
            if (page.size() < pageSize) {
                return new RunResult(sent, retried, failed, skipped, reset);
            }
            after = page.get(page.size() - 1).id();
        }
    }
}
