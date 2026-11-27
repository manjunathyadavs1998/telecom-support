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
}
