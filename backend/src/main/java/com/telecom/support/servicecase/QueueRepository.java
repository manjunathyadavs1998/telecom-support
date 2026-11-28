package com.telecom.support.servicecase;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The outbound queue reads (design 04-outbound backend R1, R2, R3; OUT-01, OUT-02, NFR-PERF). Every WHERE is built from
 * the fixed fragments below and every value is a named parameter; the ORDER BY comes from the {@link Sort} whitelist only.
 * Times are UTC {@code DATETIME(6)} read as {@code LocalDateTime} (PR #17).
 */
@Repository
class QueueRepository {

    /** One queue row (R3) before names and customer context are added. */
    record QueueRow(long id, String reference, long accountId, String categoryName, String subcategoryName,
                    String severity, String status, Long assignedUserId, Long assignedTeamId, String contactMsisdn,
                    Instant createdAt, Instant updatedAt, Instant slaDueAt, boolean bookmarked) {}

    /** R1 counts. */
    record SummaryRow(long newCount, long open, long inProgress, long pending, long resolved, long closed, long critical,
                      long overdue) {}

    /** R2 sort whitelist (O2 {@code QueueSort}); ties break on {@code id} in the same direction. */
    enum Sort {
        CREATED_AT_DESC("createdAt,desc", "c.created_at DESC, c.id DESC"),
        CREATED_AT_ASC("createdAt,asc", "c.created_at ASC, c.id ASC"),
        UPDATED_AT_DESC("updatedAt,desc", "c.updated_at DESC, c.id DESC"),
        UPDATED_AT_ASC("updatedAt,asc", "c.updated_at ASC, c.id ASC"),
        SLA_DUE_AT_ASC("slaDueAt,asc", "c.sla_due_at ASC, c.id ASC"),
        SLA_DUE_AT_DESC("slaDueAt,desc", "c.sla_due_at DESC, c.id DESC"),
        SEVERITY_DESC("severity,desc", "FIELD(c.severity, 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL') DESC, c.id DESC"),
        SEVERITY_ASC("severity,asc", "FIELD(c.severity, 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL') ASC, c.id ASC");

        final String param;
        final String sql;

        Sort(String param, String sql) {
            this.param = param;
            this.sql = sql;
        }
    }

    /** R2 filters, already validated; nulls / empty lists mean "no filter". */
    record Filter(OutboundScope scope, Instant createdFrom, Instant createdTo, Instant updatedFrom, Instant updatedTo,
                  List<String> statuses, List<String> severities, List<Long> categoryIds, String assignee,
                  Long assigneeUserId, String msisdn, List<Long> msisdnAccountIds, String reference, boolean overdue,
                  Instant now) {}

    private static final String FROM = """
            FROM cases c
            JOIN categories cat ON cat.id = c.category_id
            LEFT JOIN categories pc ON pc.id = cat.parent_id
            WHERE """ + OutboundScope.SQL + "\n";

    private static final String SELECT_ROW = """
            SELECT c.id, c.reference, c.account_id, c.severity, c.status, c.assigned_user_id, c.assigned_team_id,
                   c.contact_msisdn, c.created_at, c.updated_at, c.sla_due_at,
                   COALESCE(pc.name, cat.name) AS category_name,
                   CASE WHEN pc.id IS NULL THEN NULL ELSE cat.name END AS subcategory_name,
                   EXISTS (SELECT 1 FROM bookmarks b WHERE b.case_id = c.id AND b.user_id = :scopeUser) AS bookmarked
            """;

    private static final String SUMMARY = """
            SELECT
              COALESCE(SUM(c.status = 'CREATED'), 0) AS new_count,
              COALESCE(SUM(c.status IN ('ASSIGNED', 'REOPENED')), 0) AS open_count,
              COALESCE(SUM(c.status = 'IN_PROGRESS'), 0) AS in_progress,
              COALESCE(SUM(c.status IN ('PENDING_CUSTOMER', 'PENDING_EXTERNAL')), 0) AS pending,
              COALESCE(SUM(c.status = 'RESOLVED'), 0) AS resolved,
              COALESCE(SUM(c.status = 'CLOSED'), 0) AS closed,
              COALESCE(SUM(c.severity = 'CRITICAL' AND c.status NOT IN ('RESOLVED', 'CLOSED')), 0) AS critical,
              COALESCE(SUM(c.sla_due_at < :now AND c.status NOT IN ('RESOLVED', 'CLOSED')), 0) AS overdue
            """ + FROM + """
              AND c.created_at >= :createdFrom AND c.created_at < :createdTo
            """;

    private static final String CATEGORY_IDS = """
            SELECT c.id
            FROM categories c
            LEFT JOIN categories p ON p.id = c.parent_id
            WHERE c.code = :code OR p.code = :code
            """;

    private final NamedParameterJdbcTemplate jdbc;

    QueueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SummaryRow summary(OutboundScope scope, Instant createdFrom, Instant createdTo, Instant now) {
        Map<String, Object> params = new HashMap<>(scope.params());
        params.put("createdFrom", utc(createdFrom));
        params.put("createdTo", utc(createdTo));
        params.put("now", utc(now));
        return jdbc.queryForObject(SUMMARY, params, (rs, i) -> new SummaryRow(rs.getLong("new_count"),
                rs.getLong("open_count"), rs.getLong("in_progress"), rs.getLong("pending"), rs.getLong("resolved"),
                rs.getLong("closed"), rs.getLong("critical"), rs.getLong("overdue")));
    }

    /** R2 category filter: the code itself plus, for a top level, its children; empty for an unknown code. */
    List<Long> categoryIds(String code) {
        return jdbc.queryForList(CATEGORY_IDS, Map.of("code", code), Long.class);
    }

    long count(Filter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        Long n = jdbc.queryForObject("SELECT COUNT(*)\n" + FROM + where, params, Long.class);
        return n == null ? 0 : n;
    }

    List<QueueRow> page(Filter filter, Sort sort, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        params.put("limit", size);
        params.put("offset", (long) page * size);
        String sql = SELECT_ROW + FROM + where + "ORDER BY " + sort.sql + "\nLIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, QueueRepository::mapRow);
    }

    /** Appends one fixed fragment per present filter (R2); values go into {@code params} only. */
    private static String where(Filter f, Map<String, Object> params) {
        params.putAll(f.scope().params());
        StringBuilder sql = new StringBuilder();
        if (f.createdFrom() != null) {
            sql.append("  AND c.created_at >= :createdFrom AND c.created_at < :createdTo\n");
            params.put("createdFrom", utc(f.createdFrom()));
            params.put("createdTo", utc(f.createdTo()));
        }
        if (f.updatedFrom() != null) {
            sql.append("  AND c.updated_at >= :updatedFrom AND c.updated_at < :updatedTo\n");
            params.put("updatedFrom", utc(f.updatedFrom()));
            params.put("updatedTo", utc(f.updatedTo()));
        }
        if (!f.statuses().isEmpty()) {
            sql.append("  AND c.status IN (:statuses)\n");
            params.put("statuses", f.statuses());
        }
        if (!f.severities().isEmpty()) {
            sql.append("  AND c.severity IN (:severities)\n");
            params.put("severities", f.severities());
        }
        if (f.categoryIds() != null) {
            sql.append("  AND c.category_id IN (:categoryIds)\n");
            params.put("categoryIds", f.categoryIds().isEmpty() ? List.of(-1L) : f.categoryIds());
        }
        switch (f.assignee()) {
            case "ME" -> {
                sql.append("  AND c.assigned_user_id = :scopeUser\n");
            }
            case "UNASSIGNED" -> sql.append("  AND c.assigned_user_id IS NULL\n");
            case "USER" -> {
                sql.append("  AND c.assigned_user_id = :assigneeUserId\n");
                params.put("assigneeUserId", f.assigneeUserId());
            }
            default -> { }
        }
        if (f.msisdn() != null) {
            if (f.msisdnAccountIds().isEmpty()) {
                sql.append("  AND c.contact_msisdn = :msisdn\n");
            } else {
                sql.append("  AND (c.contact_msisdn = :msisdn OR c.account_id IN (:msisdnAccounts))\n");
                params.put("msisdnAccounts", f.msisdnAccountIds());
            }
            params.put("msisdn", f.msisdn());
        }
        if (f.reference() != null) {
            sql.append("  AND c.reference = :reference\n");
            params.put("reference", f.reference());
        }
        if (f.overdue()) {
            sql.append("  AND c.sla_due_at < :now AND c.status NOT IN ('RESOLVED', 'CLOSED')\n");
            params.put("now", utc(f.now()));
        }
        return sql.toString();
    }

    private static QueueRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new QueueRow(rs.getLong("id"), rs.getString("reference"), rs.getLong("account_id"),
                rs.getString("category_name"), rs.getString("subcategory_name"), rs.getString("severity"),
                rs.getString("status"), rs.getObject("assigned_user_id", Long.class),
                rs.getObject("assigned_team_id", Long.class), rs.getString("contact_msisdn"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "sla_due_at"),
                rs.getBoolean("bookmarked"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
