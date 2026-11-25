package com.telecom.support.payment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code invoices} (design 02-customer backend §2, R1; CUS-07, CUS-08.1). Every query of the customer portal filters by
 * the principal's {@code account_id}.
 */
@Repository
class InvoiceRepository {

    record InvoiceRow(long id, String invoiceRef, LocalDate periodStart, LocalDate periodEnd, LocalDate issueDate,
                      LocalDate dueDate, BigDecimal amountDue, BigDecimal amountPaid, String status) {}

    private static final String SELECT_LATEST = """
            SELECT id, invoice_ref, period_start, period_end, issue_date, due_date, amount_due, amount_paid, status
            FROM invoices
            WHERE account_id = :accountId
            """;

    private static final String STATUS_FILTER = "  AND status = :status\n";

    private static final String ORDER_LIMIT = """
            ORDER BY issue_date DESC, id DESC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    InvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The account's newest {@code limit} invoices by issue date, optionally only those in {@code status}. */
    List<InvoiceRow> findLatest(long accountId, String status, int limit) {
        String sql = SELECT_LATEST + (status == null ? "" : STATUS_FILTER) + ORDER_LIMIT;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("status", status)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> map(rs));
    }

    private static InvoiceRow map(ResultSet rs) throws SQLException {
        return new InvoiceRow(rs.getLong("id"), rs.getString("invoice_ref"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("amount_due"), rs.getBigDecimal("amount_paid"), rs.getString("status"));
    }
}
