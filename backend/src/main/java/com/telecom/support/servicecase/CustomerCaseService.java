package com.telecom.support.servicecase;

import com.telecom.support.audit.AuditService;
import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.BusinessRuleException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.common.reference.ReferenceGenerator;
import com.telecom.support.common.security.ActorProvider;
import com.telecom.support.common.security.ActorProvider.Actor;
import com.telecom.support.common.security.CustomerScope;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CustomerAccount;
import com.telecom.support.customer.CustomerService;
import com.telecom.support.customer.CustomerService.Profile;
import com.telecom.support.customer.SubscriptionService;
import com.telecom.support.servicecase.CaseRepository.CaseSummaryRow;
import com.telecom.support.servicecase.CaseRepository.NewCase;
import com.telecom.support.servicecase.CaseRepository.Order;
import com.telecom.support.servicecase.CategoryRepository.CategoryRow;
import com.telecom.support.servicecase.SlaPolicyRepository.SlaPolicyRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    static final String CASE_CREATED = "CASE_CREATED";
    static final String ENTITY_CASE = "CASE";
    static final String CHANNEL_CUSTOMER_PORTAL = "CUSTOMER_PORTAL";
    static final String STATUS_CREATED = "CREATED";

    /** Default list window (00-overview §3, BR-05/06) and the largest allowed one. */
    static final int DEFAULT_WINDOW_MONTHS = 3;
    static final long MAX_WINDOW_DAYS = 366;

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
    private final CaseActivityRepository activity;
    private final CategoryRepository categories;
    private final SlaPolicyRepository slaPolicies;
    private final AccountLookup accounts;
    private final CustomerService customers;
    private final SubscriptionService subscriptions;
    private final CustomerScope scope;
    private final ReferenceGenerator references;
    private final AuditService audit;
    private final ActorProvider actors;
    private final Clock clock;
    private final ZoneId zone;

    CustomerCaseService(CaseRepository cases, CaseActivityRepository activity, CategoryRepository categories,
                        SlaPolicyRepository slaPolicies, AccountLookup accounts, CustomerService customers,
                        SubscriptionService subscriptions, CustomerScope scope, ReferenceGenerator references,
                        AuditService audit, ActorProvider actors, Clock clock,
                        @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.cases = cases;
        this.activity = activity;
        this.categories = categories;
        this.slaPolicies = slaPolicies;
        this.accounts = accounts;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.scope = scope;
        this.references = references;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * E11 (R9): the account's cases created between {@code from} and {@code to} (inclusive Asia/Kolkata dates; default
     * today minus 3 months .. today), newest first unless sorted otherwise. An exact {@code reference} is searched
     * inside that window only (OQ 6). {@code from > to} or a range over 366 days → 400. Reads are allowed in every
     * account state (R16).
     */
    @Transactional(readOnly = true)
    public CasePage listMyCases(LocalDate from, LocalDate to, String reference, int page, int size, CaseSort sort) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : today.minusMonths(DEFAULT_WINDOW_MONTHS);
        if (start.isAfter(end)) {
            throw new BadRequestException("Case list from " + start + " after to " + end,
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_WINDOW_DAYS) {
            throw new BadRequestException("Case list range " + start + ".." + end + " over " + MAX_WINDOW_DAYS + " days",
                    "The date range can be at most 366 days.");
        }
        long accountId = myAccount().accountId();
        Instant fromInclusive = start.atStartOfDay(zone).toInstant();
        Instant toExclusive = end.plusDays(1).atStartOfDay(zone).toInstant();

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

        Classification classification = classify(draft.categoryCode(), draft.subcategoryCode());
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
        Instant now = clock.instant();
        CategoryRow leaf = classification.leaf();
        SlaPolicyRow sla = classification.sla();
        String reference = references.next(ReferenceGenerator.SERVICE_REQUEST);
        long caseId = cases.insert(new NewCase(reference, account.accountId(), draft.subscriptionId(), leaf.id(),
                leaf.caseType(), CHANNEL_CUSTOMER_PORTAL, draft.subject(), draft.description(),
                classification.severity(), STATUS_CREATED, draft.preferredContact().name(), profile.primaryMsisdn(),
                actor.id(), now.plus(Duration.ofMinutes(sla.responseMinutes())),
                now.plus(Duration.ofMinutes(sla.resolutionMinutes())), now));
        activity.insertCreated(caseId, actor.id(), now);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("category", leaf.code());
        details.put("caseType", leaf.caseType());
        details.put("severity", classification.severity());
        details.put("channel", CHANNEL_CUSTOMER_PORTAL);
        audit.record(CASE_CREATED, actor.id(), actor.username(), ENTITY_CASE, reference, details);

        return new CaseCreated(reference, CaseType.valueOf(leaf.caseType()), PublicCaseStatus.SUBMITTED,
                Severity.valueOf(classification.severity()), now, profile.primaryMsisdn(), account.accountRef());
    }

    /** The stored leaf, its resolved severity and the SLA row that applies (R10, BR-07). */
    private record Classification(CategoryRow leaf, String severity, SlaPolicyRow sla) {}

    /**
     * R10: {@code categoryCode} must be an active CUSTOMER top level; {@code subcategoryCode} is required exactly when
     * it has active CUSTOMER children and must be one of them. Severity = leaf default, else the parent's; SLA = (leaf,
     * severity), else (parent, severity); none → 422.
     */
    private Classification classify(String categoryCode, String subcategoryCode) {
        List<CategoryRow> tree = categories.findCustomerTree();
        CategoryRow top = tree.stream()
                .filter(c -> c.parentId() == null && c.code().equals(categoryCode))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Category " + categoryCode + " is not selectable",
                        "Choose one of the listed categories."));
        List<CategoryRow> children = tree.stream().filter(c -> Objects.equals(c.parentId(), top.id())).toList();

        CategoryRow leaf;
        CategoryRow parent;
        if (children.isEmpty()) {
            if (subcategoryCode != null) {
                throw new BusinessRuleException("Category " + categoryCode + " has no subcategory " + subcategoryCode,
                        "This category has no subcategories.");
            }
            leaf = top;
            parent = null;
        } else {
            leaf = children.stream()
                    .filter(c -> c.code().equals(subcategoryCode))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException(
                            "Subcategory " + subcategoryCode + " is not selectable under " + categoryCode,
                            "Choose one of the listed subcategories."));
            parent = top;
        }

        String severity = leaf.defaultSeverity() != null ? leaf.defaultSeverity()
                : parent != null ? parent.defaultSeverity() : null;
        SlaPolicyRow sla = severity == null ? null : slaPolicies.findActive(leaf.id(), severity)
                .or(() -> parent == null ? Optional.empty() : slaPolicies.findActive(parent.id(), severity))
                .orElse(null);
        if (sla == null) {
            throw new BusinessRuleException("No SLA policy for category " + leaf.code() + " severity " + severity,
                    "Requests of this type cannot be raised right now. Please choose another category or try later.");
        }
        return new Classification(leaf, severity, sla);
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
