package com.telecom.support.customer;

import com.telecom.support.audit.AuditService;
import com.telecom.support.catalog.CatalogService;
import com.telecom.support.catalog.CatalogService.Promotion;
import com.telecom.support.catalog.CatalogService.PromotionStatus;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.PromotionClaimRepository.ClaimRow;
import com.telecom.support.customer.SubscriptionRepository.SubscriptionRow;
import com.telecom.support.customer.SubscriptionService.SubscriptionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotion eligibility (R7, E8) and claims (R8, E9) for the signed-in customer (design 02-customer backend R1, R7, R8,
 * R16, §7; CUS-04, XC-06). The account always comes from {@link CustomerScope}; plan applicability comes from
 * {@link CatalogService}. A claim is recorded and audited only; nothing is fulfilled (OQ 7).
 */
@Service
public class PromotionEligibilityService {

    static final String CLAIMED = "PROMOTION_CLAIMED";
    static final String ENTITY_PROMOTION = "PROMOTION";

    /** The {@code Promotion} schema. {@code eligibleSubscriptionIds}: own ACTIVE subscriptions that may still claim it. */
    public record PromotionView(String code, String title, String shortDescription, String eligibility, String terms,
                                String termsHash, Instant validFrom, Instant validTo, boolean claimed,
                                List<Long> eligibleSubscriptionIds) {}

    public enum ClaimStatus { CLAIMED }

    /** The {@code PromotionClaim} schema. */
    public record PromotionClaim(String promotionCode, long subscriptionId, ClaimStatus status, Instant claimedAt) {}

    private final CatalogService catalog;
    private final SubscriptionRepository subscriptions;
    private final PromotionClaimRepository claims;
    private final AccountLookup accounts;
    private final CustomerScope scope;
    private final AuditService audit;
    private final ActorProvider actors;
    private final Clock clock;

    PromotionEligibilityService(CatalogService catalog, SubscriptionRepository subscriptions,
                                PromotionClaimRepository claims, AccountLookup accounts, CustomerScope scope,
                                AuditService audit, ActorProvider actors, Clock clock) {
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.claims = claims;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * E8 (R7): live promotions (ACTIVE, {@code validFrom <= now < validTo}) that are open to all plans or apply to the
     * plan of an ACTIVE own subscription, soonest-ending first. {@code claimed} is true when an own subscription has
     * claimed it; {@code eligibleSubscriptionIds} lists the own ACTIVE subscriptions on an applicable plan that have not
     * claimed it yet. Reads are allowed in every account state (R16).
     */
    @Transactional(readOnly = true)
    public List<PromotionView> myPromotions() {
        List<SubscriptionRow> own = subscriptions.findByAccountId(myAccount().accountId());
        List<SubscriptionRow> active = own.stream().filter(PromotionEligibilityService::isActive).toList();
        Map<Long, Set<Long>> claimedBy = claims.findBySubscriptions(own.stream().map(SubscriptionRow::id).toList())
                .stream()
                .collect(Collectors.groupingBy(ClaimRow::promotionId,
                        Collectors.mapping(ClaimRow::subscriptionId, Collectors.toSet())));

        return catalog.livePromotions(clock.instant()).stream()
                .filter(p -> p.eligiblePlanIds().isEmpty() || active.stream().anyMatch(s -> p.appliesToPlan(s.planId())))
                .map(p -> {
                    Set<Long> claimedSubs = claimedBy.getOrDefault(p.id(), Set.of());
                    List<Long> eligible = active.stream()
                            .filter(s -> p.appliesToPlan(s.planId()) && !claimedSubs.contains(s.id()))
                            .map(SubscriptionRow::id)
                            .toList();
                    return new PromotionView(p.code(), p.title(), p.shortDescription(), p.eligibilityText(),
                            p.terms(), termsHash(p.terms()), p.validFrom(), p.validTo(), !claimedSubs.isEmpty(),
                            eligible);
                })
                .toList();
    }

    /**
     * E9 (R8): claims {@code promotionCode} for one own subscription after the customer accepted the current terms.
     * Unknown promotion or another account's subscription → 404. INACTIVE customer, CLOSED or SUSPENDED account (R16,
     * OQ 12), {@code acceptTerms} not true, a stale {@code termsHash}, a promotion that is not live, a subscription that
     * is not ACTIVE or not on an applicable plan, or a second claim → 422. Writes {@code PROMOTION_CLAIMED}.
     */
    @Transactional
    public PromotionClaim claim(String promotionCode, long subscriptionId, boolean acceptTerms, String termsHash) {
        CustomerAccount account = myAccount();
        SubscriptionRow subscription = subscriptions.findOwned(account.accountId(), subscriptionId)
                .orElseThrow(() -> new NotFoundException(
                        "Subscription " + subscriptionId + " not found in account " + account.accountId()));
        Promotion promotion = catalog.promotionByCode(promotionCode)
                .orElseThrow(() -> new NotFoundException("Promotion " + promotionCode + " not found"));
        account.requireMutable();
        if (account.status() == AccountStatus.SUSPENDED) {
            throw new BusinessRuleException("Promotion claim on suspended account " + account.accountId(),
                    "This account is suspended, so offers cannot be claimed.");
        }
        if (!acceptTerms) {
            throw new BusinessRuleException("Promotion " + promotionCode + " claimed without accepting the terms",
                    "Please accept the offer terms to claim it.");
        }
        if (!termsHash(promotion.terms()).equals(termsHash)) {
            throw new BusinessRuleException("Stale terms hash for promotion " + promotionCode,
                    "The offer terms have changed. Please review the new terms.");
        }
        Instant now = clock.instant();
        if (!isLive(promotion, now)) {
            throw new BusinessRuleException("Promotion " + promotionCode + " is not live",
                    "This offer is no longer available.");
        }
        if (!isActive(subscription) || !promotion.appliesToPlan(subscription.planId())) {
            throw new BusinessRuleException(
                    "Subscription " + subscriptionId + " is not eligible for promotion " + promotionCode,
                    "This offer is not available for this number.");
        }

        Actor actor = actors.currentActor().orElseThrow();
        try {
            claims.insert(promotion.id(), subscriptionId, termsHash, actor.id(), now);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException(
                    "Promotion " + promotionCode + " already claimed by subscription " + subscriptionId,
                    "This offer has already been claimed for this number.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscriptionId", subscriptionId);
        details.put("termsHash", termsHash);
        audit.record(CLAIMED, actor.id(), actor.username(), ENTITY_PROMOTION, promotion.code(), details);
        return new PromotionClaim(promotion.code(), subscriptionId, ClaimStatus.CLAIMED, now);
    }

    /** R7: lower-case hex SHA-256 of the UTF-8 terms. */
    static String termsHash(String terms) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(terms.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private CustomerAccount myAccount() {
        long customerId = scope.requireCustomerId();
        return accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"));
    }

    private static boolean isLive(Promotion promotion, Instant now) {
        return promotion.status() == PromotionStatus.ACTIVE
                && !promotion.validFrom().isAfter(now) && promotion.validTo().isAfter(now);
    }

    private static boolean isActive(SubscriptionRow subscription) {
        return SubscriptionStatus.ACTIVE.name().equals(subscription.status());
    }
}
