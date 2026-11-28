package com.telecom.support.customer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read side of {@code accounts} with its owner's status (design 02-customer backend §2, §4, R1, R16) and, rev 03, the
 * suspension reason and time, the row lock and the suspended MSISDNs (design 03-inbound backend §2, §6.1).
 */
@Repository
class AccountRepository {

    /** One account (1:1 with its customer, OQ 1) and the owning customer's status; suspension fields null unless SUSPENDED. */
    record AccountRow(long id, long customerId, String accountRef, String status, String customerStatus,
                      String suspensionReason, Instant suspendedAt) {}

    private static final String SELECT_ACCOUNT = """
            SELECT a.id, a.customer_id, a.account_ref, a.status, c.status AS customer_status,
                   a.suspension_reason, a.suspended_at
            FROM accounts a
            JOIN customers c ON c.id = a.customer_id
            """;

    private static final String FIND_BY_CUSTOMER = SELECT_ACCOUNT + """
            WHERE a.customer_id = :customerId
            """;

    /** Locks the account row (not the customer row) until the end of the current transaction (03 R14, jobs). */
    private static final String LOCK_BY_ID = SELECT_ACCOUNT + """
            WHERE a.id = :accountId
            FOR UPDATE OF a
            """;

    /** 03 R13/R14: the MSISDNs a reactivation restores, in a stable order. */
    private static final String FIND_SUSPENDED_MSISDNS = """
            SELECT msisdn
            FROM subscriptions
            WHERE account_id = :accountId AND status = 'SUSPENDED'
            ORDER BY is_primary DESC, msisdn
            """;

    /** 04 R3/R4: customer ref and name, account ref and status of the given accounts (one query per page). */
    record CaseContextRow(long accountId, String customerRef, String customerName, String accountRef, String accountStatus) {}

    private static final String FIND_CASE_CONTEXTS = """
            SELECT a.id, c.customer_ref, c.full_name, a.account_ref, a.status
            FROM accounts a
            JOIN customers c ON c.id = a.customer_id
            WHERE a.id IN (:accountIds)
            """;

    /** 04 R2: accounts with a live (ACTIVE or SUSPENDED) line on this exact E.164 number, as 03 R1. */
    private static final String FIND_ACCOUNTS_BY_LIVE_MSISDN = """
            SELECT DISTINCT account_id
            FROM subscriptions
            WHERE msisdn = :msisdn AND status IN ('ACTIVE', 'SUSPENDED')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AccountRow> findByCustomerId(long customerId) {
        return jdbc.query(FIND_BY_CUSTOMER, Map.of("customerId", customerId), AccountRepository::map).stream()
                .findFirst();
    }

    /** Locks and reads the account; empty when it does not exist. Must run inside a transaction. */
    Optional<AccountRow> lockById(long accountId) {
        return jdbc.query(LOCK_BY_ID, Map.of("accountId", accountId), AccountRepository::map).stream().findFirst();
    }

    List<String> findSuspendedMsisdns(long accountId) {
        return jdbc.queryForList(FIND_SUSPENDED_MSISDNS, Map.of("accountId", accountId), String.class);
    }

    /** Account id → context; unknown ids are absent. {@code accountIds} must not be empty. */
    Map<Long, CaseContextRow> findCaseContexts(Collection<Long> accountIds) {
        Map<Long, CaseContextRow> rows = new HashMap<>();
        jdbc.query(FIND_CASE_CONTEXTS, Map.of("accountIds", accountIds), rs -> {
            rows.put(rs.getLong("id"), new CaseContextRow(rs.getLong("id"), rs.getString("customer_ref"),
                    rs.getString("full_name"), rs.getString("account_ref"), rs.getString("status")));
        });
        return rows;
    }

    List<Long> findAccountIdsByLiveMsisdn(String msisdn) {
        return jdbc.queryForList(FIND_ACCOUNTS_BY_LIVE_MSISDN, Map.of("msisdn", msisdn), Long.class);
    }

    private static AccountRow map(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime suspendedAt = rs.getObject("suspended_at", LocalDateTime.class);
        return new AccountRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("account_ref"),
                rs.getString("status"), rs.getString("customer_status"), rs.getString("suspension_reason"),
                suspendedAt == null ? null : suspendedAt.toInstant(ZoneOffset.UTC));
    }
}
