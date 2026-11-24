package com.telecom.support.common.idempotency;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly purge of idempotency keys older than 24 h (00-overview §7, 01 backend §6). Time comes from {@code AppClock}. */
@Component
@EnableScheduling
public class IdempotencyPurgeJob {

    static final Duration RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyRepository repository;
    private final Clock clock;

    IdempotencyPurgeJob(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Deletes every key created more than 24 h ago and returns the number of rows removed. */
    @Scheduled(cron = "${app.idempotency.purge-cron:0 0 * * * *}")
    public int purge() {
        int removed = repository.purgeCreatedBefore(clock.instant().minus(RETENTION));
        if (removed > 0) {
            log.info("Purged {} idempotency keys older than {}", removed, RETENTION);
        }
        return removed;
    }
}
