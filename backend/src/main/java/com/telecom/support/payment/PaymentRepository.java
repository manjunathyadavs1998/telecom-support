package com.telecom.support.payment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code payments} reads of the customer portal (design 02-customer backend §2, R1, R11, R15; CUS-07, CUS-08.3–08.5).
 * Every query filters by the principal's {@code account_id}. Times are UTC. Status writes are not here: they belong to
 * the guarded transitions of §3.1.
 */
@Repository
class PaymentRepository {

    /** A payment with its invoice reference; {@code gatewayTxnId} is only shown on a receipt. */
    record PaymentRow(String reference, String invoiceRef, BigDecimal amount, String currency, String method,
                      String status, String failureReason, Instant initiatedAt, Instant completedAt,
                      String gatewayTxnId) {}

    /** Fixed ORDER BY fragments, chosen by a whitelisted sort key (never from the request text). */
    enum Order {
        INITIATED_AT_DESC("p.initiated_at DESC, p.id DESC"),
        INITIATED_AT_ASC("p.initiated_at ASC, p.id ASC");

        private final String sql;

        Order(String sql) {
            this.sql = sql;
        }
    }

    private static final String SELECT = """
            SELECT p.reference, i.invoice_ref, p.amount, p.currency, p.method, p.status, p.failure_reason,
                   p.initiated_at, p.completed_at, p.gateway_txn_id
            """;

    private static final String FROM = """
            FROM payments p
            JOIN invoices i ON i.id = p.invoice_id
            """;

    private static final String WINDOW = """
            WHERE p.account_id = :accountId
              AND p.initiated_at >= :fromInclusive AND p.initiated_at < :toExclusive
            """;

    private static final String BY_REFERENCE = """
            WHERE p.account_id = :accountId
              AND p.reference = :reference
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Number of the account's payments initiated in {@code [from, to)}. */
    long count(long accountId, Instant fromInclusive, Instant toExclusive) {
        Long n = jdbc.queryForObject("SELECT COUNT(*)\nFROM payments p\n" + WINDOW,
                windowParams(accountId, fromInclusive, toExclusive), Long.class);
        return n == null ? 0 : n;
    }

    /** One page of the account's payments initiated in {@code [from, to)}. */
    List<PaymentRow> findPage(long accountId, Instant fromInclusive, Instant toExclusive, Order order, int limit,
                              long offset) {
        String sql = SELECT + FROM + WINDOW + "ORDER BY " + order.sql + "\nLIMIT :limit OFFSET :offset";
        MapSqlParameterSource params = windowParams(accountId, fromInclusive, toExclusive)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, (rs, i) -> map(rs));
    }

    /** The account's payment with this reference; empty when absent or another account's. */
    Optional<PaymentRow> findByReference(long accountId, String reference) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("reference", reference);
        return jdbc.query(SELECT + FROM + BY_REFERENCE, params, (rs, i) -> map(rs)).stream().findFirst();
    }

    private static PaymentRow map(ResultSet rs) throws SQLException {
        return new PaymentRow(rs.getString("reference"), rs.getString("invoice_ref"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("method"), rs.getString("status"),
                rs.getString("failure_reason"), instant(rs, "initiated_at"), instant(rs, "completed_at"),
                rs.getString("gateway_txn_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static MapSqlParameterSource windowParams(long accountId, Instant fromInclusive, Instant toExclusive) {
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("fromInclusive", utc(fromInclusive))
                .addValue("toExclusive", utc(toExclusive));
    }
}
