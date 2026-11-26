package com.telecom.support.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code promotions} and {@code promotion_plans} (design 02-customer backend §2, §6). */
@Repository
class PromotionRepository {

    record PromotionRow(long id, String code, String title, String shortDescription, String eligibilityText,
                        String terms, Instant validFrom, Instant validTo, String status) {}

    record PromotionPlanRow(long promotionId, long planId) {}

    private static final String COLUMNS = """
            SELECT id, code, title, short_description, eligibility_text, terms, valid_from, valid_to, status
            FROM promotions
            """;

    /** ACTIVE and inside its validity window: {@code valid_from <= now < valid_to} (R7). */
    private static final String FIND_LIVE = COLUMNS + """
            WHERE status = 'ACTIVE' AND valid_from <= :now AND valid_to > :now
            ORDER BY valid_to, code
            """;

    private static final String FIND_BY_CODE = COLUMNS + """
            WHERE code = :code
            """;

    private static final String FIND_PLANS = """
            SELECT promotion_id, plan_id
            FROM promotion_plans
            WHERE promotion_id IN (:promotionIds)
            ORDER BY promotion_id, plan_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PromotionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<PromotionRow> findLive(Instant now) {
        return jdbc.query(FIND_LIVE, Map.of("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
                (rs, i) -> promotion(rs));
    }

    List<PromotionRow> findByCode(String code) {
        return jdbc.query(FIND_BY_CODE, Map.of("code", code), (rs, i) -> promotion(rs));
    }

    /** The plan restrictions of the given promotions (a promotion without rows is open to all plans). */
    List<PromotionPlanRow> findPlans(Collection<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(FIND_PLANS, Map.of("promotionIds", promotionIds),
                (rs, i) -> new PromotionPlanRow(rs.getLong("promotion_id"), rs.getLong("plan_id")));
    }

    private static PromotionRow promotion(ResultSet rs) throws SQLException {
        return new PromotionRow(rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                rs.getString("short_description"), rs.getString("eligibility_text"), rs.getString("terms"),
                utc(rs, "valid_from"), utc(rs, "valid_to"), rs.getString("status"));
    }

    private static Instant utc(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
