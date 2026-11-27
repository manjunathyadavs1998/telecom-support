package com.telecom.support.reactivation;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.NotificationContacts;
import com.telecom.support.notification.NotificationSender.Template;
import com.telecom.support.notification.NotificationService;
import com.telecom.support.notification.NotificationService.Contacts;
import com.telecom.support.reactivation.ReactivationRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code reactivation_requests.status} after the PENDING insert (design 03-inbound backend R15,
 * §3.1; INB-04.4/04.5, BR-03, BR-08, NFR-INTEG). Every transition is one guarded
 * {@code UPDATE … WHERE id = :id AND status IN (:from)} in its own transaction; its side effects (customer
 * notification through the outbox, audit) run in that transaction and only when exactly one row changed, so parallel
 * request threads, jobs and instances act at most once. {@code actor} is null for job transitions.
 */
@Service
public class ReactivationTransitionService {

    static final String ENTITY = "REACTIVATION";
    static final String PENDING = "PENDING";
    static final String ACTIVE = "ACTIVE";
    static final String FAILED = "FAILED";
    static final String EXPIRING = "EXPIRING";
    static final String EXPIRED = "EXPIRED";
    static final String ENDED = "ENDED";
    static final String PROVIDER_UNKNOWN = "provider_unknown";
    static final String SOURCE_RECONCILE = "reconcile";
    static final String SOURCE_EXPIRY = "expiry";
    /** §3.1 retry backoff by step (0 = first wait): 1, 2, 5, 15 min, then every 60 min, without limit. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(60));

    private static final Logger log = LoggerFactory.getLogger(ReactivationTransitionService.class);

    private final ReactivationRepository requests;
    private final AccountLookup accounts;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    ReactivationTransitionService(ReactivationRepository requests, AccountLookup accounts,
                                  NotificationService notifications, AuditService audit, Clock clock) {
        this.requests = requests;
        this.accounts = accounts;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * PENDING → ACTIVE with {@code activated_at}/{@code expires_at}: enqueues {@code REACTIVATION_STARTED} and writes
     * {@code REACTIVATION_ACTIVATED}. {@code source} is {@code request} or {@code reconcile}.
     *
     * @return true when this call made the transition
     */
    @Transactional
    public boolean activate(RequestRow row, String providerRef, Instant activatedAt, Instant expiresAt, String source,
                            Actor actor) {
        if (!requests.activate(row.id(), List.of(PENDING), providerRef, activatedAt, expiresAt, clock.instant())) {
            return false;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(NotificationService.REFERENCE, row.reference());
        params.put("activatedAt", activatedAt.toString());
        params.put("expiresAt", expiresAt.toString());
        notify(row, Template.REACTIVATION_STARTED, params);

        Map<String, Object> details = transition(PENDING, ACTIVE, source);
        details.put("activatedAt", activatedAt.toString());
        details.put("expiresAt", expiresAt.toString());
        details.put("requestedHours", row.requestedHours());
        audit.record("REACTIVATION_ACTIVATED", Outcome.SUCCESS, actorId(actor), actorName(actor), ENTITY,
                row.reference(), details);
        return true;
    }

    /**
     * PENDING → FAILED with {@code failureReason} ({@code provider_rejected}, {@code provider_unavailable},
     * {@code not_created}, {@code not_restored}): writes {@code REACTIVATION_FAILED} (FAILURE). No notification (the
     * customer's service never changed).
     *
     * @return true when this call made the transition
     */
    @Transactional
    public boolean fail(RequestRow row, String failureReason, String source, Actor actor) {
        if (!requests.fail(row.id(), List.of(PENDING), failureReason, clock.instant())) {
            return false;
        }
        Map<String, Object> details = transition(PENDING, FAILED, source);
        details.put("reason", failureReason);
        audit.record("REACTIVATION_FAILED", Outcome.FAILURE, actorId(actor), actorName(actor), ENTITY,
                row.reference(), details);
        return true;
    }

    /**
     * Restore timeout / 5xx (§3.1): the request stays PENDING with {@code failure_reason = provider_unknown} and is
     * reconciled from {@code nextAttemptAt} on; no audit (the outcome is not known yet).
     *
     * @return true when the row was still PENDING
     */
    @Transactional
    public boolean markOutcomeUnknown(RequestRow row, Instant nextAttemptAt) {
        boolean changed = requests.markOutcomeUnknown(row.id(), List.of(PENDING), PROVIDER_UNKNOWN, nextAttemptAt,
                clock.instant());
        if (changed) {
            log.warn("Reactivation {}: restore outcome unknown; reconciled from {}", row.reference(), nextAttemptAt);
        }
        return changed;
    }

    /**
     * Reconcile: RESTORED, but the window ({@code restoredAt + hours}) is already over: PENDING → EXPIRING with the
     * restore times, so the expiry job suspends it next and the 24 h cap holds. Writes {@code REACTIVATION_ACTIVATED}
     * (to EXPIRING); no {@code REACTIVATION_STARTED} notification (the service is being suspended).
     *
     * @return true when this call made the transition
     */
    @Transactional
    public boolean restoredLate(RequestRow row, String providerRef, Instant activatedAt, Instant expiresAt) {
        if (!requests.restoredLate(row.id(), List.of(PENDING), providerRef, activatedAt, expiresAt, clock.instant())) {
            return false;
        }
        Map<String, Object> details = transition(PENDING, EXPIRING, SOURCE_RECONCILE);
        details.put("activatedAt", activatedAt.toString());
        details.put("expiresAt", expiresAt.toString());
        details.put("requestedHours", row.requestedHours());
        audit.record("REACTIVATION_ACTIVATED", Outcome.SUCCESS, null, null, ENTITY, row.reference(), details);
        log.warn("Reactivation {}: restore confirmed after its window; suspending", row.reference());
        return true;
    }

    /**
     * Expiry, ACTIVE rows (claim tx): locks the account, then ACTIVE → ENDED {@code account_reinstated} when due and
     * the account is ACTIVE again (no provider call; notify + audit); ACTIVE → EXPIRING when due ({@code expired}) or
     * when the account's reason is now FRAUD/LEGAL/REGULATORY ({@code policy_prohibited}, early). Otherwise nothing.
     *
     * <p>Lock order (§3.1, E1, review C6): agent-scoped rows before customer/account rows. This job takes no agent
     * row (accounts X, then the FK S lock on {@code customers} for the ENDED notification); I14 tx1 takes users X
     * then accounts X; KBA/OTP take users S (and the agent's KBA hour row) before customers X, so none of them waits
     * on an agent row while holding a customer or account row and the former 3-way cycle cannot form.
     *
     * @return the new status when this call made a transition, else null
     */
    @Transactional
    public String closeOrStartExpiring(RequestRow row) {
        CustomerAccount account = accounts.lockForUpdate(row.accountId());
        Instant now = clock.instant();
        boolean due = !row.expiresAt().isAfter(now);
        if (due && account.status() == AccountStatus.ACTIVE) {
            return end(row, "account_reinstated", now) ? ENDED : null;
        }
        String endReason = due ? "expired"
                : ReactivationEligibilityService.PROHIBITED.contains(account.suspensionReason()) ? "policy_prohibited"
                : null;
        if (endReason == null || !requests.startExpiring(row.id(), List.of(ACTIVE), endReason, now)) {
            return null;
        }
        log.info("Reactivation {}: ACTIVE -> EXPIRING ({})", row.reference(), endReason);
        return EXPIRING;
    }

    /**
     * EXPIRING → EXPIRED (suspend confirmed, or the provider reports SUSPENDED): {@code ended_at}, enqueues
     * {@code REACTIVATION_ENDED}, writes {@code REACTIVATION_EXPIRED}. Frees the open-request UK.
     *
     * @return true when this call made the transition
     */
    @Transactional
    public boolean expire(RequestRow row) {
        Instant now = clock.instant();
        if (!requests.expire(row.id(), List.of(EXPIRING), now)) {
            return false;
        }
        endedSideEffects(row, EXPIRING, EXPIRED, "REACTIVATION_EXPIRED", row.endReason(), now);
        return true;
    }

    /**
     * One failed reconcile or suspend attempt: {@code attempts + 1}, next attempt {@code nextAttemptAt}. Guarded by the
     * row's version, so parallel runs count a failure once.
     *
     * @return true when this call counted it
     */
    @Transactional
    public boolean retry(RequestRow row, Instant nextAttemptAt) {
        return requests.retry(row.id(), row.status(), row.version(), nextAttemptAt, clock.instant());
    }

    /**
     * Sets the {@code needs_attention} alert flag once ({@code unresolved_restore} or {@code suspend_failing}) with
     * {@code REACTIVATION_ATTENTION} (FAILURE) and an ERROR log. Automation continues.
     *
     * @return true when this call set it
     */
    @Transactional
    public boolean flagAttention(RequestRow row, String reason, int attempts) {
        if (!requests.flagAttention(row.id(), row.status(), reason, clock.instant())) {
            return false;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("status", row.status());
        details.put("attempts", attempts);
        audit.record("REACTIVATION_ATTENTION", Outcome.FAILURE, null, null, ENTITY, row.reference(), details);
        log.error("Reactivation {} needs attention: {} ({} attempts, still {})", row.reference(), reason, attempts,
                row.status());
        return true;
    }

    /**
     * Restored for 24 h or more and not yet suspended: once, {@code REACTIVATION_OVERDUE} (FAILURE) and an ERROR log.
     *
     * @return true when this call raised it
     */
    @Transactional
    public boolean alertOverdue(RequestRow row) {
        Instant now = clock.instant();
        if (!requests.markOverdue(row.id(), now)) {
            return false;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restoredHours", Duration.between(row.activatedAt(), now).toHours());
        details.put("attempts", row.attempts());
        details.put("status", row.status());
        audit.record("REACTIVATION_OVERDUE", Outcome.FAILURE, null, null, ENTITY, row.reference(), details);
        log.error("Reactivation {} is restored past 24 h (still {}, {} suspend attempts)", row.reference(),
                row.status(), row.attempts());
        return true;
    }

    /** §3.1 backoff of retry step {@code step} (0 = first wait); every 60 min from step 4 on, without limit. */
    static Duration backoff(int step) {
        return BACKOFF.get(Math.max(0, Math.min(step, BACKOFF.size() - 1)));
    }

    private boolean end(RequestRow row, String endReason, Instant now) {
        if (!requests.end(row.id(), List.of(ACTIVE), endReason, now)) {
            return false;
        }
        endedSideEffects(row, ACTIVE, ENDED, "REACTIVATION_ENDED", endReason, now);
        return true;
    }

    private void endedSideEffects(RequestRow row, String from, String to, String action, String endReason,
                                  Instant endedAt) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(NotificationService.REFERENCE, row.reference());
        params.put("endedAt", endedAt.toString());
        notify(row, Template.REACTIVATION_ENDED, params);

        Map<String, Object> details = transition(from, to, SOURCE_EXPIRY);
        details.put("activatedAt", row.activatedAt() == null ? null : row.activatedAt().toString());
        details.put("expiresAt", row.expiresAt() == null ? null : row.expiresAt().toString());
        details.put("endedAt", endedAt.toString());
        details.put("endReason", endReason);
        audit.record(action, Outcome.SUCCESS, null, null, ENTITY, row.reference(), details);
    }

    private void notify(RequestRow row, Template template, Map<String, String> params) {
        NotificationContacts contacts = accounts.notificationContacts(row.accountId());
        notifications.enqueue(contacts.customerId(), template, params,
                new Contacts(contacts.primaryMsisdn(), contacts.verifiedEmail()));
    }

    private static Map<String, Object> transition(String from, String to, String source) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from);
        details.put("to", to);
        details.put("source", source);
        return details;
    }

    private static Long actorId(Actor actor) {
        return actor == null ? null : actor.id();
    }

    private static String actorName(Actor actor) {
        return actor == null ? null : actor.username();
    }
}
