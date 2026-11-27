package com.telecom.support.servicecase;

import com.telecom.support.common.error.ApiExceptions.BadRequestException;
import com.telecom.support.common.error.ApiExceptions.NotFoundException;
import com.telecom.support.customer.InboundProperties;
import com.telecom.support.customer.VerifiedCustomer;
import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.Permissions;
import com.telecom.support.identity.UserDirectory;
import com.telecom.support.servicecase.CaseActivityRepository.ActivityRow;
import com.telecom.support.servicecase.CaseRepository.AgentCaseDetailRow;
import com.telecom.support.servicecase.CaseRepository.AgentCaseRow;
import com.telecom.support.servicecase.CaseRepository.Order;
import com.telecom.support.servicecase.CustomerCaseService.PreferredContact;
import com.telecom.support.servicecase.CustomerCaseService.PublicCaseStatus;
import com.telecom.support.servicecase.CustomerCaseService.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The verified customer's cases for an inbound agent: list I9 (R8) and detail I10 with the activity timeline (R9) and
 * possible duplicates (R10) (design 03-inbound backend; INB-02, BR-04, XC-04/05). The account always comes from the
 * {@link VerifiedCustomer}; a reference of another account is 404. INTERNAL activity is returned only to holders of
 * {@code case.note.internal}; customer endpoints never return activity at all.
 */
@Service
public class AgentCaseQueryService {

    /** R10: at most this many possible duplicates. */
    static final int MAX_RELATED = 5;

    /** Internal case status (BRD §7.1), the {@code CaseStatus} schema. */
    public enum CaseStatus {
        CREATED, ASSIGNED, IN_PROGRESS, PENDING_CUSTOMER, PENDING_EXTERNAL, RESOLVED, CLOSED, REOPENED
    }

    /** The {@code CaseChannel} schema. */
    public enum CaseChannel { CUSTOMER_PORTAL, INBOUND_AGENT }

    /** Whitelisted sort keys of I9 (R8). */
    public enum AgentCaseSort {
        CREATED_AT_DESC("createdAt,desc", Order.CREATED_AT_DESC),
        CREATED_AT_ASC("createdAt,asc", Order.CREATED_AT_ASC),
        UPDATED_AT_DESC("updatedAt,desc", Order.UPDATED_AT_DESC),
        UPDATED_AT_ASC("updatedAt,asc", Order.UPDATED_AT_ASC);

        private final String param;
        private final Order order;

        AgentCaseSort(String param, Order order) {
            this.param = param;
            this.order = order;
        }

        /** The sort for a {@code sort} query value; null means the default; anything else → 400. */
        public static AgentCaseSort fromParam(String value) {
            if (value == null) {
                return CREATED_AT_DESC;
            }
            for (AgentCaseSort sort : values()) {
                if (sort.param.equals(value)) {
                    return sort;
                }
            }
            throw new BadRequestException("Unsupported agent case sort " + value,
                    "Sort must be createdAt or updatedAt, asc or desc.");
        }
    }

    /** The {@code AgentCaseSummary} schema; {@code ownerName} = assigned user, else team, else null. */
    public record AgentCaseSummary(String reference, String categoryName, String subcategoryName, String subject,
                                   String caseType, Severity severity, CaseStatus status,
                                   PublicCaseStatus publicStatus, CaseChannel channel, String ownerName,
                                   Instant createdAt, Instant updatedAt) {}

    /** The {@code AgentCasePage} schema. */
    public record AgentCasePage(List<AgentCaseSummary> items, int page, int size, long totalItems, int totalPages) {}

    /** The {@code CaseActivity} schema. */
    public record CaseActivity(String type, String visibility, String actorName, String oldValue, String newValue,
                               String note, Instant at) {}

    /** The {@code RelatedCase} schema. */
    public record RelatedCase(String reference, String subject, CaseStatus status, Instant createdAt) {}

    /** The {@code AgentCaseDetail} schema (summary fields + detail). */
    public record AgentCaseDetail(String reference, String categoryName, String subcategoryName, String subject,
                                  String caseType, Severity severity, CaseStatus status,
                                  PublicCaseStatus publicStatus, CaseChannel channel, String ownerName,
                                  Instant createdAt, Instant updatedAt, String description,
                                  PreferredContact preferredContact, String contactMsisdn, String createdByName,
                                  Instant slaDueAt, List<CaseActivity> activity,
                                  List<RelatedCase> possibleDuplicates) {}

    private final CaseRepository cases;
    private final CaseActivityRepository activity;
    private final UserDirectory users;
    private final Duration relatedWindow;
    private final Clock clock;
    private final ZoneId zone;

    AgentCaseQueryService(CaseRepository cases, CaseActivityRepository activity, UserDirectory users,
                          InboundProperties inbound, Clock clock,
                          @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.cases = cases;
        this.activity = activity;
        this.users = users;
        this.relatedWindow = inbound.relatedWindow();
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * I9 (R8): the account's cases created in the window ({@link CaseWindow}: default 3 months), optionally only the
     * given statuses and exactly {@code reference} inside the window, sorted by a whitelisted key.
     */
    @Transactional(readOnly = true)
    public AgentCasePage listCases(VerifiedCustomer verified, LocalDate from, LocalDate to,
                                   Collection<CaseStatus> statuses, String reference, int page, int size,
                                   AgentCaseSort sort) {
        CaseWindow window = CaseWindow.resolve(from, to, clock, zone);
        List<String> statusNames = statuses == null || statuses.isEmpty() ? List.of()
                : EnumSet.copyOf(statuses).stream().map(Enum::name).toList();
        long accountId = verified.accountId();
        long total = cases.countForAgent(accountId, window.fromInclusive(), window.toExclusive(), statusNames,
                reference);
        List<AgentCaseRow> rows = total == 0 ? List.of()
                : cases.findAgentPage(accountId, window.fromInclusive(), window.toExclusive(), statusNames, reference,
                        sort.order, size, (long) page * size);
        Owners owners = owners(rows, List.of());
        List<AgentCaseSummary> items = rows.stream().map(row -> summary(row, owners.of(row))).toList();
        int totalPages = (int) ((total + size - 1) / size);
        return new AgentCasePage(items, page, size, total, totalPages);
    }

    /**
     * I10 (R9, R10): the account's case {@code reference} (else 404) with its timeline oldest first (INTERNAL rows only
     * for {@code case.note.internal}) and up to 5 other cases of the same leaf category created within ± the related
     * window of it, newest first.
     */
    @Transactional(readOnly = true)
    public AgentCaseDetail caseDetail(VerifiedCustomer verified, String reference) {
        AgentCaseDetailRow detail = cases.findAgentDetail(verified.accountId(), reference)
                .orElseThrow(() -> new NotFoundException("Case " + reference + " not found in account "
                        + verified.accountId()));
        AgentCaseRow head = detail.head();
        List<ActivityRow> timeline = activity.findTimeline(head.id(),
                CurrentUser.require().has(Permissions.CASE_NOTE_INTERNAL));
        List<Long> actorIds = new ArrayList<>(timeline.stream().map(ActivityRow::actorUserId).toList());
        actorIds.add(detail.createdByUserId());
        Owners owners = owners(List.of(head), actorIds);

        List<RelatedCase> related = cases.findRelated(verified.accountId(), head.categoryId(), head.id(),
                        head.createdAt().minus(relatedWindow), head.createdAt().plus(relatedWindow), MAX_RELATED)
                .stream()
                .map(r -> new RelatedCase(r.reference(), r.subject(), CaseStatus.valueOf(r.status()), r.createdAt()))
                .toList();
        List<CaseActivity> entries = timeline.stream()
                .map(a -> new CaseActivity(a.activityType(), a.visibility(), owners.userName(a.actorUserId()),
                        a.oldValue(), a.newValue(), a.noteText(), a.createdAt()))
                .toList();

        AgentCaseSummary s = summary(head, owners.of(head));
        return new AgentCaseDetail(s.reference(), s.categoryName(), s.subcategoryName(), s.subject(), s.caseType(),
                s.severity(), s.status(), s.publicStatus(), s.channel(), s.ownerName(), s.createdAt(), s.updatedAt(),
                detail.description(), PreferredContact.valueOf(detail.preferredContact()), detail.contactMsisdn(),
                owners.userName(detail.createdByUserId()), detail.slaDueAt(), entries, related);
    }

    /** User and team names for the rows' owners plus the extra user ids, two queries at most. */
    private Owners owners(List<AgentCaseRow> rows, List<Long> extraUserIds) {
        List<Long> userIds = new ArrayList<>(extraUserIds);
        rows.forEach(r -> userIds.add(r.assignedUserId()));
        return new Owners(users.displayNames(userIds),
                users.teamNames(rows.stream().map(AgentCaseRow::assignedTeamId).toList()));
    }

    private record Owners(Map<Long, String> userNames, Map<Long, String> teamNames) {

        String userName(Long userId) {
            return userId == null ? null : userNames.get(userId);
        }

        /** R8: the assigned user's name, else the team's, else null. */
        String of(AgentCaseRow row) {
            String user = userName(row.assignedUserId());
            if (user != null) {
                return user;
            }
            return row.assignedTeamId() == null ? null : teamNames.get(row.assignedTeamId());
        }
    }

    private static AgentCaseSummary summary(AgentCaseRow row, String ownerName) {
        return new AgentCaseSummary(row.reference(), row.categoryName(), row.subcategoryName(), row.subject(),
                row.caseType(), Severity.valueOf(row.severity()), CaseStatus.valueOf(row.status()),
                PublicCaseStatus.of(row.status()), CaseChannel.valueOf(row.channel()), ownerName, row.createdAt(),
                row.updatedAt());
    }
}
