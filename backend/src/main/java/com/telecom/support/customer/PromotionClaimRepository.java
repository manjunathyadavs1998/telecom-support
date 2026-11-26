package com.telecom.support.customer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code promotion_claims} (design 02-customer backend §2, R7, R8). One claim per (promotion, subscription), enforced by
 * {@code uk_promotion_claims}; a claim is only recorded, nothing is fulfilled (OQ 7).
 */
@Repository
class PromotionClaimRepository {

    record ClaimRow(long promotionId, long subscriptionId) {}

    private static final String FIND_BY_SUBSCRIPTIONS = """
            SELECT promotion_id, subscription_id
            FROM promotion_claims
            WHERE subscription_id IN (:subscriptionIds)
            ORDER BY promotion_id, subscription_id
            """;

    private static final String INSERT = """
            INSERT INTO promotion_claims (promotion_id, subscription_id, terms_hash, claimed_by_user_id, status,
                                          created_at)
            VALUES (:promotionId, :subscriptionId, :termsHash, :userId, 'CLAIMED', :now)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PromotionClaimRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every claim made by one of the given subscriptions. */
    List<ClaimRow> findBySubscriptions(Collection<Long> subscriptionIds) {
        if (subscriptionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(FIND_BY_SUBSCRIPTIONS, Map.of("subscriptionIds", subscriptionIds),
                (rs, i) -> new ClaimRow(rs.getLong("promotion_id"), rs.getLong("subscription_id")));
    }

    /**
     * Records a CLAIMED row; returns its id. A second claim of the same promotion by the same subscription fails with
     * {@link org.springframework.dao.DuplicateKeyException} (UK).
     */
    long insert(long promotionId, long subscriptionId, String termsHash, long userId, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("promotionId", promotionId)
                .addValue("subscriptionId", subscriptionId)
                .addValue("termsHash", termsHash)
                .addValue("userId", userId)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }
}
