package com.telecom.support.servicecase;

import com.telecom.support.audit.AuditService;
import com.telecom.support.servicecase.CaseRepository.DueResolvedRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes RESOLVED cases whose verification window has elapsed (design 04-outbound backend R8, §3.2; OUT-05.4). Takes due
 * ids by keyset page and, per row in its own transaction, runs the guarded {@code UPDATE … WHERE status = 'RESOLVED' AND
 * resolved_at <= :cutoff}: activity (actor null) and audit ({@code source = auto_close}) only when it changed one row, so
 * parallel runs and a concurrent reopen are safe. Schedule {@code app.outbound.auto-close-cron} (every 5 min; {@code -}
 * only in tests; refused under {@code prod}).
 */
@Component
public class CaseAutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(CaseAutoCloseJob.class);

    private final CaseRepository cases;
    private final CaseActivityRepository activity;
    private final CaseLifecycle lifecycle;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final OutboundProperties properties;
    private final Clock clock;

    CaseAutoCloseJob(CaseRepository cases, CaseActivityRepository activity, CaseLifecycle lifecycle, AuditService audit,
                     PlatformTransactionManager transactionManager, OutboundProperties properties, Clock clock) {
        this.cases = cases;
        this.activity = activity;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    /** @return the number of cases this run closed */
    @Scheduled(cron = "${app.outbound.auto-close-cron:0 */5 * * * *}")
    public int run() {
        Instant cutoff = lifecycle.autoCloseCutoff();
        int pageSize = properties.autoClose().pageSize();
        long deadline = System.nanoTime() + properties.autoClose().runBudget().toNanos();
        long after = 0;
        int closed = 0;
        while (System.nanoTime() < deadline) {
            List<DueResolvedRow> page = cases.findDueResolved(cutoff, after, pageSize);
            for (DueResolvedRow row : page) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(status -> close(row, cutoff)))) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    log.error("Auto-close of case {} failed", row.reference(), e);
                }
                after = row.id();
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        return closed;
    }

    private boolean close(DueResolvedRow row, Instant cutoff) {
        Instant now = clock.instant();
        if (!cases.autoClose(row.id(), cutoff, now)) {
            return false; // reopened, closed by an agent or by a parallel run meanwhile
        }
        activity.insertStatusChanged(row.id(), null, "RESOLVED", "CLOSED", now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", "RESOLVED");
        details.put("to", "CLOSED");
        details.put("closureType", CaseLifecycle.ClosureType.WINDOW_ELAPSED.name());
        details.put("source", "auto_close");
        audit.record(CaseCommandService.AUDIT_STATUS, null, null, "CASE", row.reference(), details);
        return true;
    }
}
