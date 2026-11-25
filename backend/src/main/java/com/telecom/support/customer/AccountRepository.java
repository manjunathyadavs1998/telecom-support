package com.telecom.support.customer;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code accounts} with its owner's status (design 02-customer backend §2, §4, R1, R16). */
@Repository
class AccountRepository {

    /** One account (1:1 with its customer, OQ 1) and the owning customer's status. */
    record AccountRow(long id, long customerId, String accountRef, String status, String customerStatus) {}

    private static final String FIND_BY_CUSTOMER = """
            SELECT a.id, a.customer_id, a.account_ref, a.status, c.status AS customer_status
            FROM accounts a
            JOIN customers c ON c.id = a.customer_id
            WHERE a.customer_id = :customerId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AccountRow> findByCustomerId(long customerId) {
        return jdbc.query(FIND_BY_CUSTOMER, Map.of("customerId", customerId), (rs, i) -> new AccountRow(
                        rs.getLong("id"), rs.getLong("customer_id"), rs.getString("account_ref"),
                        rs.getString("status"), rs.getString("customer_status")))
                .stream()
                .findFirst();
    }
}
