package com.telecom.support.customer;

import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.customer.PromotionEligibilityService.PromotionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/promotions} — E8, E9 (design 02-customer backend §4, §5, R1, R7, R8; openapi.yaml). Both
 * need their §8 permission and a customer principal with a linked customer, so staff (INBOUND_AGENT holds
 * {@code promotion.read}) and unlinked customers get 403. The account always comes from the principal.
 */
@RestController
@RequestMapping("/api/v1/customers/me/promotions")
public class PromotionController {

    /** Idempotency operation of E9 (design §5). */
    static final String PROMOTION_CLAIM_OPERATION = "PROMOTION_CLAIM";

    /** The {@code PromotionClaimRequest} schema; {@code acceptTerms=false} or a stale {@code termsHash} → 422 (R8). */
    public record PromotionClaimRequest(
            @NotNull Long subscriptionId,
            @NotNull Boolean acceptTerms,
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a SHA-256 hex digest") String termsHash) {}

    /** The canonical idempotent request: path variable + body (D6), so the same key on another promotion → 422. */
    record PromotionClaimKeyedRequest(String promotionCode, PromotionClaimRequest body) {}

    private final PromotionEligibilityService promotions;
    private final IdempotencyService idempotency;
    private final ActorProvider actors;

    public PromotionController(PromotionEligibilityService promotions, IdempotencyService idempotency,
                               ActorProvider actors) {
        this.promotions = promotions;
        this.idempotency = idempotency;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PROMOTION_READ)"
            + " and @customerScope.isCustomer()")
    public List<PromotionView> listMyPromotions() {
        return promotions.myPromotions();
    }

    @PostMapping(path = "/{promotionCode}/claims", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PROMOTION_CLAIM)"
            + " and @customerScope.isCustomer()")
    public ResponseEntity<?> claimPromotion(
            @PathVariable String promotionCode,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PromotionClaimRequest body) {
        long userId = actors.currentActor().orElseThrow().id();
        return idempotency.execute(userId, PROMOTION_CLAIM_OPERATION, idempotencyKey,
                new PromotionClaimKeyedRequest(promotionCode, body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(promotions.claim(promotionCode,
                        body.subscriptionId(), body.acceptTerms(), body.termsHash())));
    }
}
