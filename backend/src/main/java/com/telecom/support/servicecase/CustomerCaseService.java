package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.CustomerService;
import com.telecom.support.customer.CustomerService.Profile;
import com.telecom.support.customer.SubscriptionService;
import com.telecom.support.servicecase.CaseCreationService.CaseSpec;
import com.telecom.support.servicecase.CaseCreationService.Classification;
import com.telecom.support.servicecase.CaseCreationService.CreatedCase;
import com.telecom.support.servicecase.CaseRepository.CaseSummaryRow;
import com.telecom.support.servicecase.CaseRepository.Order;
import com.telecom.support.servicecase.SlaPolicyRepository.SlaPolicyRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in customer's own service requests: list (E11, R9) and create (E12, R10) (design 02-customer backend R1,
 * R9, R10, R16, §7; CUS-05, CUS-06, BR-01/02/04/05/06/07). The account, channel and contact number always come from the
 * principal ({@link CustomerScope}, {@link AccountLookup}), never from the request. Attachments (CUS-06.3) are deferred
 * (OQ 4).
 */
@Service
public class CustomerCaseService {

    static final String CHANNEL_CUSTOMER_PORTAL = "CUSTOMER_PORTAL";

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum CaseType { REQUEST, COMPLAINT }

    public enum PreferredContact { PHONE, SMS, EMAIL }

    /** Customer-facing case status (§14 #8); internal statuses are mapped, never shown. */
    public enum PublicCaseStatus {
        SUBMITTED, IN_PROGRESS, AWAITING_YOUR_REPLY, RESOLVED, CLOSED;

        static PublicCaseStatus of(String internal) {
            return switch (internal) {
                case "CREATED" -> SUBMITTED;
                case "ASSIGNED", "IN_PROGRESS", "PENDING_EXTERNAL", "REOPENED" -> IN_PROGRESS;
                case "PENDING_CUSTOMER" -> AWAITING_YOUR_REPLY;
                case "RESOLVED" -> RESOLVED;
                case "CLOSED" -> CLOSED;
                default -> throw new IllegalStateException("Unknown case status " + internal);
            };
        }
    }

    /** Whitelisted sort keys of E11 (R9). */
    public enum CaseSort {
        CREATED_AT_DESC("createdAt,desc", Order.CREATED_AT_DESC),
        CREATED_AT_ASC("createdAt,asc", Order.CREATED_AT_ASC);

        private final String param;
        private final Order order;

        CaseSort(String param, Order order) {
            this.param = param;
            this.order = order;
        }

        /** The sort for a {@code sort} query value; null means the default; anything else → 400. */
        public static CaseSort fromParam(String value) {
            if (value == null) {
                return CREATED_AT_DESC;
            }
            for (CaseSort sort : values()) {
                if (sort.param.equals(value)) {
                    return sort;
                }
            }
            throw new BadRequestException("Unsupported case sort " + value,
                    "Sort must be createdAt,desc or createdAt,asc.");
        }
    }

    /** The {@code CaseSummary} schema. */
    public record CaseSummary(String reference, String categoryName, String subcategoryName, String subject,
                              Instant createdAt, Severity severity, PublicCaseStatus status) {}

    /** The {@code CasePage} schema (00-overview §3). */
    public record CasePage(List<CaseSummary> items, int page, int size, long totalItems, int totalPages) {}

    /** What the customer submits (the {@code CaseCreateRequest} schema, already bean-validated). */
    public record CaseDraft(String categoryCode, String subcategoryCode, Long subscriptionId, String subject,
                            String description, PreferredContact preferredContact) {}

    /** The {@code CaseCreated} schema. */
    public record CaseCreated(String reference, CaseType caseType, PublicCaseStatus status, Severity severity,
                              Instant createdAt, String contactMsisdn, String accountRef) {}

    private final CaseRepository cases;
    private final CategoryRepository categories;
    private final CaseCreationService creation;
    private final AccountLookup accounts;
    private final CustomerService customers;
    private final SubscriptionService subscriptions;
    private final CustomerScope scope;
    private final ActorProvider actors;
    private final Clock clock;
    private final ZoneId zone;

    CustomerCaseService(CaseRepository cases, CategoryRepository categories, CaseCreationService creation,
                        AccountLookup accounts, CustomerService customers, SubscriptionService subscriptions,
                        CustomerScope scope, ActorProvider actors, Clock clock,
                        @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.cases = cases;
        this.categories = categories;
        this.creation = creation;
        this.accounts = accounts;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.scope = scope;
        this.actors = actors;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * E11 (R9): the account's cases created between {@code from} and {@code to} (inclusive Asia/Kolkata dates; default
     * today minus 3 months .. today; only {@code to} given → the 3 months before it), newest first unless sorted
     * otherwise. An exact {@code reference} is searched
     * inside that window only (OQ 6). {@code from > to} or a range over 366 days → 400. Reads are allowed in every
     * account state (R16).
     */
    @Transactional(readOnly = true)
    public CasePage listMyCases(LocalDate from, LocalDate to, String reference, int page, int size, CaseSort sort) {
        CaseWindow window = CaseWindow.resolve(from, to, clock, zone);
        long accountId = myAccount().accountId();
        Instant fromInclusive = window.fromInclusive();
        Instant toExclusive = window.toExclusive();

        long total = cases.count(accountId, fromInclusive, toExclusive, reference);
        List<CaseSummary> items = total == 0 ? List.of()
                : cases.findPage(accountId, fromInclusive, toExclusive, reference, sort.order, size,
                                (long) page * size)
                        .stream()
                        .map(CustomerCaseService::toSummary)
                        .toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new CasePage(items, page, size, total, totalPages);
    }

    /**
     * E12 (R10): files a case on the signed-in customer's account through the portal. Another account's
     * {@code subscriptionId} → 404. INACTIVE customer or CLOSED account (R16), an unknown, inactive or internal
     * category, a missing or foreign subcategory, EMAIL contact without a verified email, or no SLA row for the leaf
     * or its parent (BR-07) → 422. Writes the case, one PUBLIC CREATED activity and {@code CASE_CREATED}.
     */
    @Transactional
    public CaseCreated createMyCase(CaseDraft draft) {
        CustomerAccount account = myAccount();
        if (draft.subscriptionId() != null && subscriptions.mySubscriptions().stream()
                .noneMatch(s -> s.id() == draft.subscriptionId())) {
            throw new NotFoundException(
                    "Subscription " + draft.subscriptionId() + " not found in account " + account.accountId());
        }
        account.requireMutable();

        Classification classification = creation.classify(categories.findCustomerTree(), draft.categoryCode(),
                draft.subcategoryCode());
        String severity = classification.derivedSeverity();
        SlaPolicyRow sla = creation.slaFor(classification, severity);
        Profile profile = customers.myProfile();
        if (draft.preferredContact() == PreferredContact.EMAIL && !profile.emailVerified()) {
            throw new BusinessRuleException("EMAIL contact without a verified email for account " + account.accountId(),
                    "Add and verify an email address to be contacted by email.");
        }
        if (profile.primaryMsisdn() == null) {
            throw new BusinessRuleException("Account " + account.accountId() + " has no primary number",
                    "This account has no primary mobile number, so a request cannot be raised.");
        }

        Actor actor = actors.currentActor().orElseThrow();
        CreatedCase created = creation.create(new CaseSpec(account.accountId(), draft.subscriptionId(), classification,
                severity, sla, CHANNEL_CUSTOMER_PORTAL, draft.subject(), draft.description(),
                draft.preferredContact().name(), profile.primaryMsisdn(), actor, null, Map.of()));

        return new CaseCreated(created.reference(), CaseType.valueOf(created.caseType()), PublicCaseStatus.SUBMITTED,
                Severity.valueOf(created.severity()), created.createdAt(), profile.primaryMsisdn(),
                account.accountRef());
    }

    private CustomerAccount myAccount() {
        long customerId = scope.requireCustomerId();
        return accounts.accountFor(customerId)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " has no account"));
    }

    private static CaseSummary toSummary(CaseSummaryRow row) {
        return new CaseSummary(row.reference(), row.categoryName(), row.subcategoryName(), row.subject(),
                row.createdAt(), Severity.valueOf(row.severity()), PublicCaseStatus.of(row.status()));
    }
}
