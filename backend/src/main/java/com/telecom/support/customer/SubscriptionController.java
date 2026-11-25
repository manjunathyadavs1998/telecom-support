package com.telecom.support.customer;

import com.telecom.support.customer.SubscriptionService.Subscription;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/customers/me/subscriptions} — E5 (design 02-customer backend §4, §5, R1, R4; openapi.yaml). Needs
 * {@code plan.read} and a customer principal with a linked customer, so staff (INBOUND_AGENT holds {@code plan.read})
 * and unlinked customers get 403.
 */
@RestController
@RequestMapping("/api/v1/customers/me/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_READ)"
            + " and @customerScope.isCustomer()")
    public List<Subscription> listMySubscriptions() {
        return subscriptions.mySubscriptions();
    }
}
