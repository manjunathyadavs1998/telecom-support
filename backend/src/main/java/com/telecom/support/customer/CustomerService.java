package com.telecom.support.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.CustomerRepository.ProfileRow;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Own profile read and alias edit (design 02-customer backend R1, R2, R16, §7; CUS-01.1, CUS-02.1, CUS-02.3–02.5). The
 * customer id always comes from {@link CustomerScope}. Contacts change only through verification (R3, not here).
 */
@Service
public class CustomerService {

    static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    static final String ENTITY_CUSTOMER = "CUSTOMER";

    /** Read-only billing address (CUS-02.3); {@code line2} is optional. */
    public record BillingAddress(String line1, String line2, String city, String state, String postalCode,
                                 String country) {}

    /** The {@code Profile} schema of openapi.yaml. */
    public record Profile(String customerRef, String accountRef, String fullName, String aliasName,
                          String primaryMsisdn, String secondaryMsisdn, boolean secondaryMsisdnVerified, String email,
                          boolean emailVerified, BillingAddress billingAddress, int version) {}

    private final CustomerRepository customers;
    private final AccountLookup accounts;
    private final CustomerScope scope;
    private final AuditService audit;
    private final ActorProvider actors;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CustomerService(CustomerRepository customers, AccountLookup accounts, CustomerScope scope, AuditService audit,
                    ActorProvider actors, ObjectMapper objectMapper, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
        this.actors = actors;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** E1: the signed-in customer's profile. Reads are allowed in every account state (R16). */
    @Transactional(readOnly = true)
    public Profile myProfile() {
        return toProfile(load(scope.requireCustomerId()));
    }

    /**
     * E2: sets ({@code aliasName} non-null) or clears ({@code null}) the alias when {@code version} is current.
     * INACTIVE customer or CLOSED account → 422 (R16); stale version → 409 (R2). Writes {@code PROFILE_UPDATED}.
     */
    @Transactional
    public Profile updateMyAlias(String aliasName, int version) {
        long customerId = scope.requireCustomerId();
        accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"))
                .requireMutable();
        ProfileRow before = load(customerId);
        // the guarded UPDATE also catches a change between this read and the write, so 'before' is the replaced value
        if (before.version() != version || customers.updateAlias(customerId, aliasName, version, clock.instant()) == 0) {
            throw new ConflictException("Stale profile version " + version + " for customer " + customerId);
        }
        Map<String, Object> change = new HashMap<>();
        change.put("old", before.aliasName());
        change.put("new", aliasName);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("aliasName", change);
        Actor actor = actors.currentActor().orElseThrow();
        audit.record(PROFILE_UPDATED, actor.id(), actor.username(), ENTITY_CUSTOMER, String.valueOf(customerId),
                details);
        return toProfile(load(customerId));
    }

    private ProfileRow load(long customerId) {
        return customers.findProfile(customerId)
                .orElseThrow(() -> new IllegalStateException("No profile for linked customer " + customerId));
    }

    private Profile toProfile(ProfileRow row) {
        return new Profile(row.customerRef(), row.accountRef(), row.fullName(), row.aliasName(), row.primaryMsisdn(),
                row.secondaryMsisdn(), row.secondaryMsisdnVerified(), row.email(), row.emailVerified(),
                billingAddress(row.billingAddressJson()), row.version());
    }

    private BillingAddress billingAddress(String json) {
        try {
            return objectMapper.readValue(json, BillingAddress.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable billing_address JSON", e);
        }
    }
}
