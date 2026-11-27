package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.CustomerService;
import com.telecom.support.customer.CustomerService.Contacts;
import com.telecom.support.customer.SubscriptionService;
import com.telecom.support.customer.SubscriptionService.LiveSubscription;
import com.telecom.support.customer.SubscriptionService.Subscription;
import com.telecom.support.customer.SubscriptionService.SubscriptionStatus;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.Permissions;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseChannel;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.CaseCreationService.CaseSpec;
import com.telecom.support.servicecase.CaseCreationService.Classification;
import com.telecom.support.servicecase.CaseCreationService.CreatedCase;
import com.telecom.support.servicecase.CategoryRepository.AgentCategoryRow;
import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import com.telecom.support.servicecase.CustomerCaseService.CaseType;
import com.telecom.support.servicecase.CustomerCaseService.PreferredContact;
import com.telecom.support.servicecase.CustomerCaseService.Severity;
import com.telecom.support.servicecase.SlaPolicyRepository.SlaPolicyRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Case categories for agents (I11, R11) and a case raised on behalf of the verified customer (I12, R12) (design
 * 03-inbound backend; INB-03.1..03.5, BR-01/02/04/07, §14 #4). I12 is 02 R10 through {@link CaseCreationService} plus
 * the agent rules: INTERNAL categories need {@code case.category.internal}, a severity other than the derived one needs
 * {@code case.severity.override} and a reason, the contact is the chosen (or primary) live subscription's MSISDN, an
 * agent note becomes one INTERNAL activity, and the channel is {@code INBOUND_AGENT} with the agent as creator. The
 * account always comes from the {@link VerifiedCustomer}. Service-level permission denials are
 * {@link AccessDeniedException}s, so the error handler records them like {@code @PreAuthorize} ones (reason
 * {@code permission}).
 */
@Service
public class AgentCaseService {

    static final String CHANNEL_INBOUND_AGENT = "INBOUND_AGENT";
    static final String AUDIENCE_INTERNAL = "INTERNAL";

    /** A subcategory in the {@code AgentCategory} schema. */
    public record AgentSubcategory(String code, String name, Severity defaultSeverity) {}

    /** The {@code AgentCategory} schema; {@code subcategories} empty = the top level itself is the leaf. */
    public record AgentCategory(String code, String name, String audience, Severity defaultSeverity,
                                List<AgentSubcategory> subcategories) {}

    /** What the agent submits (the {@code AgentCaseCreateRequest} schema, already bean-validated). */
    public record AgentCaseDraft(String categoryCode, String subcategoryCode, Long subscriptionId, String subject,
                                 String customerStatement, String agentNotes, Severity severity,
                                 String severityReason, PreferredContact preferredContact) {}

    /** The {@code AgentCaseCreated} schema. */
    public record AgentCaseCreated(String reference, CaseType caseType, CaseStatus status, Severity severity,
                                   Instant createdAt, String contactMsisdn, String accountRef, CaseChannel channel) {}

    private final CategoryRepository categories;
    private final CaseCreationService creation;
    private final AccountLookup accounts;
    private final CustomerService customers;
    private final SubscriptionService subscriptions;
    private final ActorProvider actors;

    AgentCaseService(CategoryRepository categories, CaseCreationService creation, AccountLookup accounts,
                     CustomerService customers, SubscriptionService subscriptions, ActorProvider actors) {
        this.categories = categories;
        this.creation = creation;
        this.accounts = accounts;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.actors = actors;
    }

    /**
     * I11 (R11): active CUSTOMER top levels with their active children, plus the INTERNAL ones for holders of
     * {@code case.category.internal}, in display order.
     */
    @Transactional(readOnly = true)
    public List<AgentCategory> categories() {
        Map<Long, AgentCategoryRow> topLevels = new LinkedHashMap<>();
        Map<Long, List<AgentSubcategory>> children = new LinkedHashMap<>();
        for (AgentCategoryRow row : categories.findAgentTree(has(Permissions.CASE_CATEGORY_INTERNAL))) {
            CategoryRow c = row.category();
            if (c.parentId() == null) {
                topLevels.put(c.id(), row);
                children.put(c.id(), new ArrayList<>());
            } else {
                children.get(c.parentId()).add(new AgentSubcategory(c.code(), c.name(), severity(c.defaultSeverity())));
            }
        }
        return topLevels.values().stream()
                .map(top -> new AgentCategory(top.category().code(), top.category().name(), top.audience(),
                        severity(top.category().defaultSeverity()), List.copyOf(children.get(top.category().id()))))
                .toList();
    }

    /**
     * I12 (R12). Another account's or a TERMINATED {@code subscriptionId} → 404. INACTIVE customer or CLOSED account,
     * an unknown or inactive category or subcategory, EMAIL contact without a verified email, no live primary when no
     * subscription is given, or no SLA row for the final severity → 422. INTERNAL category without
     * {@code case.category.internal}, or a severity other than the derived one without {@code case.severity.override}
     * → 403; an override without a 5–200 character reason → 400.
     */
    @Transactional
    public AgentCaseCreated createOnBehalf(VerifiedCustomer verified, AgentCaseDraft draft) {
        CustomerAccount account = accounts.accountFor(verified.customerId())
                .orElseThrow(() -> new IllegalStateException("Verified customer " + verified.customerId()
                        + " has no account"));
        LiveSubscription chosen = draft.subscriptionId() == null ? null
                : subscriptions.liveSubscription(verified, draft.subscriptionId());
        account.requireMutable();

        Map<Long, String> audienceById = new LinkedHashMap<>();
        List<CategoryRow> tree = new ArrayList<>();
        for (AgentCategoryRow row : categories.findAgentTree(true)) {
            tree.add(row.category());
            audienceById.put(row.category().id(), row.audience());
        }
        Classification classification = creation.classify(tree, draft.categoryCode(), draft.subcategoryCode());
        boolean internalCategory = AUDIENCE_INTERNAL.equals(audienceById.get(classification.top().id()));
        if (internalCategory && !has(Permissions.CASE_CATEGORY_INTERNAL)) {
            throw new AccessDeniedException("INTERNAL category " + draft.categoryCode() + " needs "
                    + Permissions.CASE_CATEGORY_INTERNAL);
        }

        String derived = classification.derivedSeverity();
        String severity = draft.severity() != null ? draft.severity().name() : derived;
        boolean overridden = !Objects.equals(severity, derived);
        String severityReason = null;
        if (overridden) {
            if (!has(Permissions.CASE_SEVERITY_OVERRIDE)) {
                throw new AccessDeniedException("Severity " + severity + " instead of " + derived + " needs "
                        + Permissions.CASE_SEVERITY_OVERRIDE);
            }
            severityReason = draft.severityReason() == null ? null : draft.severityReason().strip();
            if (severityReason == null || severityReason.length() < 5 || severityReason.length() > 200) {
                throw new BadRequestException("Severity override without a valid reason",
                        "Give a reason of 5 to 200 characters for changing the severity.");
            }
        }
        SlaPolicyRow sla = creation.slaFor(classification, severity);

        Contacts contacts = customers.contactsOf(verified);
        if (draft.preferredContact() == PreferredContact.EMAIL && contacts.verifiedEmail() == null) {
            throw new BusinessRuleException("EMAIL contact without a verified email for account "
                    + verified.accountId(), "The customer has no verified email address, so choose phone or SMS.");
        }
        long subscriptionId;
        String contactMsisdn;
        if (chosen != null) {
            subscriptionId = chosen.id();
            contactMsisdn = chosen.msisdn();
        } else {
            Subscription primary = subscriptions.subscriptionsOf(verified).stream()
                    .filter(s -> s.primary() && s.status() != SubscriptionStatus.TERMINATED)
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("Account " + verified.accountId()
                            + " has no live primary subscription",
                            "This account has no primary mobile number, so choose the number the request is about."));
            subscriptionId = primary.id();
            contactMsisdn = primary.msisdn();
        }

        String note = draft.agentNotes() == null || draft.agentNotes().isBlank() ? null : draft.agentNotes();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("customerRef", customers.profileOf(verified).customerRef());
        details.put("checkId", verified.checkId());
        details.put("internalCategory", internalCategory);
        details.put("severityDerived", derived);
        if (overridden) {
            details.put("severityReason", severityReason);
        }
        details.put("agentNote", note != null);

        Actor actor = actors.currentActor().orElseThrow();
        CreatedCase created = creation.create(new CaseSpec(verified.accountId(), subscriptionId, classification,
                severity, sla, CHANNEL_INBOUND_AGENT, draft.subject(), draft.customerStatement(),
                draft.preferredContact().name(), contactMsisdn, actor, note, details));
        return new AgentCaseCreated(created.reference(), CaseType.valueOf(created.caseType()), CaseStatus.CREATED,
                Severity.valueOf(created.severity()), created.createdAt(), contactMsisdn, account.accountRef(),
                CaseChannel.INBOUND_AGENT);
    }

    private static boolean has(String permission) {
        return CurrentUser.require().has(permission);
    }

    private static Severity severity(String value) {
        return value == null ? null : Severity.valueOf(value);
    }
}
