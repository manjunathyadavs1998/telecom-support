package com.telecom.support.reactivation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code reactivation_requests} (design 03-inbound backend §2, R13–R15, §3.1). Status is written only through
 * {@code ReactivationTransitionService}; the reads here serve eligibility (R13) and the history (I15).
 */
@Repository
class ReactivationRepository {

    private static final String FIND_OPEN_REFERENCE = """
            SELECT reference FROM reactivation_requests WHERE open_account_id = :accountId
            """;

    private static final String COUNT_ACCOUNT_SINCE = """
            SELECT COUNT(*) FROM reactivation_requests
            WHERE account_id = :accountId AND status <> 'FAILED' AND created_at > :since
            """;

    private static final String COUNT_AGENT_SINCE = """
            SELECT COUNT(*) FROM reactivation_requests
            WHERE requested_by_user_id = :userId AND status <> 'FAILED' AND created_at >= :since
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ReactivationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The reference of the account's open (PENDING, ACTIVE, EXPIRING) request, if any (at most one, UK). */
    Optional<String> findOpenReference(long accountId) {
        return jdbc.queryForList(FIND_OPEN_REFERENCE, new MapSqlParameterSource("accountId", accountId), String.class)
                .stream().findFirst();
    }

    /** Non-FAILED requests of the account created after {@code since} (R13 account limit). */
    long countNonFailedForAccountSince(long accountId, Instant since) {
        return jdbc.queryForObject(COUNT_ACCOUNT_SINCE, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("since", utc(since)), Long.class);
    }

    /** Non-FAILED requests by the agent created at or after {@code since} (R13 agent daily limit). */
    long countNonFailedByAgentSince(long userId, Instant since) {
        return jdbc.queryForObject(COUNT_AGENT_SINCE, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", utc(since)), Long.class);
    }

    /** UTC, truncated to the column's microseconds. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
