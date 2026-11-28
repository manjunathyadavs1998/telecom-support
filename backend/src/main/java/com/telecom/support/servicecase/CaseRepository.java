package com.telecom.support.servicecase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code cases} (design 02-customer backend §2, R9, R10; CUS-05, CUS-06; 03-inbound backend R8–R10; INB-02). Every
 * query filters by an {@code account_id} taken from the principal (customer portal, 02 R1) or from the agent's
 * {@code VerifiedCustomer} (03 R5). Times are UTC.
 */
@Repository
class CaseRepository {

    /** Fields of a new case; {@code subscriptionId} is optional. */
    record NewCase(String reference, long accountId, Long subscriptionId, long categoryId, String caseType,
                   String channel, String subject, String description, String severity, String status,
                   String preferredContact, String contactMsisdn, long createdByUserId, Instant responseDueAt,
                   Instant slaDueAt, Instant createdAt) {}

    /**
     * One row of the customer's list. {@code categoryName} is the top level; {@code subcategoryName} the leaf when the
     * case is filed under a subcategory, else null.
     */
    record CaseSummaryRow(String reference, String categoryName, String subcategoryName, String subject,
                          Instant createdAt, String severity, String status) {}

    /**
     * One row of the agent's list (03 R8) and the head of a detail: top-level and leaf names as {@link CaseSummaryRow},
     * the internal status, channel and assignment ids (owner names come from {@code UserDirectory}).
     */
    record AgentCaseRow(long id, String reference, long categoryId, String categoryName, String subcategoryName,
                        String subject, String caseType, String severity, String status, String channel,
                        Long assignedUserId, Long assignedTeamId, Instant createdAt, Instant updatedAt) {}

    /** A case of the account for the agent's detail (03 R9). */
    record AgentCaseDetailRow(AgentCaseRow head, String description, String preferredContact, String contactMsisdn,
                              long createdByUserId, Instant slaDueAt) {}

    /** A possible duplicate (03 R10). */
    record RelatedCaseRow(String reference, String subject, String status, Instant createdAt) {}

    /** Fixed ORDER BY fragments, chosen by a whitelisted sort key (never from the request text). */
    enum Order {
        CREATED_AT_DESC("c.created_at DESC, c.id DESC"),
        CREATED_AT_ASC("c.created_at ASC, c.id ASC"),
        UPDATED_AT_DESC("c.updated_at DESC, c.id DESC"),
        UPDATED_AT_ASC("c.updated_at ASC, c.id ASC");

        private final String sql;

        Order(String sql) {
            this.sql = sql;
        }
    }

    private static final String INSERT = """
            INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject,
                               description, severity, status, preferred_contact, contact_msisdn, created_by_user_id,
                               response_due_at, sla_due_at, version, created_at, updated_at)
            VALUES (:reference, :accountId, :subscriptionId, :categoryId, :caseType, :channel, :subject,
                    :description, :severity, :status, :preferredContact, :contactMsisdn, :createdBy,
                    :responseDueAt, :slaDueAt, 0, :now, :now)
            """;

    private static final String WINDOW = """
            FROM cases c
            JOIN categories leaf ON leaf.id = c.category_id
            LEFT JOIN categories parent ON parent.id = leaf.parent_id
            WHERE c.account_id = :accountId
              AND c.created_at >= :fromInclusive AND c.created_at < :toExclusive
            """;

    private static final String REFERENCE_FILTER = "  AND c.reference = :reference\n";

    private static final String SELECT_PAGE = """
            SELECT c.reference, COALESCE(parent.name, leaf.name) AS category_name,
                   CASE WHEN parent.id IS NULL THEN NULL ELSE leaf.name END AS subcategory_name,
                   c.subject, c.created_at, c.severity, c.status
            """;

    private static final String STATUS_FILTER = "  AND c.status IN (:statuses)\n";

    private static final String SELECT_AGENT = """
            SELECT c.id, c.reference, c.category_id, COALESCE(parent.name, leaf.name) AS category_name,
                   CASE WHEN parent.id IS NULL THEN NULL ELSE leaf.name END AS subcategory_name,
                   c.subject, c.case_type, c.severity, c.status, c.channel, c.assigned_user_id, c.assigned_team_id,
                   c.created_at, c.updated_at
            """;

    private static final String FIND_AGENT_DETAIL = """
            SELECT c.id, c.reference, c.category_id, COALESCE(parent.name, leaf.name) AS category_name,
                   CASE WHEN parent.id IS NULL THEN NULL ELSE leaf.name END AS subcategory_name,
                   c.subject, c.case_type, c.severity, c.status, c.channel, c.assigned_user_id, c.assigned_team_id,
                   c.created_at, c.updated_at, c.description, c.preferred_contact, c.contact_msisdn,
                   c.created_by_user_id, c.sla_due_at
            FROM cases c
            JOIN categories leaf ON leaf.id = c.category_id
            LEFT JOIN categories parent ON parent.id = leaf.parent_id
            WHERE c.account_id = :accountId AND c.reference = :reference
            """;

    private static final String FIND_RELATED = """
            SELECT c.reference, c.subject, c.status, c.created_at
            FROM cases c
            WHERE c.account_id = :accountId AND c.category_id = :categoryId AND c.id <> :caseId
              AND c.created_at >= :fromInclusive AND c.created_at <= :toInclusive
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the case and returns its id. */
    long insert(NewCase c) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reference", c.reference())
                .addValue("accountId", c.accountId())
                .addValue("subscriptionId", c.subscriptionId())
                .addValue("categoryId", c.categoryId())
                .addValue("caseType", c.caseType())
                .addValue("channel", c.channel())
                .addValue("subject", c.subject())
                .addValue("description", c.description())
                .addValue("severity", c.severity())
                .addValue("status", c.status())
                .addValue("preferredContact", c.preferredContact())
                .addValue("contactMsisdn", c.contactMsisdn())
                .addValue("createdBy", c.createdByUserId())
                .addValue("responseDueAt", utc(c.responseDueAt()))
                .addValue("slaDueAt", utc(c.slaDueAt()))
                .addValue("now", utc(c.createdAt()));
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, params, keys, new String[] {"id"});
        return keys.getKeyAs(Number.class).longValue();
    }

    /** Number of the account's cases created in {@code [from, to)}, optionally with exactly this reference. */
    long count(long accountId, Instant fromInclusive, Instant toExclusive, String reference) {
        Long n = jdbc.queryForObject("SELECT COUNT(*)\n" + WINDOW + referenceFilter(reference),
                windowParams(accountId, fromInclusive, toExclusive, reference), Long.class);
        return n == null ? 0 : n;
    }

    /** One page of the account's cases created in {@code [from, to)}, optionally with exactly this reference. */
    List<CaseSummaryRow> findPage(long accountId, Instant fromInclusive, Instant toExclusive, String reference,
                                  Order order, int limit, long offset) {
        String sql = SELECT_PAGE + WINDOW + referenceFilter(reference)
                + "ORDER BY " + order.sql + "\nLIMIT :limit OFFSET :offset";
        MapSqlParameterSource params = windowParams(accountId, fromInclusive, toExclusive, reference)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> new CaseSummaryRow(rs.getString("reference"),
                rs.getString("category_name"), rs.getString("subcategory_name"), rs.getString("subject"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("severity"), rs.getString("status")));
    }

    /**
     * Number of the account's cases created in {@code [from, to)} for the agent's list (03 R8), optionally only these
     * statuses (empty = all) and exactly this reference.
     */
    long countForAgent(long accountId, Instant fromInclusive, Instant toExclusive, Collection<String> statuses,
                       String reference) {
        Long n = jdbc.queryForObject("SELECT COUNT(*)\n" + WINDOW + statusFilter(statuses) + referenceFilter(reference),
                agentParams(accountId, fromInclusive, toExclusive, statuses, reference), Long.class);
        return n == null ? 0 : n;
    }

    /** One page of the agent's list (03 R8); filters as {@link #countForAgent}. */
    List<AgentCaseRow> findAgentPage(long accountId, Instant fromInclusive, Instant toExclusive,
                                     Collection<String> statuses, String reference, Order order, int limit,
                                     long offset) {
        String sql = SELECT_AGENT + WINDOW + statusFilter(statuses) + referenceFilter(reference)
                + "ORDER BY " + order.sql + "\nLIMIT :limit OFFSET :offset";
        MapSqlParameterSource params = agentParams(accountId, fromInclusive, toExclusive, statuses, reference)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> agentRow(rs));
    }

    /** The account's case with this reference (03 R9); another account's reference is empty. */
    Optional<AgentCaseDetailRow> findAgentDetail(long accountId, String reference) {
        return jdbc.query(FIND_AGENT_DETAIL, Map.of("accountId", accountId, "reference", reference),
                        (rs, i) -> new AgentCaseDetailRow(agentRow(rs), rs.getString("description"),
                                rs.getString("preferred_contact"), rs.getString("contact_msisdn"),
                                rs.getLong("created_by_user_id"), instant(rs, "sla_due_at")))
                .stream()
                .findFirst();
    }

    /**
     * At most {@code limit} other cases of the account with the same leaf category created in {@code [from, to]},
     * newest first (03 R10).
     */
    List<RelatedCaseRow> findRelated(long accountId, long categoryId, long caseId, Instant fromInclusive,
                                     Instant toInclusive, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("categoryId", categoryId)
                .addValue("caseId", caseId)
                .addValue("fromInclusive", utc(fromInclusive))
                .addValue("toInclusive", utc(toInclusive))
                .addValue("limit", limit);
        return jdbc.query(FIND_RELATED, params, (rs, i) -> new RelatedCaseRow(rs.getString("reference"),
                rs.getString("subject"), rs.getString("status"), instant(rs, "created_at")));
    }

    private static AgentCaseRow agentRow(ResultSet rs) throws SQLException {
        return new AgentCaseRow(rs.getLong("id"), rs.getString("reference"), rs.getLong("category_id"),
                rs.getString("category_name"), rs.getString("subcategory_name"), rs.getString("subject"),
                rs.getString("case_type"), rs.getString("severity"), rs.getString("status"), rs.getString("channel"),
                rs.getObject("assigned_user_id", Long.class), rs.getObject("assigned_team_id", Long.class),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String statusFilter(Collection<String> statuses) {
        return statuses.isEmpty() ? "" : STATUS_FILTER;
    }

    private static MapSqlParameterSource agentParams(long accountId, Instant fromInclusive, Instant toExclusive,
                                                     Collection<String> statuses, String reference) {
        MapSqlParameterSource params = windowParams(accountId, fromInclusive, toExclusive, reference);
        if (!statuses.isEmpty()) {
            params.addValue("statuses", List.copyOf(statuses));
        }
        return params;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String referenceFilter(String reference) {
        return reference == null ? "" : REFERENCE_FILTER;
    }

    private static MapSqlParameterSource windowParams(long accountId, Instant fromInclusive, Instant toExclusive,
                                                      String reference) {
        return new MapSqlParameterSource(Map.of(
                        "accountId", accountId,
                        "fromInclusive", utc(fromInclusive),
                        "toExclusive", utc(toExclusive)))
                .addValue("reference", reference);
    }
    // ---------------------------------------------------------------- outbound (04-outbound backend R4, §3.2)

    /** One case as the outbound detail and the command service see it (R4, §3.2); {@code bookmarked} for the actor. */
    record OutboundCaseRow(long id, String reference, long accountId, String categoryName, String subcategoryName,
                           String caseType, String channel, String subject, String description, String severity,
                           String status, String preferredContact, String contactMsisdn, long createdByUserId,
                           Long assignedTeamId, Long assignedUserId, Instant responseDueAt, Instant slaDueAt,
                           Instant createdAt, Instant updatedAt, Instant resolvedAt, Long resolvedByUserId,
                           String resolutionRemarks, Instant closedAt, String closureType, int reopenCount, int version,
                           boolean bookmarked) {}

    private static final String SELECT_OUTBOUND = """
            SELECT c.id, c.reference, c.account_id, c.case_type, c.channel, c.subject, c.description, c.severity,
                   c.status, c.preferred_contact, c.contact_msisdn, c.created_by_user_id, c.assigned_team_id,
                   c.assigned_user_id, c.response_due_at, c.sla_due_at, c.created_at, c.updated_at, c.resolved_at,
                   c.resolved_by_user_id, c.resolution_remarks, c.closed_at, c.closure_type, c.reopen_count, c.version,
                   COALESCE(pc.name, cat.name) AS category_name,
                   CASE WHEN pc.id IS NULL THEN NULL ELSE cat.name END AS subcategory_name,
                   EXISTS (SELECT 1 FROM bookmarks b WHERE b.case_id = c.id AND b.user_id = :actorId) AS bookmarked
            FROM cases c
            JOIN categories cat ON cat.id = c.category_id
            LEFT JOIN categories pc ON pc.id = cat.parent_id
            WHERE c.reference = :reference
            """;

    /** §3.2 step 1: the case row locked until the end of the command's transaction (categories are not locked). */
    private static final String LOCK_OUTBOUND = SELECT_OUTBOUND + """
            FOR UPDATE OF c
            """;

    /** R4: the case by reference (scope is checked by the caller: out of scope is 404 there too). */
    Optional<OutboundCaseRow> findOutbound(String reference, long actorId) {
        return jdbc.query(SELECT_OUTBOUND, Map.of("reference", reference, "actorId", actorId), CaseRepository::mapOutbound)
                .stream().findFirst();
    }

    /** §3.2 (1): locks and reads the case; empty when the reference is unknown. Must run inside a transaction. */
    Optional<OutboundCaseRow> lockForCommand(String reference, long actorId) {
        return jdbc.query(LOCK_OUTBOUND, Map.of("reference", reference, "actorId", actorId), CaseRepository::mapOutbound)
                .stream().findFirst();
    }

    private static OutboundCaseRow mapOutbound(ResultSet rs, int rowNum) throws SQLException {
        return new OutboundCaseRow(rs.getLong("id"), rs.getString("reference"), rs.getLong("account_id"),
                rs.getString("category_name"), rs.getString("subcategory_name"), rs.getString("case_type"),
                rs.getString("channel"), rs.getString("subject"), rs.getString("description"), rs.getString("severity"),
                rs.getString("status"), rs.getString("preferred_contact"), rs.getString("contact_msisdn"),
                rs.getLong("created_by_user_id"), rs.getObject("assigned_team_id", Long.class),
                rs.getObject("assigned_user_id", Long.class), at(rs, "response_due_at"), at(rs, "sla_due_at"),
                at(rs, "created_at"), at(rs, "updated_at"), at(rs, "resolved_at"),
                rs.getObject("resolved_by_user_id", Long.class), rs.getString("resolution_remarks"),
                at(rs, "closed_at"), rs.getString("closure_type"), rs.getInt("reopen_count"), rs.getInt("version"),
                rs.getBoolean("bookmarked"));
    }

    /** UTC {@code DATETIME(6)} read as {@code LocalDateTime} (PR #17: never {@code Timestamp.toLocalDateTime()}). */
    private static Instant at(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    /** R5/R6: owner, team and (CREATED → ASSIGNED) status in one guarded update. */
    private static final String ASSIGN = """
            UPDATE cases
            SET assigned_user_id = :userId, assigned_team_id = :teamId, status = :status,
                updated_at = :now, version = version + 1
            WHERE id = :id AND version = :version
            """;

    /** R7–R9: the status and every resolution/closure column, in one guarded update. */
    private static final String TRANSITION = """
            UPDATE cases
            SET status = :status, resolved_at = :resolvedAt, resolved_by_user_id = :resolvedBy,
                resolution_remarks = :remarks, closed_at = :closedAt, closure_type = :closureType,
                reopen_count = :reopenCount, updated_at = :now, version = version + 1
            WHERE id = :id AND version = :version
            """;

    /** R10: a note is a material update (OUT-04.3). */
    private static final String TOUCH = """
            UPDATE cases
            SET updated_at = :now, version = version + 1
            WHERE id = :id AND version = :version
            """;

    /** The new column values of a status change (R7–R9). */
    record TransitionValues(String status, Instant resolvedAt, Long resolvedBy, String remarks, Instant closedAt,
                            String closureType, int reopenCount) {}

    /** §3.2 (4): true when exactly one row changed (else the caller answers 409 {@code stale_version}). */
    boolean assign(long id, int version, long userId, Long teamId, String status, Instant now) {
        return jdbc.update(ASSIGN, new MapSqlParameterSource()
                .addValue("id", id).addValue("version", version).addValue("userId", userId)
                .addValue("teamId", teamId).addValue("status", status).addValue("now", utc(now))) == 1;
    }

    boolean transition(long id, int version, TransitionValues v, Instant now) {
        return jdbc.update(TRANSITION, new MapSqlParameterSource()
                .addValue("id", id).addValue("version", version).addValue("status", v.status())
                .addValue("resolvedAt", v.resolvedAt() == null ? null : utc(v.resolvedAt()))
                .addValue("resolvedBy", v.resolvedBy()).addValue("remarks", v.remarks())
                .addValue("closedAt", v.closedAt() == null ? null : utc(v.closedAt()))
                .addValue("closureType", v.closureType()).addValue("reopenCount", v.reopenCount())
                .addValue("now", utc(now))) == 1;
    }

    boolean touch(long id, int version, Instant now) {
        return jdbc.update(TOUCH, new MapSqlParameterSource()
                .addValue("id", id).addValue("version", version).addValue("now", utc(now))) == 1;
    }
    /** R8 job: a RESOLVED case whose verification window has elapsed. */
    record DueResolvedRow(long id, String reference) {}

    private static final String FIND_DUE_RESOLVED = """
            SELECT id, reference
            FROM cases
            WHERE status = 'RESOLVED' AND resolved_at <= :cutoff AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** §3.2: guarded on status and resolved_at, so a reopen or an agent's close meanwhile wins and nothing is written. */
    private static final String AUTO_CLOSE = """
            UPDATE cases
            SET status = 'CLOSED', closed_at = :now, closure_type = 'WINDOW_ELAPSED', updated_at = :now,
                version = version + 1
            WHERE id = :id AND status = 'RESOLVED' AND resolved_at <= :cutoff
            """;

    List<DueResolvedRow> findDueResolved(Instant cutoff, long afterId, int limit) {
        return jdbc.query(FIND_DUE_RESOLVED, Map.of("cutoff", utc(cutoff), "after", afterId, "limit", limit),
                (rs, i) -> new DueResolvedRow(rs.getLong("id"), rs.getString("reference")));
    }

    /** True when this call closed the case. */
    boolean autoClose(long id, Instant cutoff, Instant now) {
        return jdbc.update(AUTO_CLOSE, Map.of("id", id, "cutoff", utc(cutoff), "now", utc(now))) == 1;
    }
}
