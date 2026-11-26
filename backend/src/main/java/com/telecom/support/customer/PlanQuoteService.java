package com.telecom.support.customer;

import com.telecom.support.catalog.CatalogService;
import com.telecom.support.catalog.CatalogService.Plan;
import com.telecom.support.catalog.CatalogService.PlanStatus;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.SubscriptionRepository.SubscriptionRow;
import com.telecom.support.customer.SubscriptionService.PlanSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upgrade options and quotes (design 02-customer backend R5, E6; CUS-03.4/03.5). A plan is an eligible upgrade when the
 * subscription is ACTIVE with no SCHEDULED change, and the target is ACTIVE, of the same {@code plan_type} and has a
 * higher rental. The quote's effective date is the first billing-cycle day strictly after today in
 * {@code app.display-time-zone} (Asia/Kolkata); the charge is 0.00 with no proration (OQ 10, user decision).
 */
@Service
public class PlanQuoteService {

    /** OQ 10 (approved): no charge and no proration for a plan change. */
    static final BigDecimal CHARGE = new BigDecimal("0.00");

    /** The {@code PlanOption} schema. */
    public record PlanOption(PlanSummary plan, String rentalDifference, LocalDate effectiveDate, String charge) {}

    /** A fresh quote for one target plan (used by E7 to check the customer's expected values). */
    record Quote(Plan from, Plan to, LocalDate effectiveDate, BigDecimal charge) {}

    private final SubscriptionRepository subscriptions;
    private final PlanChangeRepository planChanges;
    private final AccountLookup accounts;
    private final CatalogService catalog;
    private final CustomerScope scope;
    private final Clock clock;
    private final ZoneId zone;

    PlanQuoteService(SubscriptionRepository subscriptions, PlanChangeRepository planChanges, AccountLookup accounts,
                     CatalogService catalog, CustomerScope scope, Clock clock,
                     @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.subscriptions = subscriptions;
        this.planChanges = planChanges;
        this.accounts = accounts;
        this.catalog = catalog;
        this.scope = scope;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * E6: eligible upgrades of one own subscription, cheapest first; empty when the subscription is not ACTIVE or already
     * has a SCHEDULED change. Another account's (or an unknown) subscription → 404. Reads are allowed in every account
     * state (R16).
     */
    @Transactional(readOnly = true)
    public List<PlanOption> optionsFor(long subscriptionId) {
        SubscriptionRow subscription = ownSubscription(myAccountId(), subscriptionId);
        Plan current = currentPlan(subscription);
        if (!upgradable(subscription)) {
            return List.of();
        }
        LocalDate effectiveDate = effectiveDate(subscription.billingCycleDay(), today());
        return catalog.activePlans().stream()
                .filter(target -> isUpgrade(current, target))
                .map(target -> new PlanOption(SubscriptionService.summary(target),
                        SubscriptionService.money(target.monthlyRental().subtract(current.monthlyRental())),
                        effectiveDate, SubscriptionService.money(CHARGE)))
                .toList();
    }

    /** A fresh quote for {@code targetPlanCode}, or empty when that plan is not an eligible upgrade (R5). */
    Optional<Quote> quote(SubscriptionRow subscription, String targetPlanCode) {
        if (!upgradable(subscription)) {
            return Optional.empty();
        }
        Plan current = currentPlan(subscription);
        return catalog.planByCode(targetPlanCode)
                .filter(target -> isUpgrade(current, target))
                .map(target -> new Quote(current, target, effectiveDate(subscription.billingCycleDay(), today()),
                        CHARGE));
    }

    /** The subscription when it belongs to {@code accountId}; otherwise 404 (R1). */
    SubscriptionRow ownSubscription(long accountId, long subscriptionId) {
        return subscriptions.findOwned(accountId, subscriptionId)
                .orElseThrow(() -> new NotFoundException(
                        "Subscription " + subscriptionId + " not found in account " + accountId));
    }

    /** Today in the display zone (Asia/Kolkata). */
    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** R5: the first {@code billingCycleDay} (1–28) strictly after {@code today}. */
    static LocalDate effectiveDate(int billingCycleDay, LocalDate today) {
        LocalDate candidate = today.withDayOfMonth(billingCycleDay);
        return candidate.isAfter(today) ? candidate : candidate.plusMonths(1);
    }

    private long myAccountId() {
        long customerId = scope.requireCustomerId();
        return accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"))
                .accountId();
    }

    private boolean upgradable(SubscriptionRow subscription) {
        return SubscriptionService.SubscriptionStatus.ACTIVE.name().equals(subscription.status())
                && planChanges.findScheduled(List.of(subscription.id())).isEmpty();
    }

    private Plan currentPlan(SubscriptionRow subscription) {
        Plan plan = catalog.plansByIds(List.of(subscription.planId())).get(subscription.planId());
        if (plan == null) {
            throw new IllegalStateException("Plan " + subscription.planId() + " not in catalog");
        }
        return plan;
    }

    private static boolean isUpgrade(Plan current, Plan target) {
        return target.status() == PlanStatus.ACTIVE
                && target.planType() == current.planType()
                && target.monthlyRental().compareTo(current.monthlyRental()) > 0;
    }
}
