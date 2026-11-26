package com.telecom.support.catalog;

import com.telecom.support.catalog.PlanRepository.BenefitRow;
import com.telecom.support.catalog.PlanRepository.PlanRow;
import com.telecom.support.catalog.PromotionRepository.PromotionPlanRow;
import com.telecom.support.catalog.PromotionRepository.PromotionRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only plan, benefit and promotion data (design 02-customer backend §1, §6; CUS-03.2, CUS-04.1). {@code catalog}
 * depends on nothing in {@code customer}: quotes, promotion eligibility and claims live there and call this service.
 */
@Service
public class CatalogService {

    public enum PlanType { PREPAID, POSTPAID }

    public enum PlanStatus { ACTIVE, RETIRED }

    public enum BenefitType { VOICE, DATA, SMS, ROAMING, OTT, OTHER }

    public enum PromotionStatus { ACTIVE, INACTIVE }

    /** One plan benefit; {@code key} benefits are shown in the collapsed plan card (R4). */
    public record Benefit(BenefitType type, String label, String value, boolean key) {}

    /** A plan with all its benefits in display order. */
    public record Plan(long id, String code, String name, PlanType planType, BigDecimal monthlyRental,
                       String description, PlanStatus status, List<Benefit> benefits) {}

    /** A promotion; {@code eligiblePlanIds} empty = open to every plan (R7). */
    public record Promotion(long id, String code, String title, String shortDescription, String eligibilityText,
                            String terms, Instant validFrom, Instant validTo, PromotionStatus status,
                            Set<Long> eligiblePlanIds) {

        /** True when the promotion is open to all plans or lists {@code planId}. */
        public boolean appliesToPlan(long planId) {
            return eligiblePlanIds.isEmpty() || eligiblePlanIds.contains(planId);
        }
    }

    private final PlanRepository plans;
    private final PromotionRepository promotions;

    CatalogService(PlanRepository plans, PromotionRepository promotions) {
        this.plans = plans;
        this.promotions = promotions;
    }

    /** Plans by id (any status, a subscription may still be on a RETIRED plan), keyed by id; unknown ids are absent. */
    @Transactional(readOnly = true)
    public Map<Long, Plan> plansByIds(Collection<Long> ids) {
        return withBenefits(plans.findByIds(Set.copyOf(ids)));
    }

    /** One plan by its code (any status). */
    @Transactional(readOnly = true)
    public Optional<Plan> planByCode(String code) {
        return withBenefits(plans.findByCode(code)).values().stream().findFirst();
    }

    /** Every ACTIVE plan, cheapest first. */
    @Transactional(readOnly = true)
    public List<Plan> activePlans() {
        return List.copyOf(withBenefits(plans.findActive()).values());
    }

    /** Promotions that are ACTIVE with {@code validFrom <= now < validTo}, soonest-ending first (R7). */
    @Transactional(readOnly = true)
    public List<Promotion> livePromotions(Instant now) {
        return withPlans(promotions.findLive(now));
    }

    /** One promotion by its code, in any status or validity (the caller checks R7/R8). */
    @Transactional(readOnly = true)
    public Optional<Promotion> promotionByCode(String code) {
        return withPlans(promotions.findByCode(code)).stream().findFirst();
    }

    private Map<Long, Plan> withBenefits(List<PlanRow> rows) {
        Map<Long, List<Benefit>> benefits = plans.findBenefits(rows.stream().map(PlanRow::id).toList()).stream()
                .collect(Collectors.groupingBy(BenefitRow::planId, LinkedHashMap::new, Collectors.mapping(
                        b -> new Benefit(BenefitType.valueOf(b.benefitType()), b.label(), b.valueText(), b.key()),
                        Collectors.toList())));
        Map<Long, Plan> result = new LinkedHashMap<>();
        for (PlanRow row : rows) {
            result.put(row.id(), new Plan(row.id(), row.code(), row.name(), PlanType.valueOf(row.planType()),
                    row.monthlyRental(), row.description(), PlanStatus.valueOf(row.status()),
                    List.copyOf(benefits.getOrDefault(row.id(), List.of()))));
        }
        return result;
    }

    private List<Promotion> withPlans(List<PromotionRow> rows) {
        Map<Long, Set<Long>> planIds = promotions.findPlans(rows.stream().map(PromotionRow::id).toList()).stream()
                .collect(Collectors.groupingBy(PromotionPlanRow::promotionId, Collectors.mapping(
                        PromotionPlanRow::planId, Collectors.toCollection(LinkedHashSet::new))));
        return rows.stream()
                .map(row -> new Promotion(row.id(), row.code(), row.title(), row.shortDescription(),
                        row.eligibilityText(), row.terms(), row.validFrom(), row.validTo(),
                        PromotionStatus.valueOf(row.status()),
                        Set.copyOf(planIds.getOrDefault(row.id(), Set.of()))))
                .toList();
    }
}
