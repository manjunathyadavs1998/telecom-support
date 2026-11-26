package com.telecom.support.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.telecom.support.catalog.CatalogService.Benefit;
import com.telecom.support.catalog.CatalogService.BenefitType;
import com.telecom.support.catalog.CatalogService.Plan;
import com.telecom.support.catalog.CatalogService.PlanStatus;
import com.telecom.support.catalog.CatalogService.PlanType;
import com.telecom.support.catalog.CatalogService.Promotion;
import com.telecom.support.catalog.CatalogService.PromotionStatus;
import com.telecom.support.support.AbstractMySqlIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Read-only catalog API (design 02-customer backend §1, §6; CUS-03.2, CUS-04.1). Test data:
 * R__test_users_02_customer.sql (6 plans, 1 RETIRED; promotions T_DATA_BOOST all plans, T_OTT_POST POST_599 only,
 * T_FESTIVE_OLD expired, T_INACTIVE inactive).
 */
class CatalogServiceIT extends AbstractMySqlIT {

    @Autowired
    CatalogService catalog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void plansByIdsIncludeRetiredPlansAndAllBenefitsInOrder() {
        long post599 = planId("POST_599");
        long post349 = planId("POST_349");

        Map<Long, Plan> plans = catalog.plansByIds(List.of(post599, post349, post599, -1L));

        assertThat(plans).containsOnlyKeys(post599, post349);
        Plan plan = plans.get(post599);
        assertThat(plan.code()).isEqualTo("POST_599");
        assertThat(plan.name()).isEqualTo("Postpaid 599");
        assertThat(plan.planType()).isEqualTo(PlanType.POSTPAID);
        assertThat(plan.monthlyRental()).isEqualByComparingTo(new BigDecimal("599.00"));
        assertThat(plan.description()).isEqualTo("150 GB, unlimited calls, OTT");
        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.benefits()).containsExactly(
                new Benefit(BenefitType.DATA, "Data", "150 GB/month", true),
                new Benefit(BenefitType.VOICE, "Calls", "Unlimited", true),
                new Benefit(BenefitType.OTT, "Streaming", "Video app premium", true),
                new Benefit(BenefitType.ROAMING, "Roaming", "Incoming free", false));

        Plan retired = plans.get(post349);
        assertThat(retired.status()).isEqualTo(PlanStatus.RETIRED);
        assertThat(retired.description()).isNull();
        assertThat(retired.benefits()).extracting(Benefit::type).containsExactly(BenefitType.DATA, BenefitType.VOICE);
    }

    @Test
    void emptyIdListGivesNoPlans() {
        assertThat(catalog.plansByIds(List.of())).isEmpty();
    }

    @Test
    void planByCode() {
        assertThat(catalog.planByCode("PRE_299")).get()
                .satisfies(p -> {
                    assertThat(p.id()).isEqualTo(planId("PRE_299"));
                    assertThat(p.planType()).isEqualTo(PlanType.PREPAID);
                    assertThat(p.benefits()).hasSize(3);
                });
        assertThat(catalog.planByCode("NOPE")).isEmpty();
    }

    @Test
    void activePlansExcludeRetiredAndAreCheapestFirst() {
        assertThat(catalog.activePlans()).extracting(Plan::code)
                .containsSubsequence("PRE_199", "PRE_299", "POST_399", "PRE_449", "POST_599")
                .doesNotContain("POST_349");
        assertThat(catalog.activePlans()).allSatisfy(p -> assertThat(p.benefits()).isNotEmpty());
    }

    @Test
    void livePromotionsAreActiveAndInsideTheirWindow() {
        List<Promotion> live = catalog.livePromotions(Instant.now());

        assertThat(live).extracting(Promotion::code)
                .contains("T_DATA_BOOST", "T_OTT_POST")
                .doesNotContain("T_FESTIVE_OLD", "T_INACTIVE");
        Promotion open = live.stream().filter(p -> p.code().equals("T_DATA_BOOST")).findFirst().orElseThrow();
        assertThat(open.eligiblePlanIds()).isEmpty();
        assertThat(open.appliesToPlan(planId("PRE_199"))).isTrue();
        assertThat(open.status()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(open.terms()).isEqualTo("Test terms: 10 GB added once per subscription, valid 30 days.");
        assertThat(open.validFrom()).isBefore(Instant.now()).isAfter(Instant.now().minus(31, ChronoUnit.DAYS));

        Promotion restricted = live.stream().filter(p -> p.code().equals("T_OTT_POST")).findFirst().orElseThrow();
        assertThat(restricted.eligiblePlanIds()).containsExactly(planId("POST_599"));
        assertThat(restricted.appliesToPlan(planId("POST_599"))).isTrue();
        assertThat(restricted.appliesToPlan(planId("POST_399"))).isFalse();
    }

    @Test
    void validityWindowIsFromInclusiveToExclusive() {
        Promotion boost = catalog.promotionByCode("T_DATA_BOOST").orElseThrow();

        assertThat(codes(catalog.livePromotions(boost.validFrom()))).contains("T_DATA_BOOST");
        assertThat(codes(catalog.livePromotions(boost.validFrom().minusNanos(1000)))).doesNotContain("T_DATA_BOOST");
        assertThat(codes(catalog.livePromotions(boost.validTo().minusNanos(1000)))).contains("T_DATA_BOOST");
        assertThat(codes(catalog.livePromotions(boost.validTo()))).doesNotContain("T_DATA_BOOST");
    }

    @Test
    void promotionByCodeReturnsAnyStatus() {
        assertThat(catalog.promotionByCode("T_INACTIVE")).get()
                .extracting(Promotion::status).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(catalog.promotionByCode("T_FESTIVE_OLD")).get()
                .satisfies(p -> assertThat(p.validTo()).isBefore(Instant.now()));
        assertThat(catalog.promotionByCode("NOPE")).isEmpty();
    }

    private static List<String> codes(List<Promotion> promotions) {
        return promotions.stream().map(Promotion::code).toList();
    }

    private long planId(String code) {
        return jdbc.queryForObject("SELECT id FROM plans WHERE code = :code", Map.of("code", code), Long.class);
    }
}
