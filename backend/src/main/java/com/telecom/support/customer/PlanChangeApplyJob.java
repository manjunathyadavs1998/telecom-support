package com.telecom.support.customer;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.catalog.CatalogService;
import com.telecom.support.catalog.CatalogService.Plan;
import com.telecom.support.customer.PlanChangeRepository.DueChange;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies due plan changes (design 02-customer backend R6, §7, §8; CUS-03.5, BR-08, XC-06/07). Runs daily at 00:05
 * Asia/Kolkata. Each due change is handled in its own transaction with the row locked:
 * <ol>
 *   <li>guarded {@code UPDATE subscriptions SET plan_id = to WHERE id AND plan_id = from AND status = 'ACTIVE'};
 *       0 rows → the change is {@code CANCELLED} ({@code cancel_reason}) + {@code PLAN_CHANGE_CANCELLED};</li>
 *   <li>{@link PlanProvisioning#apply}; only when it returns is the change {@code APPLIED} + {@code PLAN_CHANGE_APPLIED}
 *       (success only on confirmation);</li>
 *   <li>a provider failure rolls the plan switch back; the change stays SCHEDULED with {@code apply_attempts + 1}, is
 *       retried next run and audited {@code PLAN_CHANGE_APPLY_FAILED} (FAILURE).</li>
 * </ol>
 * Job audit rows have no actor.
 */
@Component
public class PlanChangeApplyJob {

    static final String GUARD_FAILED_REASON = "subscription_not_active_or_plan_changed";
    static final String PROVIDER_FAILED_REASON = "provisioning_failed";

    private static final Logger log = LoggerFactory.getLogger(PlanChangeApplyJob.class);

    /** Counts of one run. */
    public record Result(int applied, int cancelled, int failed) {}

    private enum ApplyOutcome { APPLIED, CANCELLED, FAILED, SKIPPED }

    private final PlanChangeRepository planChanges;
    private final SubscriptionRepository subscriptions;
    private final PlanProvisioning provisioning;
    private final CatalogService catalog;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    PlanChangeApplyJob(PlanChangeRepository planChanges, SubscriptionRepository subscriptions,
                       PlanProvisioning provisioning, CatalogService catalog, AuditService audit,
                       PlatformTransactionManager transactionManager, Clock clock,
                       @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.planChanges = planChanges;
        this.subscriptions = subscriptions;
        this.provisioning = provisioning;
        this.catalog = catalog;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.zone = zone;
    }

    /** Applies every SCHEDULED change whose effective date is today (Asia/Kolkata) or earlier. */
    @Scheduled(cron = "${app.customer.plan-change.apply-cron:0 5 0 * * *}",
            zone = "${app.display-time-zone:Asia/Kolkata}")
    public Result run() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        int applied = 0;
        int cancelled = 0;
        int failed = 0;
        for (long id : planChanges.findDueIds(today)) {
            ApplyOutcome outcome;
            try {
                outcome = applyOne(id);
            } catch (RuntimeException e) {
                log.error("Plan change {} could not be processed; retried next run", id, e);
                continue;
            }
            switch (outcome) {
                case APPLIED -> applied++;
                case CANCELLED -> cancelled++;
                case FAILED -> failed++;
                case SKIPPED -> { }
            }
        }
        if (applied + cancelled + failed > 0) {
            log.info("Plan change job for {}: applied={}, cancelled={}, failed={}", today, applied, cancelled, failed);
        }
        return new Result(applied, cancelled, failed);
    }

    private ApplyOutcome applyOne(long id) {
        ApplyOutcome outcome = tx.execute(status -> {
            Optional<DueChange> locked = planChanges.lockScheduled(id);
            if (locked.isEmpty()) {
                return ApplyOutcome.SKIPPED; // handled by a parallel run
            }
            DueChange change = locked.get();
            Map<Long, Plan> plans = catalog.plansByIds(List.of(change.fromPlanId(), change.toPlanId()));
            String from = plans.get(change.fromPlanId()).code();
            String to = plans.get(change.toPlanId()).code();
            Instant now = clock.instant();

            if (subscriptions.switchPlan(change.subscriptionId(), change.fromPlanId(), change.toPlanId(), now) == 0) {
                planChanges.markCancelled(id, GUARD_FAILED_REASON, now);
                audit.record(PlanChangeService.CANCELLED, null, null, PlanChangeService.ENTITY_SUBSCRIPTION,
                        String.valueOf(change.subscriptionId()), PlanChangeService.details(from, to,
                                change.effectiveDate(), change.chargeAmount(), GUARD_FAILED_REASON));
                return ApplyOutcome.CANCELLED;
            }
            try {
                provisioning.apply(change.msisdn(), to, change.effectiveDate());
            } catch (RuntimeException e) {
                log.warn("Provisioning failed for plan change {} (attempt {}); retried next run: {}", id,
                        change.applyAttempts() + 1, e.getMessage());
                status.setRollbackOnly(); // undo the plan switch; the change stays SCHEDULED
                return ApplyOutcome.FAILED;
            }
            planChanges.markApplied(id, now);
            audit.record(PlanChangeService.APPLIED, null, null, PlanChangeService.ENTITY_SUBSCRIPTION,
                    String.valueOf(change.subscriptionId()), PlanChangeService.details(from, to,
                            change.effectiveDate(), change.chargeAmount(), null));
            return ApplyOutcome.APPLIED;
        });
        if (outcome == ApplyOutcome.FAILED) {
            recordFailure(id);
        }
        return outcome;
    }

    /** R6 provider failure: count the attempt and audit FAILURE, in a transaction of its own. */
    private void recordFailure(long id) {
        tx.executeWithoutResult(status -> planChanges.lockScheduled(id).ifPresent(change -> {
            planChanges.countFailedAttempt(id, clock.instant());
            Map<Long, Plan> plans = catalog.plansByIds(List.of(change.fromPlanId(), change.toPlanId()));
            audit.recordIndependently(PlanChangeService.APPLY_FAILED, Outcome.FAILURE, null, null,
                    PlanChangeService.ENTITY_SUBSCRIPTION, String.valueOf(change.subscriptionId()),
                    PlanChangeService.details(plans.get(change.fromPlanId()).code(),
                            plans.get(change.toPlanId()).code(), change.effectiveDate(), change.chargeAmount(),
                            PROVIDER_FAILED_REASON));
        }));
    }
}
