package com.telecom.support.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code plan_changes} (design 02-customer backend §2, R4, R6): the pending change per subscription, scheduling (E7)
 * and the guarded state changes of {@link PlanChangeApplyJob}. Every status change is guarded by
 * {@code status = 'SCHEDULED'}; the UK {@code scheduled_sub_id} allows one SCHEDULED change per subscription.
 */
@Repository
class PlanChangeRepository {

    record PlanChangeRow(long id, long subscriptionId, long fromPlanId, long toPlanId, LocalDate effectiveDate,
                         BigDecimal chargeAmount, String status) {}

    /** A SCHEDULED change locked for the apply job, with the subscription's number for provisioning. */
    record DueChange(long id, long subscriptionId, String msisdn, long fromPlanId, long toPlanId,
                     LocalDate effectiveDate, BigDecimal chargeAmount, int applyAttempts) {}

    /** The SCHEDULED change of each subscription (at most one per subscription by UK {@code scheduled_sub_id}). */
    private static final String FIND_SCHEDULED = """
            SELECT id, subscription_id, from_plan_id, to_plan_id, effective_date, charge_amount, status
            FROM plan_changes
            WHERE subscription_id IN (:subscriptionIds) AND status = 'SCHEDULED'
            """;

    private static final String INSERT_SCHEDULED = """
            INSERT INTO plan_changes (subscription_id, from_plan_id, to_plan_id, effective_date, charge_amount, status,
                                      apply_attempts, requested_by_user_id, created_at, updated_at)
            VALUES (:subscriptionId, :fromPlanId, :toPlanId, :effectiveDate, :charge, 'SCHEDULED', 0, :userId,
                    :now, :now)
            """;

    /** Ids of SCHEDULED changes due on or before {@code today} (idx status, effective_date). */
    private static final String FIND_DUE_IDS = """
            SELECT id FROM plan_changes
            WHERE status = 'SCHEDULED' AND effective_date <= :today
            ORDER BY effective_date, id
            """;

    /** Locks one still-SCHEDULED change, so parallel job runs process it once. */
    private static final String LOCK_SCHEDULED = """
            SELECT pc.id, pc.subscription_id, s.msisdn, pc.from_plan_id, pc.to_plan_id, pc.effective_date,
                   pc.charge_amount, pc.apply_attempts
            FROM plan_changes pc
            JOIN subscriptions s ON s.id = pc.subscription_id
            WHERE pc.id = :id AND pc.status = 'SCHEDULED'
            FOR UPDATE
            """;

    private static final String MARK_APPLIED = """
            UPDATE plan_changes SET status = 'APPLIED', updated_at = :now
            WHERE id = :id AND status = 'SCHEDULED'
            """;

    private static final String MARK_CANCELLED = """
            UPDATE plan_changes SET status = 'CANCELLED', cancel_reason = :reason, updated_at = :now
            WHERE id = :id AND status = 'SCHEDULED'
            """;

    /** Provider failure (R6): stays SCHEDULED, one more attempt counted (TINYINT, capped). */
    private static final String COUNT_FAILED_ATTEMPT = """
            UPDATE plan_changes SET apply_attempts = LEAST(apply_attempts + 1, 127), updated_at = :now
            WHERE id = :id AND status = 'SCHEDULED'
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

    /**
     * Inserts a SCHEDULED change and returns its id.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the subscription already has a SCHEDULED change
     */
    long insertScheduled(long subscriptionId, long fromPlanId, long toPlanId, LocalDate effectiveDate,
                         BigDecimal charge, long userId, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT_SCHEDULED, new MapSqlParameterSource()
                .addValue("subscriptionId", subscriptionId)
                .addValue("fromPlanId", fromPlanId)
                .addValue("toPlanId", toPlanId)
                .addValue("effectiveDate", effectiveDate)
                .addValue("charge", charge)
                .addValue("userId", userId)
                .addValue("now", utc(now)), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    List<Long> findDueIds(LocalDate today) {
        return jdbc.queryForList(FIND_DUE_IDS, Map.of("today", today), Long.class);
    }

    Optional<DueChange> lockScheduled(long id) {
        return jdbc.query(LOCK_SCHEDULED, Map.of("id", id), (rs, i) -> new DueChange(rs.getLong("id"),
                        rs.getLong("subscription_id"), rs.getString("msisdn"), rs.getLong("from_plan_id"),
                        rs.getLong("to_plan_id"), rs.getObject("effective_date", LocalDate.class),
                        rs.getBigDecimal("charge_amount"), rs.getInt("apply_attempts")))
                .stream()
                .findFirst();
    }

    int markApplied(long id, Instant now) {
        return jdbc.update(MARK_APPLIED, Map.of("id", id, "now", utc(now)));
    }

    int markCancelled(long id, String reason, Instant now) {
        return jdbc.update(MARK_CANCELLED, Map.of("id", id, "reason", reason, "now", utc(now)));
    }

    int countFailedAttempt(long id, Instant now) {
        return jdbc.update(COUNT_FAILED_ATTEMPT, Map.of("id", id, "now", utc(now)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
