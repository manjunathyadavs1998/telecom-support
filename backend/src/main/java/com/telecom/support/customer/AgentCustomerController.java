package com.telecom.support.customer;

import com.telecom.support.customer.CustomerLookupService.CustomerMatch;
import com.telecom.support.customer.CustomerService.AgentCustomerProfile;
import com.telecom.support.customer.SubscriptionService.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/agent/**} customer endpoints of the inbound workspace (design 03-inbound backend §4, §5; openapi.yaml).
 * Every handler needs {@code customer.lookup} (inbound agents only) plus its own permission. The verified view (I5, I6)
 * takes the {@link VerifiedCustomer} of {@link VerifiedCustomerInterceptor} (R5), never the raw path id.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCustomerController {

    /** The {@code CustomerSearch} schema; the MSISDN goes in the body so it never appears in a URL. */
    public record CustomerSearch(@NotNull @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be an E.164 number")
                                 String msisdn) {

        /** Never print the full number (NFR-PRIV). */
        @Override
        public String toString() {
            return "CustomerSearch[msisdn=***]";
        }
    }

    private final CustomerLookupService lookups;
    private final CustomerService customers;
    private final SubscriptionService subscriptions;

    public AgentCustomerController(CustomerLookupService lookups, CustomerService customers,
                                   SubscriptionService subscriptions) {
        this.lookups = lookups;
        this.customers = customers;
        this.subscriptions = subscriptions;
    }

    /** I1 (R1). */
    @PostMapping(path = "/customer-searches", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)")
    public List<CustomerMatch> search(@Valid @RequestBody CustomerSearch body) {
        return lookups.search(body.msisdn());
    }

    /** I5 (R7): profile, statuses, suspension context and contacts; no billing address. */
    @GetMapping("/customers/{customerId}/profile")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_PROFILE_READ)")
    public AgentCustomerProfile profile(VerifiedCustomer verified) {
        return customers.profileOf(verified);
    }

    /** I6 (R7 = 02 R4 data): subscriptions and plans, primary first. */
    @GetMapping("/customers/{customerId}/subscriptions")
    @PreAuthorize("hasAuthority(T(com.telecom.support.identity.Permissions).CUSTOMER_LOOKUP)"
            + " and hasAuthority(T(com.telecom.support.identity.Permissions).PLAN_READ)")
    public List<Subscription> subscriptions(VerifiedCustomer verified) {
        return subscriptions.subscriptionsOf(verified);
    }
}
