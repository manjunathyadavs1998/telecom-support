package com.telecom.support.notification;

import com.telecom.support.notification.NotificationSender.Channel;
import com.telecom.support.notification.NotificationSender.Template;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notifications} outbox (design 03-inbound backend §2, R16). Every state change is one guarded statement: a run
 * owns a row only when its claim {@code PENDING → SENDING} changed it, and only the owner (same {@code claimed_at})
 * completes it, so parallel runs and instances never send a row twice (a stale SENDING reset is at-least-once).
 */
@Repository
class NotificationRepository {

    /** A row ready to be claimed; {@code paramsJson} is the stored JSON object. */
    record Due(long id, Channel channel, Template template, String destination, String paramsJson, String sourceRef,
               int attempts) {

        /** Never print the destination. */
        @Override
        public String toString() {
            return "Due[id=" + id + ", " + template + "/" + channel + ", sourceRef=" + sourceRef + "]";
        }
    }

    /** A repeated (source_ref, template, channel) hits the UK and adds nothing (idempotent enqueue). */
    private static final String INSERT = """
            INSERT INTO notifications (customer_id, channel, template, destination, params, source_ref, status, attempts,
                                       next_attempt_at, created_at, updated_at)
            VALUES (:customerId, :channel, :template, :destination, CAST(:params AS JSON), :sourceRef, 'PENDING', 0,
                    :now, :now, :now)
            """;

    private static final String FIND_DUE = """
            SELECT id, channel, template, destination, CAST(params AS CHAR) AS params, source_ref, attempts
            FROM notifications
            WHERE status = 'PENDING' AND next_attempt_at <= :now AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    private static final String CLAIM = """
            UPDATE notifications
            SET status = 'SENDING', claimed_at = :now, updated_at = :now
            WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= :now
            """;

    private static final String MARK_SENT = """
            UPDATE notifications
            SET status = 'SENT', sent_at = :now, destination = :masked, claimed_at = NULL, last_error = NULL,
                updated_at = :now
            WHERE id = :id AND status = 'SENDING' AND claimed_at = :claimedAt
            """;

    private static final String RETRY_LATER = """
            UPDATE notifications
            SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = :nextAttemptAt, claimed_at = NULL,
                last_error = :error, updated_at = :now
            WHERE id = :id AND status = 'SENDING' AND claimed_at = :claimedAt
            """;

    private static final String MARK_FAILED = """
            UPDATE notifications
            SET status = 'FAILED', attempts = attempts + 1, destination = :masked, claimed_at = NULL, last_error = :error,
                updated_at = :now
            WHERE id = :id AND status = 'SENDING' AND claimed_at = :claimedAt
            """;

    private static final String RESET_STALE = """
            UPDATE notifications
            SET status = 'PENDING', claimed_at = NULL, updated_at = :now
            WHERE status = 'SENDING' AND claimed_at < :claimedBefore
            """;

    private final NamedParameterJdbcTemplate jdbc;

    NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 1 when a row was added, 0 when (source_ref, template, channel) already exists */
    int insert(long customerId, Channel channel, Template template, String destination, String paramsJson,
               String sourceRef, Instant now) {
        try {
            return jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("customerId", customerId)
                    .addValue("channel", channel.name())
                    .addValue("template", template.name())
                    .addValue("destination", destination)
                    .addValue("params", paramsJson)
                    .addValue("sourceRef", sourceRef)
                    .addValue("now", utc(now)));
        } catch (DuplicateKeyException alreadyQueued) {
            return 0; // MySQL rolls back only the statement; the caller's transaction goes on
        }
    }

    /** PENDING rows due at {@code now} with an id above {@code after}, oldest first (keyset page). */
    List<Due> findDue(Instant now, long after, int limit) {
        return jdbc.query(FIND_DUE, new MapSqlParameterSource()
                .addValue("now", utc(now))
                .addValue("after", after)
                .addValue("limit", limit), (rs, n) -> due(rs));
    }

    /** {@code PENDING → SENDING}; true when this caller now owns the row. */
    boolean claim(long id, Instant now) {
        return jdbc.update(CLAIM, new MapSqlParameterSource().addValue("id", id).addValue("now", utc(now))) == 1;
    }

    boolean markSent(long id, Instant claimedAt, String maskedDestination, Instant now) {
        return jdbc.update(MARK_SENT, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("claimedAt", utc(claimedAt))
                .addValue("masked", maskedDestination)
                .addValue("now", utc(now))) == 1;
    }

    boolean retryLater(long id, Instant claimedAt, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.update(RETRY_LATER, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("claimedAt", utc(claimedAt))
                .addValue("nextAttemptAt", utc(nextAttemptAt))
                .addValue("error", error)
                .addValue("now", utc(now))) == 1;
    }

    boolean markFailed(long id, Instant claimedAt, String maskedDestination, String error, Instant now) {
        return jdbc.update(MARK_FAILED, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("claimedAt", utc(claimedAt))
                .addValue("masked", maskedDestination)
                .addValue("error", error)
                .addValue("now", utc(now))) == 1;
    }

    /** SENDING rows claimed before {@code claimedBefore} (a crashed run) become PENDING again. */
    int resetStale(Instant claimedBefore, Instant now) {
        return jdbc.update(RESET_STALE, new MapSqlParameterSource()
                .addValue("claimedBefore", utc(claimedBefore))
                .addValue("now", utc(now)));
    }

    private static Due due(ResultSet rs) throws SQLException {
        return new Due(rs.getLong("id"), Channel.valueOf(rs.getString("channel")),
                Template.valueOf(rs.getString("template")), rs.getString("destination"), rs.getString("params"),
                rs.getString("source_ref"), rs.getInt("attempts"));
    }

    /** UTC, truncated to the column's microseconds so a stored {@code claimed_at} compares equal to its claim time. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
