package com.telecom.support.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup.AccountStatus;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.AccountLookup.SuspensionReason;
import com.telecom.support.customer.CustomerRepository.ProfileRow;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Own profile read and alias edit (design 02-customer backend R1, R2, R16, §7; CUS-01.1, CUS-02.1, CUS-02.3–02.5). The
 * customer id always comes from {@link CustomerScope}. Contacts change only through verification (R3, not here).
 *
 * <p>Inbound agent reads (design 03-inbound backend R7, §6.1; INB-01.3) take a {@link VerifiedCustomer}, never a raw id.
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

    public enum CustomerStatus { ACTIVE, INACTIVE }

    /** Why and since when the account is suspended. */
    public record Suspension(SuspensionReason reason, Instant since) {}

    /**
     * The {@code AgentCustomerProfile} schema (03 R7): identity, statuses, suspension context and contacts with their
     * verified flags. No billing address (data minimisation: it is a KBA answer).
     */
    public record AgentCustomerProfile(long customerId, String customerRef, String accountRef, String fullName,
                                       String aliasName, CustomerStatus customerStatus, AccountStatus accountStatus,
                                       Suspension suspension, String primaryMsisdn, String email,
                                       boolean emailVerified, String secondaryMsisdn,
                                       boolean secondaryMsisdnVerified) {}

    /**
     * Where the customer can be contacted (03 §6.1, R12): the primary MSISDN (primary subscription unless TERMINATED,
     * else null) and the email only when verified (else null).
     */
    public record Contacts(String primaryMsisdn, String verifiedEmail) {

        /** Never print contact data. */
        @Override
        public String toString() {
            return "Contacts[primaryMsisdn=" + (primaryMsisdn == null ? null : "***") + ", verifiedEmail="
                    + (verifiedEmail == null ? null : "***") + "]";
        }
    }

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

    /** I5 (03 R7): the verified customer's profile, in every account state. */
    @Transactional(readOnly = true)
    public AgentCustomerProfile profileOf(VerifiedCustomer verified) {
        long customerId = verified.customerId();
        ProfileRow row = customers.findProfile(customerId)
                .orElseThrow(() -> new IllegalStateException("No profile for verified customer " + customerId));
        CustomerAccount account = accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Verified customer " + customerId + " has no account"));
        Suspension suspension = account.status() == AccountStatus.SUSPENDED && account.suspensionReason() != null
                ? new Suspension(account.suspensionReason(), account.suspendedAt()) : null;
        return new AgentCustomerProfile(customerId, row.customerRef(), row.accountRef(), row.fullName(),
                row.aliasName(), account.customerActive() ? CustomerStatus.ACTIVE : CustomerStatus.INACTIVE,
                account.status(), suspension, row.primaryMsisdn(), row.email(), row.emailVerified(),
                row.secondaryMsisdn(), row.secondaryMsisdnVerified());
    }

    /** 03 §6.1: the verified customer's primary MSISDN and verified email (R12 case contact). */
    @Transactional(readOnly = true)
    public Contacts contactsOf(VerifiedCustomer verified) {
        return customers.findContactsByAccount(verified.accountId())
                .map(row -> new Contacts(row.primaryMsisdn(), row.verifiedEmail()))
                .orElseThrow(() -> new IllegalStateException("No account " + verified.accountId()));
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
