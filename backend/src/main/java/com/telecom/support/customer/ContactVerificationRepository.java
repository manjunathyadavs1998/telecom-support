package com.telecom.support.customer;

import com.telecom.support.customer.VerificationCodeSender.Channel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code contact_verifications} (design 02-customer backend §2, R3, §3.2). Every state change is one guarded statement,
 * so parallel confirms cannot both win: the attempt counter, LOCKED and VERIFIED all require {@code status='PENDING'}.
 */
@Repository
class ContactVerificationRepository {

    /** One verification; {@code codeHmac} is the hex HMAC-SHA256 of the code, never the code. */
    record VerificationRow(long id, long customerId, Channel channel, String newValue, String codeHmac, int attempts,
                           String status, Instant expiresAt) {}

    static final String PENDING = "PENDING";
    static final String LOCKED = "LOCKED";

    private static final String COUNT_STARTS_SINCE = """
            SELECT COUNT(*) AS starts, MIN(created_at) AS oldest
            FROM contact_verifications
            WHERE customer_id = :customerId AND created_at > :since
            """;

    private static final String SUPERSEDE_PENDING = """
            UPDATE contact_verifications
            SET status = 'SUPERSEDED', updated_at = :now
            WHERE customer_id = :customerId AND channel = :channel AND status = 'PENDING'
            """;

    private static final String INSERT = """
            INSERT INTO contact_verifications (customer_id, channel, new_value, code_hmac, attempts, status, expires_at,
                                               created_at, updated_at)
            VALUES (:customerId, :channel, :newValue, :codeHmac, 0, 'PENDING', :expiresAt, :now, :now)
            """;

    /** §3.2 step 1: counts one attempt while the verification is still usable. */
    private static final String COUNT_ATTEMPT = """
            UPDATE contact_verifications
            SET attempts = attempts + 1, updated_at = :now
            WHERE id = :id AND customer_id = :customerId AND status = 'PENDING' AND attempts < :maxAttempts
              AND expires_at > :now
            """;

    private static final String FIND = """
            SELECT id, customer_id, channel, new_value, code_hmac, attempts, status, expires_at
            FROM contact_verifications
            WHERE id = :id
            """;

    /** Guarded PENDING → {@code :to} (LOCKED, VERIFIED or EXPIRED). */
    private static final String TRANSITION_FROM_PENDING = """
            UPDATE contact_verifications
            SET status = :to, updated_at = :now
            WHERE id = :id AND status = 'PENDING'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ContactVerificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Starts by the customer after {@code since}, and the oldest of them (empty when none). */
    record Starts(int count, Optional<Instant> oldest) {}

    Starts startsSince(long customerId, Instant since) {
        return jdbc.queryForObject(COUNT_STARTS_SINCE, Map.of("customerId", customerId, "since", utc(since)),
                (rs, i) -> new Starts(rs.getInt("starts"),
                        Optional.ofNullable(rs.getObject("oldest", LocalDateTime.class)).map(t -> t.toInstant(ZoneOffset.UTC))));
    }

    /** R3: a new start supersedes the PENDING verification of the same channel. */
    int supersedePending(long customerId, Channel channel, Instant now) {
        return jdbc.update(SUPERSEDE_PENDING,
                Map.of("customerId", customerId, "channel", channel.name(), "now", utc(now)));
    }

    long insert(long customerId, Channel channel, String newValue, String codeHmac, Instant expiresAt, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("channel", channel.name())
                .addValue("newValue", newValue)
                .addValue("codeHmac", codeHmac)
                .addValue("expiresAt", utc(expiresAt))
                .addValue("now", utc(now)), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    /** §3.2 step 1; returns 1 when the attempt was counted, 0 when the verification is absent, foreign or unusable. */
    int countAttempt(long id, long customerId, int maxAttempts, Instant now) {
        return jdbc.update(COUNT_ATTEMPT,
                Map.of("id", id, "customerId", customerId, "maxAttempts", maxAttempts, "now", utc(now)));
    }

    Optional<VerificationRow> find(long id) {
        return jdbc.query(FIND, Map.of("id", id), (rs, i) -> new VerificationRow(
                        rs.getLong("id"), rs.getLong("customer_id"), Channel.valueOf(rs.getString("channel")),
                        rs.getString("new_value"), rs.getString("code_hmac"), rs.getInt("attempts"),
                        rs.getString("status"), rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .stream()
                .findFirst();
    }

    /** Guarded PENDING → LOCKED; 1 when this call locked it. */
    int lock(long id, Instant now) {
        return transition(id, LOCKED, now);
    }

    /** Guarded PENDING → VERIFIED; 1 only for the single winning confirm. */
    int markVerified(long id, Instant now) {
        return transition(id, "VERIFIED", now);
    }

    /** Guarded PENDING → EXPIRED (the code could not be delivered). */
    int markExpired(long id, Instant now) {
        return transition(id, "EXPIRED", now);
    }

    private int transition(long id, String to, Instant now) {
        return jdbc.update(TRANSITION_FROM_PENDING, Map.of("id", id, "to", to, "now", utc(now)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
