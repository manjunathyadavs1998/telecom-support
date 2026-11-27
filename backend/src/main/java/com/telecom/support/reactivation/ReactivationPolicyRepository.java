package com.telecom.support.reactivation;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code reactivation_policies} (design 03-inbound backend §2, R13; INB-04.1/04.2, §14 #5): eligibility and maximum
 * duration per suspension reason. FRAUD/LEGAL/REGULATORY are never eligible (DB CHECK and, independently, R13).
 * Not final, so an IT can force a policy the CHECK forbids.
 */
@Repository
class ReactivationPolicyRepository {

    /** One policy row; {@code maxHours} is 1–24. */
    record Policy(String suspensionReason, boolean eligible, int maxHours) {}

    private static final String FIND = """
            SELECT suspension_reason, eligible, max_hours
            FROM reactivation_policies
            WHERE suspension_reason = :reason
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ReactivationPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The policy of {@code suspensionReason}; empty when there is none. */
    Optional<Policy> find(String suspensionReason) {
        return jdbc.query(FIND, Map.of("reason", suspensionReason),
                        (rs, n) -> new Policy(rs.getString("suspension_reason"), rs.getBoolean("eligible"),
                                rs.getInt("max_hours")))
                .stream().findFirst();
    }
}
