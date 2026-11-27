package com.telecom.support.reactivation;

import com.telecom.support.audit.AuditEvent.Outcome;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.NotificationContacts;
import com.telecom.support.notification.NotificationSender.Template;
import com.telecom.support.notification.NotificationService;
import com.telecom.support.notification.NotificationService.Contacts;
import com.telecom.support.reactivation.ReactivationRepository.RequestRow;
import java.time.Clock;
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
    static final String PROVIDER_UNKNOWN = "provider_unknown";

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
