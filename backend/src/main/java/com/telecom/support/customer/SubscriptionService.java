package com.telecom.support.customer;

import com.telecom.support.catalog.CatalogService;
import com.telecom.support.catalog.CatalogService.BenefitType;
import com.telecom.support.catalog.CatalogService.Plan;
import com.telecom.support.catalog.CatalogService.PlanType;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.PlanChangeRepository.PlanChangeRow;
import com.telecom.support.customer.SubscriptionRepository.SubscriptionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Own subscriptions with plan summary and benefits (design 02-customer backend R1, R4; CUS-03.1–03.3). The account
 * always comes from {@link CustomerScope}. No usage fields (CUS-03.3).
 */
@Service
public class SubscriptionService {

    public enum SubscriptionStatus { ACTIVE, SUSPENDED, TERMINATED }

    public enum PlanChangeStatus { SCHEDULED, APPLIED, CANCELLED }

    /** The {@code Benefit} schema; {@code key} benefits form the collapsed summary (R4). */
    public record Benefit(BenefitType type, String label, String value, boolean key) {}

    /** The {@code PlanSummary} schema; {@code monthlyRental} is {@code Money} ("599.00"); all benefits (expanded view). */
    public record PlanSummary(String code, String name, PlanType planType, String monthlyRental, String description,
                              List<Benefit> benefits) {}

    /** The {@code PlanChange} schema. */
    public record PlanChange(long id, String fromPlanCode, String toPlanCode, LocalDate effectiveDate, String charge,
                             PlanChangeStatus status) {}

    /** The {@code Subscription} schema; {@code pendingPlanChange} is the SCHEDULED change or null. */
    public record Subscription(long id, String msisdn, boolean primary, SubscriptionStatus status, int billingCycleDay,
                               PlanSummary plan, PlanChange pendingPlanChange) {}

    private final SubscriptionRepository subscriptions;
    private final PlanChangeRepository planChanges;
    private final AccountLookup accounts;
    private final CatalogService catalog;
    private final CustomerScope scope;

    SubscriptionService(SubscriptionRepository subscriptions, PlanChangeRepository planChanges, AccountLookup accounts,
                        CatalogService catalog, CustomerScope scope) {
        this.subscriptions = subscriptions;
        this.planChanges = planChanges;
        this.accounts = accounts;
        this.catalog = catalog;
        this.scope = scope;
    }

    /** E5: every subscription of the signed-in customer's account, primary first. Allowed in every account state (R16). */
    @Transactional(readOnly = true)
    public List<Subscription> mySubscriptions() {
        long customerId = scope.requireCustomerId();
        long accountId = accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"))
                .accountId();
        List<SubscriptionRow> rows = subscriptions.findByAccountId(accountId);
        Map<Long, PlanChangeRow> pending = planChanges.findScheduled(rows.stream().map(SubscriptionRow::id).toList())
                .stream()
                .collect(Collectors.toMap(PlanChangeRow::subscriptionId, Function.identity()));

        Set<Long> planIds = new HashSet<>();
        rows.forEach(r -> planIds.add(r.planId()));
        pending.values().forEach(c -> {
            planIds.add(c.fromPlanId());
            planIds.add(c.toPlanId());
        });
        Map<Long, Plan> plans = catalog.plansByIds(planIds);

        return rows.stream()
                .map(r -> new Subscription(r.id(), r.msisdn(), r.primary(), SubscriptionStatus.valueOf(r.status()),
                        r.billingCycleDay(), summary(plan(plans, r.planId())), planChange(pending.get(r.id()), plans)))
                .toList();
    }

    static PlanSummary summary(Plan plan) {
        return new PlanSummary(plan.code(), plan.name(), plan.planType(), money(plan.monthlyRental()),
                plan.description(), plan.benefits().stream()
                        .map(b -> new Benefit(b.type(), b.label(), b.value(), b.key()))
                        .toList());
    }

    /** {@code Money}: two fraction digits, plain notation. */
    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static PlanChange planChange(PlanChangeRow row, Map<Long, Plan> plans) {
        if (row == null) {
            return null;
        }
        return new PlanChange(row.id(), plan(plans, row.fromPlanId()).code(), plan(plans, row.toPlanId()).code(),
                row.effectiveDate(), money(row.chargeAmount()), PlanChangeStatus.valueOf(row.status()));
    }

    private static Plan plan(Map<Long, Plan> plans, long planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            throw new IllegalStateException("Plan " + planId + " not in catalog");
        }
        return plan;
    }
}
