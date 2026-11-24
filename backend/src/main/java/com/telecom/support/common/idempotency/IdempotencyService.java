package com.telecom.support.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.error.Errors.BadRequestException;
import com.telecom.support.common.error.Errors.BusinessRuleException;
import com.telecom.support.common.error.Errors.ConflictException;
import com.telecom.support.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retry-safe execution for create / payment / reactivation (NFR-REL, CUS-06.6, CUS-08.4).
 *
 * <p>The key row is inserted in the same transaction as the business change. A concurrent duplicate blocks on the
 * unique index until the first transaction finishes, then replays the stored response. If the first attempt fails
 * and rolls back, the key disappears with it and the client may retry. Same key + different payload → 422.
 */
@Service
public class IdempotencyService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private static final String INSERT = """
            INSERT INTO idempotency_keys (user_id, idem_key, operation, request_hash, status)
            VALUES (:userId, :key, :operation, :hash, 'IN_PROGRESS')
            """;
    private static final String FIND_FOR_UPDATE = """
            SELECT request_hash, status, response_body FROM idempotency_keys
            WHERE user_id = :userId AND operation = :operation AND idem_key = :key
            FOR UPDATE
            """;
    private static final String COMPLETE = """
            UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 200, response_body = CAST(:body AS JSON)
            WHERE user_id = :userId AND operation = :operation AND idem_key = :key
            """;

    private record Stored(String hash, String status, String body) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(String operation, String key, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-100 characters [A-Za-z0-9_-]");
        }
        long userId = CurrentUser.require().id();
        String hash = sha256(toJson(request));
        var params = new MapSqlParameterSource().addValue("userId", userId).addValue("key", key)
                .addValue("operation", operation).addValue("hash", hash);
        try {
            jdbc.update(INSERT, params);
        } catch (DuplicateKeyException duplicate) {
            return replay(params, hash, responseType);
        }
        T result = action.get();
        jdbc.update(COMPLETE, params.addValue("body", toJson(result)));
        return result;
    }

    private <T> T replay(MapSqlParameterSource params, String hash, Class<T> responseType) {
        Stored stored = Optional.ofNullable(jdbc.query(FIND_FOR_UPDATE, params,
                        (rs, i) -> new Stored(rs.getString("request_hash"), rs.getString("status"), rs.getString("response_body"))))
                .flatMap(l -> l.stream().findFirst())
                .orElseThrow(() -> new ConflictException("REQUEST_IN_PROGRESS", "The original request is still being processed"));
        if (!stored.hash().equals(hash)) {
            throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request");
        }
        if (!"COMPLETED".equals(stored.status()) || stored.body() == null) {
            throw new ConflictException("REQUEST_IN_PROGRESS", "The original request is still being processed");
        }
        try {
            return objectMapper.readValue(stored.body(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value not serialisable", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
