package com.telecom.support.common.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** SQL for {@code idempotency_keys} (01 backend §2, §6.1). Times are UTC {@code DATETIME(6)}. */
@Repository
class IdempotencyRepository {

    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String COMPLETED = "COMPLETED";

    /** One stored key row. {@code responseBody} is the stored JSON text, or null. */
    record StoredKey(long id, String requestHash, String status, Integer responseStatus, String responseBody,
                     Instant createdAt) {}

    private static final String INSERT = """
            INSERT INTO idempotency_keys (user_id, operation, idem_key, request_hash, status, created_at)
            VALUES (:userId, :operation, :key, :hash, 'IN_PROGRESS', :createdAt)
            """;
    private static final String FIND = """
            SELECT id, request_hash, status, response_status, response_body, created_at
            FROM idempotency_keys
            WHERE user_id = :userId AND operation = :operation AND idem_key = :key
            """;
    private static final String DELETE_STALE = """
            DELETE FROM idempotency_keys
            WHERE id = :id AND status = 'IN_PROGRESS' AND created_at < :cutoff
            """;
    private static final String COMPLETE = """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = :responseStatus, response_body = CAST(:responseBody AS JSON)
            WHERE id = :id AND status = 'IN_PROGRESS'
            """;
    private static final String RELEASE = """
            DELETE FROM idempotency_keys WHERE id = :id AND status = 'IN_PROGRESS'
            """;
    private static final String PURGE = """
            DELETE FROM idempotency_keys WHERE created_at < :cutoff
            """;

    private final NamedParameterJdbcTemplate jdbc;

    IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts an IN_PROGRESS row and returns its id; a duplicate (user, operation, key) throws DuplicateKeyException. */
    long insertInProgress(long userId, String operation, String key, String hash, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("operation", operation)
                .addValue("key", key)
                .addValue("hash", hash)
                .addValue("createdAt", utc(createdAt)), keys, new String[] {"id"});
        return Objects.requireNonNull(keys.getKey(), "generated id").longValue();
    }

    Optional<StoredKey> find(long userId, String operation, String key) {
        return jdbc.query(FIND, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("operation", operation)
                        .addValue("key", key), IdempotencyRepository::map)
                .stream().findFirst();
    }

    /** Deletes the row only if it is still IN_PROGRESS and was created before {@code cutoff}. */
    boolean deleteStale(long id, Instant cutoff) {
        return jdbc.update(DELETE_STALE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("cutoff", utc(cutoff))) == 1;
    }

    void complete(long id, int responseStatus, String responseBody) {
        jdbc.update(COMPLETE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("responseStatus", responseStatus)
                .addValue("responseBody", responseBody));
    }

    void release(long id) {
        jdbc.update(RELEASE, new MapSqlParameterSource("id", id));
    }

    int purgeCreatedBefore(Instant cutoff) {
        return jdbc.update(PURGE, new MapSqlParameterSource("cutoff", utc(cutoff)));
    }

    private static StoredKey map(ResultSet rs, int row) throws SQLException {
        int status = rs.getInt("response_status");
        Integer responseStatus = rs.wasNull() ? null : status;
        return new StoredKey(rs.getLong("id"), rs.getString("request_hash"), rs.getString("status"), responseStatus,
                rs.getString("response_body"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
