package com.telecom.support.customer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity check housekeeping (design 03-inbound backend §2, §6; NFR-PRIV): PENDING OTP checks past their expiry become
 * EXPIRED, and lookup tokens are purged 24 h after they were issued. Both statements are idempotent, so parallel runs
 * (or instances) are harmless. Time comes from {@code AppClock}. A CHECKING KBA row is left as it is: it counts as a
 * failure in the hourly window (R4) and then no longer matters.
 */
@Component
@EnableScheduling
public class IdentityCheckCleanupJob {

    static final Duration LOOKUP_RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(IdentityCheckCleanupJob.class);

    /** What one run changed. */
    public record Result(int expiredChecks, int purgedLookups) {}

    private final IdentityCheckRepository checks;
    private final CustomerLookupRepository lookups;
    private final Clock clock;

    IdentityCheckCleanupJob(IdentityCheckRepository checks, CustomerLookupRepository lookups, Clock clock) {
        this.checks = checks;
        this.lookups = lookups;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.inbound.identity-check.cleanup-cron:0 */5 * * * *}")
    @Transactional
    public Result run() {
        Instant now = clock.instant();
        Result result = new Result(checks.expirePending(now), lookups.purgeCreatedBefore(now.minus(LOOKUP_RETENTION)));
        if (result.expiredChecks() > 0 || result.purgedLookups() > 0) {
            log.info("Identity check cleanup: {} expired, {} lookups purged", result.expiredChecks(),
                    result.purgedLookups());
        }
        return result;
    }
}
