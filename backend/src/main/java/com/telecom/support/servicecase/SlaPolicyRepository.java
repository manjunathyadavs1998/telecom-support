package com.telecom.support.servicecase;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read side of {@code sla_policies} (design 02-customer backend §2, R10; BR-07): response and resolution minutes per
 * (category, severity). R10 looks up the leaf first, then its parent.
 */
@Repository
class SlaPolicyRepository {

    record SlaPolicyRow(long categoryId, String severity, int responseMinutes, int resolutionMinutes) {}

    private static final String FIND_ACTIVE = """
            SELECT category_id, severity, response_minutes, resolution_minutes
            FROM sla_policies
            WHERE category_id = :categoryId AND severity = :severity AND active = TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SlaPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The active SLA row of exactly this category and severity, if any. */
    Optional<SlaPolicyRow> findActive(long categoryId, String severity) {
        return jdbc.query(FIND_ACTIVE, Map.of("categoryId", categoryId, "severity", severity),
                        (rs, i) -> new SlaPolicyRow(rs.getLong("category_id"), rs.getString("severity"),
                                rs.getInt("response_minutes"), rs.getInt("resolution_minutes")))
                .stream()
                .findFirst();
    }
}
