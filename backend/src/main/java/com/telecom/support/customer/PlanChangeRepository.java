package com.telecom.support.customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code plan_changes} (design 02-customer backend §2, R4, R6). Read side only so far: the pending change per subscription. */
@Repository
class PlanChangeRepository {

    record PlanChangeRow(long id, long subscriptionId, long fromPlanId, long toPlanId, LocalDate effectiveDate,
                         BigDecimal chargeAmount, String status) {}

    /** The SCHEDULED change of each subscription (at most one per subscription by UK {@code scheduled_sub_id}). */
    private static final String FIND_SCHEDULED = """
            SELECT id, subscription_id, from_plan_id, to_plan_id, effective_date, charge_amount, status
            FROM plan_changes
            WHERE subscription_id IN (:subscriptionIds) AND status = 'SCHEDULED'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PlanChangeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PlanChangeRow> findScheduled(Collection<Long> subscriptionIds) {
        if (subscriptionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(FIND_SCHEDULED, Map.of("subscriptionIds", subscriptionIds), (rs, i) -> new PlanChangeRow(
                rs.getLong("id"), rs.getLong("subscription_id"), rs.getLong("from_plan_id"), rs.getLong("to_plan_id"),
                rs.getObject("effective_date", LocalDate.class), rs.getBigDecimal("charge_amount"),
                rs.getString("status")));
    }
}
