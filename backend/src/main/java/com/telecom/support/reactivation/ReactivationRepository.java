package com.telecom.support.reactivation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code reactivation_requests} (design 03-inbound backend §2, R13–R15, §3.1). The status-changing statements are
 * guarded ({@code WHERE id = :id AND status IN (:from)}) and called only by {@link ReactivationTransitionService}
 * (single writer), which acts only when exactly one row changed. The reads serve eligibility (R13), create (R14) and
 * the history (I15).
 */
@Repository
class ReactivationRepository {

    /** One request as stored; {@code msisdns} is the snapshot taken at creation. */
    record RequestRow(long id, String reference, long accountId, String suspensionReason, List<String> msisdns,
                      int requestedHours, String reasonText, String approvalReference, long identityCheckId,
                      long requestedByUserId, String status, String failureReason, String endReason,
                      String providerRef, Instant activatedAt, Instant expiresAt, Instant endedAt, int attempts,
                      Instant nextAttemptAt, boolean needsAttention, int version, Instant createdAt) {

        /** Never print the reason text or the MSISDNs. */
        @Override
        public String toString() {
            return "RequestRow[id=" + id + ", " + reference + ", status=" + status + "]";
        }
    }

    /** What R14 tx1 inserts (status PENDING). */
    record NewRequest(String reference, long accountId, String suspensionReason, List<String> msisdns,
                      int requestedHours, String reasonText, String approvalReference, long identityCheckId,
                      long requestedByUserId, Instant nextAttemptAt, Instant now) {}

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, reference, account_id, suspension_reason, CAST(msisdns AS CHAR) AS msisdns, requested_hours,
            reason_text, approval_reference, identity_check_id, requested_by_user_id, status, failure_reason,
            end_reason, provider_ref, activated_at, expires_at, ended_at, attempts, next_attempt_at,
            needs_attention, version, created_at
            """;

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

    private static final String INSERT = """
            INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours,
                                               reason_text, approval_reference, identity_check_id,
                                               requested_by_user_id, status, attempts, next_attempt_at,
                                               needs_attention, version, created_at, updated_at)
            VALUES (:reference, :accountId, :suspensionReason, CAST(:msisdns AS JSON), :requestedHours, :reasonText,
                    :approvalReference, :identityCheckId, :requestedByUserId, 'PENDING', 0, :nextAttemptAt, FALSE, 0,
                    :now, :now)
            """;

    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM reactivation_requests WHERE id = :id";

    private static final String FIND_PAGE = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE account_id = :accountId AND created_at >= :from AND created_at < :to
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_PAGE = """
            SELECT COUNT(*) FROM reactivation_requests
            WHERE account_id = :accountId AND created_at >= :from AND created_at < :to
            """;

    /** §3.1 PENDING → ACTIVE: restore confirmed within the window; clears the retry and attention fields. */
    private static final String ACTIVATE = """
            UPDATE reactivation_requests
            SET status = 'ACTIVE', provider_ref = :providerRef, activated_at = :activatedAt, expires_at = :expiresAt,
                failure_reason = NULL, attempts = 0, next_attempt_at = NULL, needs_attention = FALSE,
                attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /**
     * §3.1 PENDING → FAILED. E2: {@code next_attempt_at} schedules the late-restore re-check; {@code updated_at} is the
     * time it failed (the re-check bookkeeping never touches it), which bounds the re-checks to 24 h.
     */
    private static final String FAIL = """
            UPDATE reactivation_requests
            SET status = 'FAILED', failure_reason = :failureReason, attempts = 0, next_attempt_at = :recheckAt,
                needs_attention = FALSE, attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** E2 reconcile: FAILED requests due for a late-restore re-check, failed at or after {@code :since}, keyset by id. */
    private static final String FIND_DUE_FAILED = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status = 'FAILED' AND next_attempt_at <= :now AND updated_at >= :since AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /**
     * E2: the next re-check of a FAILED request, or none ({@code NULL}) once it would fall after 24 h from the failure.
     * Guarded by the version, so parallel runs count it once; {@code updated_at} (the failure time) is kept.
     */
    private static final String RECHECK_FAILED_LATER = """
            UPDATE reactivation_requests
            SET attempts = attempts + 1, version = version + 1,
                next_attempt_at = IF(:nextAttemptAt <= updated_at + INTERVAL 24 HOUR, :nextAttemptAt, NULL)
            WHERE id = :id AND status = 'FAILED' AND version = :version
            """;

    /** §3.1 restore timeout / 5xx: PENDING stays, the outcome is reconciled from {@code next_attempt_at}. */
    private static final String MARK_OUTCOME_UNKNOWN = """
            UPDATE reactivation_requests
            SET failure_reason = :failureReason, next_attempt_at = :nextAttemptAt, version = version + 1,
                updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** Reconcile job: PENDING requests whose next attempt is due, keyset by id. */
    private static final String FIND_DUE_PENDING = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status = 'PENDING' AND next_attempt_at <= :now AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** Reconcile job: PENDING requests created at or before {@code :cutoff} not yet flagged, keyset by id. */
    private static final String FIND_UNFLAGGED_PENDING = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status = 'PENDING' AND needs_attention = FALSE AND created_at <= :cutoff AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** Expiry job: every ACTIVE request (due, or its account's reason may now be prohibited), keyset by id. */
    private static final String FIND_ACTIVE = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status = 'ACTIVE' AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** Expiry job: EXPIRING requests whose (next) suspend is due, keyset by id. */
    private static final String FIND_DUE_EXPIRING = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status = 'EXPIRING' AND next_attempt_at <= :now AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** Expiry job: restored (ACTIVE/EXPIRING) at or before {@code :cutoff} and not yet alerted, keyset by id. */
    private static final String FIND_OVERDUE = "SELECT " + COLUMNS + """
            FROM reactivation_requests
            WHERE status IN ('ACTIVE', 'EXPIRING') AND overdue_alerted_at IS NULL AND activated_at <= :cutoff
              AND id > :after
            ORDER BY id
            LIMIT :limit
            """;

    /** §3.1 reconcile: RESTORED with the window already over → EXPIRING, suspended by the expiry job next. */
    private static final String RESTORED_LATE = """
            UPDATE reactivation_requests
            SET status = 'EXPIRING', provider_ref = :providerRef, activated_at = :activatedAt, expires_at = :expiresAt,
                end_reason = 'expired', failure_reason = NULL, attempts = 0, next_attempt_at = :now,
                needs_attention = FALSE, attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** §3.1 expiry: ACTIVE → ENDED (account ACTIVE again; no provider call). */
    private static final String END = """
            UPDATE reactivation_requests
            SET status = 'ENDED', end_reason = :endReason, ended_at = :now, next_attempt_at = NULL,
                needs_attention = FALSE, attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** §3.1 expiry claim: ACTIVE → EXPIRING, suspend due now. */
    private static final String START_EXPIRING = """
            UPDATE reactivation_requests
            SET status = 'EXPIRING', end_reason = :endReason, attempts = 0, next_attempt_at = :now,
                needs_attention = FALSE, attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** §3.1 EXPIRING → EXPIRED (suspend confirmed or provider reports SUSPENDED). */
    private static final String EXPIRE = """
            UPDATE reactivation_requests
            SET status = 'EXPIRED', ended_at = :now, next_attempt_at = NULL, needs_attention = FALSE,
                attention_reason = NULL, version = version + 1, updated_at = :now
            WHERE id = :id AND status IN (:from)
            """;

    /** §3.1 retry bookkeeping: guarded by the version read, so parallel runs count one failure once. */
    private static final String RETRY = """
            UPDATE reactivation_requests
            SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt, version = version + 1, updated_at = :now
            WHERE id = :id AND status = :status AND version = :version
            """;

    /** §3.1 alert flag (not a state): set once while the row keeps its status. */
    private static final String FLAG_ATTENTION = """
            UPDATE reactivation_requests
            SET needs_attention = TRUE, attention_reason = :reason, updated_at = :now
            WHERE id = :id AND status = :status AND needs_attention = FALSE
            """;

    /** §3.1 restored past 24 h: alerted once. */
    private static final String MARK_OVERDUE = """
            UPDATE reactivation_requests
            SET overdue_alerted_at = :now, updated_at = :now
            WHERE id = :id AND status IN ('ACTIVE', 'EXPIRING') AND overdue_alerted_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ReactivationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

    /**
     * R14 tx1: inserts a PENDING request and returns its id. A second open request of the account fails on the
     * open-request UK ({@code DuplicateKeyException}).
     */
    long insert(NewRequest request) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("reference", request.reference())
                .addValue("accountId", request.accountId())
                .addValue("suspensionReason", request.suspensionReason())
                .addValue("msisdns", toJson(request.msisdns()))
                .addValue("requestedHours", request.requestedHours())
                .addValue("reasonText", request.reasonText())
                .addValue("approvalReference", request.approvalReference())
                .addValue("identityCheckId", request.identityCheckId())
                .addValue("requestedByUserId", request.requestedByUserId())
                .addValue("nextAttemptAt", utc(request.nextAttemptAt()))
                .addValue("now", utc(request.now())), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    Optional<RequestRow> findById(long id) {
        return jdbc.query(FIND_BY_ID, new MapSqlParameterSource("id", id), (rs, n) -> map(rs)).stream().findFirst();
    }

    /** I15: the account's requests created in {@code [from, to)}, newest first. */
    List<RequestRow> findPage(long accountId, Instant from, Instant to, int limit, long offset) {
        return jdbc.query(FIND_PAGE, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("from", utc(from))
                .addValue("to", utc(to))
                .addValue("limit", limit)
                .addValue("offset", offset), (rs, n) -> map(rs));
    }

    long countPage(long accountId, Instant from, Instant to) {
        return jdbc.queryForObject(COUNT_PAGE, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("from", utc(from))
                .addValue("to", utc(to)), Long.class);
    }

    /** Guarded {@code from → ACTIVE}; true when exactly this row changed. */
    boolean activate(long id, List<String> from, String providerRef, Instant activatedAt, Instant expiresAt,
                     Instant now) {
        return jdbc.update(ACTIVATE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("providerRef", providerRef)
                .addValue("activatedAt", utc(activatedAt))
                .addValue("expiresAt", utc(expiresAt))
                .addValue("now", utc(now))) == 1;
    }

    /** Guarded {@code from → FAILED}; true when exactly this row changed. */
    boolean fail(long id, List<String> from, String failureReason, Instant recheckAt, Instant now) {
        return jdbc.update(FAIL, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("failureReason", failureReason)
                .addValue("recheckAt", utc(recheckAt))
                .addValue("now", utc(now))) == 1;
    }

    List<RequestRow> findDueFailed(Instant now, Instant since, long after, int limit) {
        return page(FIND_DUE_FAILED, new MapSqlParameterSource()
                .addValue("now", utc(now))
                .addValue("since", utc(since)), after, limit);
    }

    /** E2: schedules the next late-restore re-check (none after 24 h); true when this call counted it. */
    boolean recheckFailedLater(long id, int version, Instant nextAttemptAt) {
        return jdbc.update(RECHECK_FAILED_LATER, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("version", version)
                .addValue("nextAttemptAt", utc(nextAttemptAt))) == 1;
    }

    /** Guarded: the status stays, the outcome is reconciled at {@code nextAttemptAt}; true when this row changed. */
    boolean markOutcomeUnknown(long id, List<String> from, String failureReason, Instant nextAttemptAt, Instant now) {
        return jdbc.update(MARK_OUTCOME_UNKNOWN, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("failureReason", failureReason)
                .addValue("nextAttemptAt", utc(nextAttemptAt))
                .addValue("now", utc(now))) == 1;
    }

    List<RequestRow> findDuePending(Instant now, long after, int limit) {
        return page(FIND_DUE_PENDING, new MapSqlParameterSource("now", utc(now)), after, limit);
    }

    List<RequestRow> findUnflaggedPending(Instant cutoff, long after, int limit) {
        return page(FIND_UNFLAGGED_PENDING, new MapSqlParameterSource("cutoff", utc(cutoff)), after, limit);
    }

    List<RequestRow> findActive(long after, int limit) {
        return page(FIND_ACTIVE, new MapSqlParameterSource(), after, limit);
    }

    List<RequestRow> findDueExpiring(Instant now, long after, int limit) {
        return page(FIND_DUE_EXPIRING, new MapSqlParameterSource("now", utc(now)), after, limit);
    }

    List<RequestRow> findOverdue(Instant cutoff, long after, int limit) {
        return page(FIND_OVERDUE, new MapSqlParameterSource("cutoff", utc(cutoff)), after, limit);
    }

    /** Guarded {@code from → EXPIRING} with the restore times; true when exactly this row changed. */
    boolean restoredLate(long id, List<String> from, String providerRef, Instant activatedAt, Instant expiresAt,
                         Instant now) {
        return jdbc.update(RESTORED_LATE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("providerRef", providerRef)
                .addValue("activatedAt", utc(activatedAt))
                .addValue("expiresAt", utc(expiresAt))
                .addValue("now", utc(now))) == 1;
    }

    /** Guarded {@code from → ENDED}; true when exactly this row changed. */
    boolean end(long id, List<String> from, String endReason, Instant now) {
        return jdbc.update(END, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("endReason", endReason)
                .addValue("now", utc(now))) == 1;
    }

    /** Guarded {@code from → EXPIRING}; true when exactly this row changed. */
    boolean startExpiring(long id, List<String> from, String endReason, Instant now) {
        return jdbc.update(START_EXPIRING, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("endReason", endReason)
                .addValue("now", utc(now))) == 1;
    }

    /** Guarded {@code from → EXPIRED}; true when exactly this row changed. */
    boolean expire(long id, List<String> from, Instant now) {
        return jdbc.update(EXPIRE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("from", from)
                .addValue("now", utc(now))) == 1;
    }

    /** One failed attempt ({@code attempts + 1}, next at {@code nextAttemptAt}); true when this call counted it. */
    boolean retry(long id, String status, int version, Instant nextAttemptAt, Instant now) {
        return jdbc.update(RETRY, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("version", version)
                .addValue("nextAttemptAt", utc(nextAttemptAt))
                .addValue("now", utc(now))) == 1;
    }

    /** Sets {@code needs_attention} once while the row is in {@code status}; true when this call set it. */
    boolean flagAttention(long id, String status, String reason, Instant now) {
        return jdbc.update(FLAG_ATTENTION, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("reason", reason)
                .addValue("now", utc(now))) == 1;
    }

    /** Sets {@code overdue_alerted_at} once; true when this call set it. */
    boolean markOverdue(long id, Instant now) {
        return jdbc.update(MARK_OVERDUE, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", utc(now))) == 1;
    }

    private List<RequestRow> page(String sql, MapSqlParameterSource params, long after, int limit) {
        return jdbc.query(sql, params.addValue("after", after).addValue("limit", limit), (rs, n) -> map(rs));
    }

    private RequestRow map(ResultSet rs) throws SQLException {
        return new RequestRow(rs.getLong("id"), rs.getString("reference"), rs.getLong("account_id"),
                rs.getString("suspension_reason"), fromJson(rs.getString("msisdns")), rs.getInt("requested_hours"),
                rs.getString("reason_text"), rs.getString("approval_reference"), rs.getLong("identity_check_id"),
                rs.getLong("requested_by_user_id"), rs.getString("status"), rs.getString("failure_reason"),
                rs.getString("end_reason"), rs.getString("provider_ref"), instant(rs, "activated_at"),
                instant(rs, "expires_at"), instant(rs, "ended_at"), rs.getInt("attempts"),
                instant(rs, "next_attempt_at"), rs.getBoolean("needs_attention"), rs.getInt("version"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("MSISDN list is not serialisable", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRINGS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored msisdns is not a JSON array of strings", e);
        }
    }

    /** UTC, truncated to the column's microseconds. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
