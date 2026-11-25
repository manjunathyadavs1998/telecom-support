package com.telecom.support.customer;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code subscriptions} with the account's billing-cycle day (design 02-customer backend §2, R1, R4). */
@Repository
class SubscriptionRepository {

    record SubscriptionRow(long id, String msisdn, long planId, boolean primary, String status, int billingCycleDay) {}

    /** Every subscription of the account (any status), primary first (R4, openapi E5). */
    private static final String FIND_BY_ACCOUNT = """
            SELECT s.id, s.msisdn, s.plan_id, s.is_primary, s.status, a.billing_cycle_day
            FROM subscriptions s
            JOIN accounts a ON a.id = s.account_id
            WHERE s.account_id = :accountId
            ORDER BY s.is_primary DESC, s.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SubscriptionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SubscriptionRow> findByAccountId(long accountId) {
        return jdbc.query(FIND_BY_ACCOUNT, Map.of("accountId", accountId), (rs, i) -> new SubscriptionRow(
                rs.getLong("id"), rs.getString("msisdn"), rs.getLong("plan_id"), rs.getBoolean("is_primary"),
                rs.getString("status"), rs.getInt("billing_cycle_day")));
    }
}
