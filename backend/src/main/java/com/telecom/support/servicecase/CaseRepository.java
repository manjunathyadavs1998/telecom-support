package com.telecom.support.servicecase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code cases} (design 02-customer backend §2, R9, R10; CUS-05, CUS-06). Every query of the customer portal filters by
 * the principal's {@code account_id} (R1). Times are UTC.
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

    /** Fixed ORDER BY fragments, chosen by a whitelisted sort key (never from the request text). */
    enum Order {
        CREATED_AT_DESC("c.created_at DESC, c.id DESC"),
        CREATED_AT_ASC("c.created_at ASC, c.id ASC");

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
