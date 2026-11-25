package com.telecom.support.customer;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.PlanQuoteService.Quote;
import com.telecom.support.customer.SubscriptionRepository.SubscriptionRow;
import com.telecom.support.customer.SubscriptionService.PlanChange;
import com.telecom.support.customer.SubscriptionService.PlanChangeStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schedules a confirmed plan change (design 02-customer backend R5, R6, R16, §7; CUS-03.5, XC-06). The account always
 * comes from {@link CustomerScope}. The change is only scheduled here; {@link PlanChangeApplyJob} switches the plan on
 * the effective date after the provisioning system confirms it (BR-08, XC-07).
 */
@Service
public class PlanChangeService {

    static final String SCHEDULED = "PLAN_CHANGE_SCHEDULED";
    static final String APPLIED = "PLAN_CHANGE_APPLIED";
    static final String CANCELLED = "PLAN_CHANGE_CANCELLED";
    static final String APPLY_FAILED = "PLAN_CHANGE_APPLY_FAILED";
    static final String ENTITY_SUBSCRIPTION = "SUBSCRIPTION";

    private final PlanQuoteService quotes;
    private final PlanChangeRepository planChanges;
    private final AccountLookup accounts;
    private final CustomerScope scope;
    private final AuditService audit;
    private final ActorProvider actors;
    private final Clock clock;

    PlanChangeService(PlanQuoteService quotes, PlanChangeRepository planChanges, AccountLookup accounts,
                      CustomerScope scope, AuditService audit, ActorProvider actors, Clock clock) {
        this.quotes = quotes;
        this.planChanges = planChanges;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * E7: schedules the change to {@code targetPlanCode} when a fresh quote (R5) matches {@code expectedEffectiveDate}
     * and {@code expectedCharge}. Another account's subscription → 404; INACTIVE customer, CLOSED or SUSPENDED account
     * (R16, OQ 12), an ineligible target, a quote mismatch or a second SCHEDULED change → 422. Writes
     * {@code PLAN_CHANGE_SCHEDULED}.
     */
    @Transactional
    public PlanChange schedule(long subscriptionId, String targetPlanCode, LocalDate expectedEffectiveDate,
                               BigDecimal expectedCharge) {
        long customerId = scope.requireCustomerId();
        CustomerAccount account = accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"));
        SubscriptionRow subscription = quotes.ownSubscription(account.accountId(), subscriptionId);
        account.requireMutable();
        if (account.status() == AccountStatus.SUSPENDED) {
            throw new BusinessRuleException("Plan change on suspended account " + account.accountId(),
                    "This account is suspended, so its plan cannot be changed.");
        }
        Quote quote = quotes.quote(subscription, targetPlanCode)
                .orElseThrow(() -> new BusinessRuleException(
                        "Plan " + targetPlanCode + " is not an eligible upgrade for subscription " + subscriptionId,
                        "This plan is not available as an upgrade for this subscription."));
        if (!quote.effectiveDate().equals(expectedEffectiveDate) || quote.charge().compareTo(expectedCharge) != 0) {
            throw new BusinessRuleException(
                    "Quote mismatch for subscription " + subscriptionId + ": expected " + expectedEffectiveDate + "/"
                            + expectedCharge + ", fresh " + quote.effectiveDate() + "/" + quote.charge(),
                    "The quote has changed. Please review the new effective date and charge.");
        }

        Actor actor = actors.currentActor().orElseThrow();
        long id;
        try {
            id = planChanges.insertScheduled(subscriptionId, quote.from().id(), quote.to().id(),
                    quote.effectiveDate(), quote.charge(), actor.id(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Subscription " + subscriptionId + " already has a scheduled change",
                    "A plan change is already scheduled for this subscription.");
        }
        audit.record(SCHEDULED, actor.id(), actor.username(), ENTITY_SUBSCRIPTION, String.valueOf(subscriptionId),
                details(quote.from().code(), quote.to().code(), quote.effectiveDate(), quote.charge(), null));
        return new PlanChange(id, quote.from().code(), quote.to().code(), quote.effectiveDate(),
                SubscriptionService.money(quote.charge()), PlanChangeStatus.SCHEDULED);
    }

    /** §7 {@code details}: from/to plan, effective date, charge and, when given, the reason. */
    static Map<String, Object> details(String fromPlanCode, String toPlanCode, LocalDate effectiveDate,
                                       BigDecimal charge, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromPlan", fromPlanCode);
        details.put("toPlan", toPlanCode);
        details.put("effectiveDate", effectiveDate.toString());
        details.put("charge", SubscriptionService.money(charge));
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }
}
