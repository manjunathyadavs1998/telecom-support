package com.telecom.support.payment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code invoices} (design 02-customer backend §2, R1; CUS-07, CUS-08.1). Every query of the customer portal filters by
 * the principal's {@code account_id}. The only write is the R14 credit of a winning SUCCEEDED transition; initiate locks
 * the invoice row ({@link #lockOwn}).
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

    /** Balance of an invoice locked for an R14 credit. */
    record InvoiceBalance(long id, String invoiceRef, BigDecimal amountDue, BigDecimal amountPaid, String status,
                          int version) {}

    private static final String LOCK_BALANCE = """
            SELECT id, invoice_ref, amount_due, amount_paid, status, version
            FROM invoices
            WHERE id = :id
            FOR UPDATE
            """;

    private static final String LOCK_OWN = """
            SELECT id, invoice_ref, amount_due, amount_paid, status, version
            FROM invoices
            WHERE id = :id
              AND account_id = :accountId
            FOR UPDATE
            """;

    /** R14: status is assigned first, so it compares against the amount paid before this credit plus the credit. */
    private static final String CREDIT = """
            UPDATE invoices
            SET status = IF(amount_paid + :amount >= amount_due, 'PAID', status),
                amount_paid = amount_paid + :amount,
                version = version + 1,
                updated_at = :now
            WHERE id = :id
              AND version = :version
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

    /** Locks the invoice for a credit (always after its payment row, see {@code PaymentRepository#lockByReference}). */
    Optional<InvoiceBalance> lockBalance(long id) {
        return jdbc.query(LOCK_BALANCE, new MapSqlParameterSource("id", id),
                (rs, i) -> new InvoiceBalance(rs.getLong("id"), rs.getString("invoice_ref"),
                        rs.getBigDecimal("amount_due"), rs.getBigDecimal("amount_paid"), rs.getString("status"),
                        rs.getInt("version"))).stream().findFirst();
    }

    /**
     * Locks the account's invoice for an initiate (R12, erratum E1): every payment insert of an invoice runs under this
     * lock. Empty when absent or another account's.
     */
    Optional<InvoiceBalance> lockOwn(long accountId, long id) {
        return jdbc.query(LOCK_OWN, new MapSqlParameterSource().addValue("id", id).addValue("accountId", accountId),
                (rs, i) -> new InvoiceBalance(rs.getLong("id"), rs.getString("invoice_ref"),
                        rs.getBigDecimal("amount_due"), rs.getBigDecimal("amount_paid"), rs.getString("status"),
                        rs.getInt("version"))).stream().findFirst();
    }

    /**
     * R14 credit guarded by id + version: adds {@code amount} to {@code amount_paid} and marks the invoice PAID once
     * {@code amount_paid >= amount_due}. Overpayment is still credited.
     *
     * @return rows changed (0 when the version is stale)
     */
    int credit(long id, int version, BigDecimal amount, Instant now) {
        return jdbc.update(CREDIT, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("version", version)
                .addValue("amount", amount)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    private static InvoiceRow map(ResultSet rs) throws SQLException {
        return new InvoiceRow(rs.getLong("id"), rs.getString("invoice_ref"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("amount_due"), rs.getBigDecimal("amount_paid"), rs.getString("status"));
    }
}
