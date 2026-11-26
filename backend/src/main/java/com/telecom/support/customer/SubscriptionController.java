package com.telecom.support.customer;

import com.telecom.support.common.idempotency.IdempotencyService;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.customer.PlanQuoteService.PlanOption;
import com.telecom.support.customer.SubscriptionService.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * {@code /api/v1/customers/me/subscriptions} — E5, E6, E7 (design 02-customer backend §4, §5, R1, R4–R6;
 * openapi.yaml). Every endpoint needs its §8 permission and a customer principal with a linked customer, so staff
 * (INBOUND_AGENT holds {@code plan.read}) and unlinked customers get 403. The account always comes from the principal.
 */
@RestController
@RequestMapping("/api/v1/customers/me/subscriptions")
public class SubscriptionController {

    /** Idempotency operation of E7 (design §5). */
    static final String PLAN_CHANGE_OPERATION = "PLAN_CHANGE";

    /** The {@code PlanChangeRequest} schema: the quote the customer confirmed ({@code expectedCharge} is Money). */
    public record PlanChangeRequest(
            @NotBlank @Size(max = 32) String targetPlanCode,
            @NotNull LocalDate expectedEffectiveDate,
            @NotNull @Pattern(regexp = "^\\d{1,10}\\.\\d{2}$", message = "must be an amount like 499.00")
            String expectedCharge) {}

    /** The canonical idempotent request: path variables + body (D6), so the same key on another subscription → 422. */
    record PlanChangeKeyedRequest(long subscriptionId, PlanChangeRequest body) {}

    private final SubscriptionService subscriptions;
    private final PlanQuoteService quotes;
    private final PlanChangeService planChanges;
    private final IdempotencyService idempotency;
    private final ActorProvider actors;

    public SubscriptionController(SubscriptionService subscriptions, PlanQuoteService quotes,
                                  PlanChangeService planChanges, IdempotencyService idempotency,
                                  ActorProvider actors) {
        this.subscriptions = subscriptions;
        this.quotes = quotes;
        this.planChanges = planChanges;
        this.idempotency = idempotency;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_READ)"
            + " and @customerScope.isCustomer()")
    public List<Subscription> listMySubscriptions() {
        return subscriptions.mySubscriptions();
    }

    @GetMapping("/{subscriptionId}/plan-options")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_READ)"
            + " and @customerScope.isCustomer()")
    public List<PlanOption> listPlanOptions(@PathVariable long subscriptionId) {
        return quotes.optionsFor(subscriptionId);
    }

    @PostMapping(path = "/{subscriptionId}/plan-changes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_CHANGE)"
            + " and @customerScope.isCustomer()")
    public ResponseEntity<?> schedulePlanChange(
            @PathVariable long subscriptionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlanChangeRequest body) {
        long userId = actors.currentActor().orElseThrow().id();
        return idempotency.execute(userId, PLAN_CHANGE_OPERATION, idempotencyKey,
                new PlanChangeKeyedRequest(subscriptionId, body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(planChanges.schedule(subscriptionId,
                        body.targetPlanCode().trim(), body.expectedEffectiveDate(),
                        new BigDecimal(body.expectedCharge()))));
    }
}
