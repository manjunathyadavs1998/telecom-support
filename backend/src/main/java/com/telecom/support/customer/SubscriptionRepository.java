package com.telecom.support.customer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code subscriptions} with the account's billing-cycle day (design 02-customer backend §2, R1, R4, R5) and the guarded
 * plan switch of the apply job (R6).
 */
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

    /** One subscription, only when it belongs to the account (R1: another account's id → empty → 404). */
    private static final String FIND_OWNED = """
            SELECT s.id, s.msisdn, s.plan_id, s.is_primary, s.status, a.billing_cycle_day
            FROM subscriptions s
            JOIN accounts a ON a.id = s.account_id
            WHERE s.id = :id AND s.account_id = :accountId
            """;

    /** R6 guard: switches the plan only while the subscription is ACTIVE and still on the quoted plan. */
    private static final String SWITCH_PLAN = """
            UPDATE subscriptions SET plan_id = :toPlanId, updated_at = :now
            WHERE id = :id AND plan_id = :fromPlanId AND status = 'ACTIVE'
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

    Optional<SubscriptionRow> findOwned(long accountId, long subscriptionId) {
        return jdbc.query(FIND_OWNED, Map.of("id", subscriptionId, "accountId", accountId), (rs, i) ->
                        new SubscriptionRow(rs.getLong("id"), rs.getString("msisdn"), rs.getLong("plan_id"),
                                rs.getBoolean("is_primary"), rs.getString("status"), rs.getInt("billing_cycle_day")))
                .stream()
                .findFirst();
    }

    /** Guarded plan switch (R6); returns the number of rows changed (0 = guard failed). */
    int switchPlan(long subscriptionId, long fromPlanId, long toPlanId, Instant now) {
        return jdbc.update(SWITCH_PLAN, Map.of("id", subscriptionId, "fromPlanId", fromPlanId, "toPlanId", toPlanId,
                "now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }
}
