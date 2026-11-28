package com.telecom.support.servicecase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.customer.AccountLookup;
import com.telecom.support.customer.AccountLookup.CaseCustomer;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.Permissions;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.identity.UserDirectory.QueueAgent;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseActivity;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseStatus;
import com.telecom.support.servicecase.CaseRepository.OutboundCaseRow;
import com.telecom.support.servicecase.CategoryRepository.AgentCategoryRow;
import com.telecom.support.servicecase.CustomerCaseService.PublicCaseStatus;
import com.telecom.support.servicecase.CustomerCaseService.Severity;
import com.telecom.support.servicecase.QueueRepository.Filter;
import com.telecom.support.servicecase.QueueRepository.QueueRow;
import com.telecom.support.servicecase.QueueRepository.SummaryRow;
import com.telecom.support.servicecase.QueueSearchValidator.Range;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound queue reads O1–O4 (design 04-outbound backend R1–R3, R13; OUT-01, OUT-02, OUT-04.4). Scope S comes from the
 * principal only. Names and customer context are added with one batch call each per page.
 */
@Service
public class OutboundCaseQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;

    /** The {@code DateWindow} schema: inclusive display-zone days. */
    public record DateWindow(LocalDate from, LocalDate to) {}

    /** The {@code QueueSummary} schema. */
    public record QueueSummary(DateWindow window, @JsonProperty("new") long newCount, long open, long inProgress, long pending,
                               long resolved, long closed, long critical, long overdue) {}

    /** The {@code QueueSearch} schema (POST body, OUT-02). */
    public record QueueSearch(LocalDate createdFrom, LocalDate createdTo, LocalDate updatedFrom, LocalDate updatedTo,
                              List<CaseStatus> status, List<Severity> severity, String categoryCode, String assignee,
                              Long assigneeUserId, String customerMsisdn, String reference, Boolean overdue,
                              Integer page, Integer size, String sort) {}

    /** The {@code AppliedFilters} schema (OUT-02.2). */
    public record AppliedFilters(DateWindow created, DateWindow updated, String sort) {}

    /** The {@code CaseCustomer} schema: contact MSISDN masked to its last 4 digits (NFR-PRIV). */
    public record QueueCustomer(String customerRef, String customerName, String accountRef, String maskedMsisdn) {}

    /** The {@code OutboundCaseSummary} schema. */
    public record OutboundCaseSummary(String reference, QueueCustomer customer, String categoryName,
                                      String subcategoryName, Severity severity, CaseStatus status,
                                      PublicCaseStatus publicStatus, Long assignedUserId, String ownerName,
                                      Instant createdAt, Instant updatedAt, Instant slaDueAt, boolean overdue,
                                      boolean bookmarked) {}

    /** The {@code OutboundCasePage} schema. */
    public record OutboundCasePage(List<OutboundCaseSummary> items, int page, int size, long totalItems,
                                   int totalPages, AppliedFilters filters) {}

    /** The {@code QueueCategory} schema. */
    public record QueueCategory(String code, String name, String audience, List<QueueSubcategory> children) {}

    public record QueueSubcategory(String code, String name) {}

    /** The {@code QueueAgent} schema. */
    public record QueueAgentView(long userId, String displayName, Long teamId, String teamName) {}

    /** The {@code AllowedActions} schema (R4): what the actor may do now; every action is re-checked on the server. */
    public record AllowedActions(boolean claim, boolean reassign, List<CaseStatus> transitions, boolean internalNote,
                                 boolean publicComment) {}

    /** The {@code OutboundCaseDetail} schema (R4). {@code contactMsisdn} is the full number for the assignee only. */
    public record OutboundCaseDetail(String reference, int version, QueueCustomer customer, String accountStatus,
                                     String contactMsisdn, String preferredContact, String categoryName,
                                     String subcategoryName, String caseType, String channel, String creatorName,
                                     String subject, String description, Severity severity, CaseStatus status,
                                     PublicCaseStatus publicStatus, Long assignedUserId, String assigneeName,
                                     String teamName, Instant createdAt, Instant updatedAt, Instant responseDueAt,
                                     Instant slaDueAt, boolean overdue, Instant resolvedAt, String resolvedByName,
                                     String resolutionRemarks, Instant closedAt, String closureType, int reopenCount,
                                     boolean bookmarked, List<CaseActivity> activity, AllowedActions allowedActions) {}

    private final QueueRepository queue;
    private final CaseRepository cases;
    private final CaseActivityRepository activity;
    private final CaseTimelineAssembler timelines;
    private final CaseLifecycle lifecycle;
    private final CategoryRepository categories;
    private final UserDirectory users;
    private final AccountLookup accounts;
    private final Clock clock;
    private final ZoneId zone;

    OutboundCaseQueryService(QueueRepository queue, CaseRepository cases, CaseActivityRepository activity,
                             CaseTimelineAssembler timelines, CaseLifecycle lifecycle, CategoryRepository categories,
                             UserDirectory users, AccountLookup accounts, Clock clock,
                             @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.queue = queue;
        this.cases = cases;
        this.activity = activity;
        this.timelines = timelines;
        this.lifecycle = lifecycle;
        this.categories = categories;
        this.users = users;
        this.accounts = accounts;
        this.clock = clock;
        this.zone = zone;
    }

    /** O1 (R1): counts over S and the created window (default the last 3 months). */
    @Transactional(readOnly = true)
    public QueueSummary summary(LocalDate from, LocalDate to) {
        OutboundScope scope = OutboundScope.of(CurrentUser.require());
        CaseWindow window = CaseWindow.resolve(from, to, clock, zone);
        SummaryRow row = queue.summary(scope, window.fromInclusive(), window.toExclusive(), clock.instant());
        return new QueueSummary(dateWindow(window.fromInclusive(), window.toExclusive()), row.newCount(), row.open(),
                row.inProgress(), row.pending(), row.resolved(), row.closed(), row.critical(), row.overdue());
    }

    /** O2 (R2, R3): the filtered, sorted page over S, with the effective filters echoed. */
    @Transactional(readOnly = true)
    public OutboundCasePage search(QueueSearch search) {
        QueueSearch s = search == null
                ? new QueueSearch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : search;
        OutboundScope scope = OutboundScope.of(CurrentUser.require());
        QueueSearchValidator.page(s.page(), s.size());
        QueueRepository.Sort sort = QueueSearchValidator.sort(s.sort());
        String assignee = s.assignee() == null ? "ANY" : s.assignee();
        QueueSearchValidator.assignee(assignee, s.assigneeUserId());
        QueueSearchValidator.msisdn(s.customerMsisdn());

        Range created = QueueSearchValidator.range("created", s.createdFrom(), s.createdTo(), clock, zone);
        Range updated = QueueSearchValidator.range("updated", s.updatedFrom(), s.updatedTo(), clock, zone);
        if (created == null && updated == null) {
            created = QueueSearchValidator.defaultCreated(clock, zone);
        }

        List<Long> categoryIds = blank(s.categoryCode()) ? null : queue.categoryIds(s.categoryCode().trim());
        String msisdn = blank(s.customerMsisdn()) ? null : s.customerMsisdn();
        List<Long> msisdnAccounts = msisdn == null ? List.of() : accounts.accountIdsByLiveMsisdn(msisdn);
        Instant now = clock.instant();
        Filter filter = new Filter(scope,
                created == null ? null : start(created.from()), created == null ? null : end(created.to()),
                updated == null ? null : start(updated.from()), updated == null ? null : end(updated.to()),
                s.status() == null ? List.of() : s.status().stream().filter(Objects::nonNull).map(Enum::name).distinct().toList(),
                s.severity() == null ? List.of() : s.severity().stream().filter(Objects::nonNull).map(Enum::name).distinct().toList(),
                categoryIds, assignee, s.assigneeUserId(), msisdn, msisdnAccounts,
                blank(s.reference()) ? null : s.reference().trim().toUpperCase(java.util.Locale.ROOT),
                Boolean.TRUE.equals(s.overdue()), now);

        int page = s.page() == null ? 0 : s.page();
        int size = s.size() == null ? DEFAULT_PAGE_SIZE : s.size();
        long total = queue.count(filter);
        List<QueueRow> rows = total == 0 ? List.of() : queue.page(filter, sort, page, size);
        AppliedFilters applied = new AppliedFilters(
                created == null ? null : new DateWindow(created.from(), created.to()),
                updated == null ? null : new DateWindow(updated.from(), updated.to()), sort.param);
        return new OutboundCasePage(summaries(rows, now), page, size, total,
                (int) ((total + size - 1) / size), applied);
    }

    /** O3 (R13): all active categories, both audiences, top levels with their children. */
    @Transactional(readOnly = true)
    public List<QueueCategory> categories() {
        Map<Long, QueueCategory> tops = new LinkedHashMap<>();
        Map<Long, List<QueueSubcategory>> children = new LinkedHashMap<>();
        for (AgentCategoryRow row : categories.findAgentTree(true)) {
            if (row.category().parentId() == null) {
                List<QueueSubcategory> list = new ArrayList<>();
                children.put(row.category().id(), list);
                tops.put(row.category().id(),
                        new QueueCategory(row.category().code(), row.category().name(), row.audience(), list));
            } else {
                List<QueueSubcategory> list = children.get(row.category().parentId());
                if (list != null) {
                    list.add(new QueueSubcategory(row.category().code(), row.category().name()));
                }
            }
        }
        return tops.values().stream()
                .map(top -> new QueueCategory(top.code(), top.name(), top.audience(), List.copyOf(top.children())))
                .toList();
    }

    /** O4 (R13): the actor's team's queue agents, all of them for {@code case.assign.any}. */
    @Transactional(readOnly = true)
    public List<QueueAgentView> agents() {
        OutboundScope scope = OutboundScope.of(CurrentUser.require());
        if (!scope.all() && scope.teamId() == null) {
            return List.of();
        }
        List<QueueAgent> agents = users.queueAgents(scope.all() ? null : scope.teamId());
        return agents.stream().map(a -> new QueueAgentView(a.userId(), a.displayName(), a.teamId(), a.teamName()))
                .toList();
    }

    /** O5 (R4): the case in S (else 404) with its timeline and the actor's allowed actions. */
    @Transactional(readOnly = true)
    public OutboundCaseDetail detail(String reference) {
        CurrentUser actor = CurrentUser.require();
        OutboundScope scope = OutboundScope.of(actor);
        OutboundCaseRow row = cases.findOutbound(reference, actor.id())
                .filter(r -> scope.covers(r.assignedUserId(), r.assignedTeamId()))
                .orElseThrow(() -> new NotFoundException("Outbound case " + reference + " not in scope"));
        return toDetail(row, actor);
    }

    /** R4 view of a case row the actor may see (also the response of every O6–O8 command, XC-07). */
    OutboundCaseDetail toDetail(OutboundCaseRow row, CurrentUser actor) {
        List<CaseActivity> timeline = timelines.assemble(
                activity.findTimeline(row.id(), actor.has(Permissions.CASE_NOTE_INTERNAL)));
        List<Long> userIds = new ArrayList<>(List.of(row.createdByUserId()));
        if (row.assignedUserId() != null) {
            userIds.add(row.assignedUserId());
        }
        if (row.resolvedByUserId() != null) {
            userIds.add(row.resolvedByUserId());
        }
        Map<Long, String> userNames = users.displayNames(userIds);
        Map<Long, String> teamNames = row.assignedTeamId() == null ? Map.of()
                : users.teamNames(List.of(row.assignedTeamId()));
        CaseCustomer c = accounts.caseContexts(List.of(row.accountId())).get(row.accountId());
        QueueCustomer customer = new QueueCustomer(c == null ? null : c.customerRef(),
                c == null ? null : c.customerName(), c == null ? null : c.accountRef(), mask(row.contactMsisdn()));
        CaseStatus status = CaseStatus.valueOf(row.status());
        boolean owner = row.assignedUserId() != null && row.assignedUserId() == actor.id();
        return new OutboundCaseDetail(row.reference(), row.version(), customer,
                c == null ? null : c.accountStatus().name(),
                owner ? row.contactMsisdn() : null, row.preferredContact(), row.categoryName(), row.subcategoryName(),
                row.caseType(), row.channel(), userNames.get(row.createdByUserId()), row.subject(), row.description(),
                Severity.valueOf(row.severity()), status, PublicCaseStatus.of(row.status()), row.assignedUserId(),
                row.assignedUserId() == null ? null : userNames.get(row.assignedUserId()),
                row.assignedTeamId() == null ? null : teamNames.get(row.assignedTeamId()),
                row.createdAt(), row.updatedAt(), row.responseDueAt(), row.slaDueAt(),
                overdue(row.slaDueAt(), status, clock.instant()), row.resolvedAt(),
                row.resolvedByUserId() == null ? null : userNames.get(row.resolvedByUserId()),
                row.resolutionRemarks(), row.closedAt(), row.closureType(), row.reopenCount(), row.bookmarked(),
                timeline, allowedActions(row, status, actor, owner));
    }

    /** R4 {@code allowedActions} from {@link CaseLifecycle} and the actor's permissions. */
    private AllowedActions allowedActions(OutboundCaseRow row, CaseStatus status, CurrentUser actor, boolean owner) {
        boolean any = actor.has(Permissions.CASE_ASSIGN_ANY);
        boolean terminal = CaseLifecycle.terminal(status);
        boolean mayChange = actor.has(Permissions.CASE_STATUS_UPDATE) && (owner || any)
                && row.assignedUserId() != null;
        return new AllowedActions(
                actor.has(Permissions.CASE_ASSIGN) && row.assignedUserId() == null && !terminal,
                any && !terminal,
                mayChange ? lifecycle.availableTargets(status, row.closedAt()) : List.of(),
                actor.has(Permissions.CASE_NOTE_INTERNAL),
                mayChange && status != CaseStatus.CLOSED);
    }

    /** R3 rows: owner names and customer context in one batch call each. */
    List<OutboundCaseSummary> summaries(List<QueueRow> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> userNames = users.displayNames(rows.stream().map(QueueRow::assignedUserId).toList());
        Map<Long, String> teamNames = users.teamNames(rows.stream().map(QueueRow::assignedTeamId).toList());
        Map<Long, CaseCustomer> customers = accounts.caseContexts(rows.stream().map(QueueRow::accountId).toList());
        return rows.stream().map(row -> {
            CaseCustomer c = customers.get(row.accountId());
            QueueCustomer customer = new QueueCustomer(c == null ? null : c.customerRef(),
                    c == null ? null : c.customerName(), c == null ? null : c.accountRef(), mask(row.contactMsisdn()));
            CaseStatus status = CaseStatus.valueOf(row.status());
            return new OutboundCaseSummary(row.reference(), customer, row.categoryName(), row.subcategoryName(),
                    Severity.valueOf(row.severity()), status, PublicCaseStatus.of(row.status()), row.assignedUserId(),
                    ownerName(row.assignedUserId(), row.assignedTeamId(), userNames, teamNames), row.createdAt(),
                    row.updatedAt(), row.slaDueAt(), overdue(row.slaDueAt(), status, now), row.bookmarked());
        }).toList();
    }

    static String ownerName(Long userId, Long teamId, Map<Long, String> userNames, Map<Long, String> teamNames) {
        if (userId != null) {
            return userNames.get(userId);
        }
        return teamId == null ? null : teamNames.get(teamId);
    }

    static boolean overdue(Instant slaDueAt, CaseStatus status, Instant now) {
        return slaDueAt != null && slaDueAt.isBefore(now) && !CaseLifecycle.terminal(status);
    }

    /** NFR-PRIV / OQ 9: {@code ******} + the last 4 digits. */
    static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 4) {
            return "******";
        }
        return "******" + msisdn.substring(msisdn.length() - 4);
    }

    private DateWindow dateWindow(Instant fromInclusive, Instant toExclusive) {
        return new DateWindow(LocalDate.ofInstant(fromInclusive, zone), LocalDate.ofInstant(toExclusive, zone).minusDays(1));
    }

    private Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    private Instant end(LocalDate day) {
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
