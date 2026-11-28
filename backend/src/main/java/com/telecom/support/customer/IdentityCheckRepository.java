package com.telecom.support.customer;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * {@code identity_checks} (design 03-inbound backend §2, R3, R4). Every state change is one guarded statement, so parallel
 * requests cannot both win: an OTP attempt, LOCKED and VERIFIED require {@code status='PENDING'}; a KBA verdict requires
 * {@code status='CHECKING'}. Only the HMAC of an OTP code is stored; KBA answers are never stored.
 */
@Repository
class IdentityCheckRepository {

    static final String OTP = "OTP";
    static final String KBA = "KBA";
    static final String PENDING = "PENDING";
    static final String CHECKING = "CHECKING";
    static final String VERIFIED = "VERIFIED";
    static final String FAILED = "FAILED";
    static final String EXPIRED = "EXPIRED";
    static final String LOCKED = "LOCKED";

    /** One check; {@code codeHmac} is the hex HMAC-SHA256 of the OTP code (null for KBA). */
    record CheckRow(long id, long agentUserId, long customerId, String method, String status, String codeHmac,
                    int attempts, Instant expiresAt, Instant validUntil) {}

    /**
     * What an identity check needs about a customer: the primary MSISDN (primary subscription, not TERMINATED; null
     * when none), and the KBA references (billing postal code, verified email / secondary MSISDN, else null).
     */
    record CustomerFacts(long customerId, String customerRef, String primaryMsisdn, String postalCode,
                         String verifiedEmail, String verifiedSecondaryMsisdn) {

        /** Never print contact data or KBA references. */
        @Override
        public String toString() {
            return "CustomerFacts[customerRef=" + customerRef + "]";
        }
    }

    /** Rows of a rolling window and the oldest of them (empty when none). */
    record Window(int count, Optional<Instant> oldest) {}

    private static final String FIND_FACTS = """
            SELECT c.id, c.customer_ref, s.msisdn AS primary_msisdn,
                   JSON_UNQUOTE(JSON_EXTRACT(c.billing_address, '$.postalCode')) AS postal_code,
                   CASE WHEN c.email_verified_at IS NOT NULL THEN c.email END AS verified_email,
                   CASE WHEN c.secondary_verified_at IS NOT NULL THEN c.secondary_msisdn END AS verified_secondary
            FROM customers c
            JOIN accounts a ON a.customer_id = c.id
            LEFT JOIN subscriptions s ON s.account_id = a.id AND s.is_primary = TRUE AND s.status <> 'TERMINATED'
            WHERE c.id = :customerId
            """;

    /** R3: OTP starts of the customer by any agent (every status). */
    private static final String OTP_STARTS_SINCE = """
            SELECT COUNT(*) AS n, MIN(created_at) AS oldest
            FROM identity_checks
            WHERE customer_id = :customerId AND method = 'OTP' AND created_at > :since
            """;

    /** R4: KBA attempts of the customer that were not verified (CHECKING counts: a crash is a failure). */
    private static final String KBA_ATTEMPTS_OF_CUSTOMER_SINCE = """
            SELECT COUNT(*) AS n, MIN(created_at) AS oldest
            FROM identity_checks
            WHERE customer_id = :customerId AND method = 'KBA' AND status IN ('CHECKING', 'FAILED')
              AND created_at > :since
            """;

    /** R4: KBA attempts of the agent (all customers) that were not verified. */
    private static final String KBA_ATTEMPTS_OF_AGENT_SINCE = """
            SELECT COUNT(*) AS n, MIN(created_at) AS oldest
            FROM identity_checks
            WHERE agent_user_id = :agentUserId AND method = 'KBA' AND status IN ('CHECKING', 'FAILED')
              AND created_at > :since
            """;

    /** A new start (OTP) or a new verification supersedes this agent's other open checks for the customer. */
    private static final String SUPERSEDE_OPEN = """
            UPDATE identity_checks
            SET status = 'SUPERSEDED', updated_at = :now
            WHERE agent_user_id = :agentUserId AND customer_id = :customerId AND status IN ('PENDING', 'VERIFIED')
              AND id <> :keepId
            """;

    private static final String INSERT_OTP = """
            INSERT INTO identity_checks (agent_user_id, customer_id, method, status, code_hmac, attempts, expires_at,
                                         created_at, updated_at)
            VALUES (:agentUserId, :customerId, 'OTP', 'PENDING', :codeHmac, 0, :expiresAt, :now, :now)
            """;

    /** R4 tx A: the attempt exists (and counts) before any answer is compared. */
    private static final String INSERT_KBA_CHECKING = """
            INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, created_at, updated_at)
            VALUES (:agentUserId, :customerId, 'KBA', 'CHECKING', 1, :now, :now)
            """;

    /** 02 §3.2 step 1: counts one OTP attempt of this agent's check while it is still usable. */
    private static final String COUNT_OTP_ATTEMPT = """
            UPDATE identity_checks
            SET attempts = attempts + 1, updated_at = :now
            WHERE id = :id AND agent_user_id = :agentUserId AND customer_id = :customerId AND method = 'OTP'
              AND status = 'PENDING' AND attempts < :maxAttempts AND expires_at > :now
            """;

    private static final String COLUMNS = """
            id, agent_user_id, customer_id, method, status, code_hmac, attempts, expires_at, valid_until
            """;

    private static final String FIND = "SELECT " + COLUMNS + " FROM identity_checks WHERE id = :id";

    /** I4 / R5: this agent's valid VERIFIED check for the customer. */
    private static final String FIND_CURRENT = "SELECT " + COLUMNS + """
             FROM identity_checks
            WHERE agent_user_id = :agentUserId AND customer_id = :customerId AND status = 'VERIFIED'
              AND valid_until > :now
            ORDER BY valid_until DESC, id DESC
            LIMIT 1
            """;

    /** R5: this agent's valid VERIFIED check for the customer, with the customer's account. */
    private static final String FIND_VERIFIED = """
            SELECT ic.id, a.id AS account_id
            FROM identity_checks ic
            JOIN accounts a ON a.customer_id = ic.customer_id
            WHERE ic.agent_user_id = :agentUserId AND ic.customer_id = :customerId AND ic.status = 'VERIFIED'
              AND ic.valid_until > :now
            ORDER BY ic.valid_until DESC, ic.id DESC
            LIMIT 1
            """;

    /** R6: the first protected read under a check wins this guarded update (exactly once, also in parallel). */
    private static final String MARK_ACCESS_LOGGED = """
            UPDATE identity_checks
            SET access_logged_at = :now
            WHERE id = :id AND access_logged_at IS NULL
            """;

    /** Guarded {@code :from} → {@code :to} (LOCKED, EXPIRED, FAILED). */
    private static final String TRANSITION = """
            UPDATE identity_checks
            SET status = :to, updated_at = :now
            WHERE id = :id AND status = :from
            """;

    /** Guarded {@code :from} (PENDING or CHECKING) → VERIFIED with its validity window. */
    private static final String MARK_VERIFIED = """
            UPDATE identity_checks
            SET status = 'VERIFIED', verified_at = :now, valid_until = :validUntil, updated_at = :now
            WHERE id = :id AND status = :from
            """;

    /** Cleanup job: OTP codes past their expiry. */
    private static final String EXPIRE_PENDING = """
            UPDATE identity_checks
            SET status = 'EXPIRED', updated_at = :now
            WHERE status = 'PENDING' AND expires_at <= :now
            """;

    /**
     * §3.1 lock order (E1, review C6): the agent's {@code users} row in share mode, taken before any customer row, so
     * the later {@code identity_checks} FK check never waits on {@code users} while the customer row is held.
     */
    private static final String LOCK_AGENT_SHARED = """
            SELECT id FROM users WHERE id = :agentUserId FOR SHARE
            """;

    /** E1: creates the agent's hour row if missing; on a duplicate this also X-locks the existing row. */
    private static final String UPSERT_KBA_WINDOW = """
            INSERT INTO agent_kba_windows (agent_user_id, window_start, attempts)
            VALUES (:agentUserId, :windowStart, 0)
            ON DUPLICATE KEY UPDATE attempts = attempts
            """;

    private static final String LOCK_KBA_WINDOW = """
            SELECT attempts FROM agent_kba_windows
            WHERE agent_user_id = :agentUserId AND window_start = :windowStart
            FOR UPDATE
            """;

    private static final String COUNT_KBA_WINDOW = """
            UPDATE agent_kba_windows SET attempts = attempts + 1
            WHERE agent_user_id = :agentUserId AND window_start = :windowStart
            """;

    private final NamedParameterJdbcTemplate jdbc;

    IdentityCheckRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Share-locks the agent's {@code users} row (agent-scoped, first in the §3.1 lock order). */
    void lockAgentShared(long agentUserId) {
        jdbc.queryForList(LOCK_AGENT_SHARED, Map.of("agentUserId", agentUserId), Long.class);
    }

    /** E1: upserts and X-locks the agent's KBA row for the hour starting {@code windowStart}. */
    void lockKbaWindow(long agentUserId, Instant windowStart) {
        Map<String, Object> params = Map.of("agentUserId", agentUserId, "windowStart", utc(windowStart));
        jdbc.update(UPSERT_KBA_WINDOW, params);
        jdbc.queryForList(LOCK_KBA_WINDOW, params, Integer.class);
    }

    /** E1: counts one admitted KBA attempt in the agent's (already locked) hour row. */
    void countKbaWindow(long agentUserId, Instant windowStart) {
        jdbc.update(COUNT_KBA_WINDOW, Map.of("agentUserId", agentUserId, "windowStart", utc(windowStart)));
    }

    Optional<CustomerFacts> findFacts(long customerId) {
        return jdbc.query(FIND_FACTS, Map.of("customerId", customerId), (rs, i) -> new CustomerFacts(
                        rs.getLong("id"), rs.getString("customer_ref"), rs.getString("primary_msisdn"),
                        rs.getString("postal_code"), rs.getString("verified_email"),
                        rs.getString("verified_secondary")))
                .stream()
                .findFirst();
    }

    Window otpStartsSince(long customerId, Instant since) {
        return window(OTP_STARTS_SINCE, Map.of("customerId", customerId, "since", utc(since)));
    }

    Window kbaAttemptsOfCustomerSince(long customerId, Instant since) {
        return window(KBA_ATTEMPTS_OF_CUSTOMER_SINCE, Map.of("customerId", customerId, "since", utc(since)));
    }

    Window kbaAttemptsOfAgentSince(long agentUserId, Instant since) {
        return window(KBA_ATTEMPTS_OF_AGENT_SINCE, Map.of("agentUserId", agentUserId, "since", utc(since)));
    }

    /** Supersedes this agent's PENDING/VERIFIED checks for the customer except {@code keepId} (−1 = none kept). */
    int supersedeOpen(long agentUserId, long customerId, long keepId, Instant now) {
        return jdbc.update(SUPERSEDE_OPEN, Map.of("agentUserId", agentUserId, "customerId", customerId,
                "keepId", keepId, "now", utc(now)));
    }

    long insertOtp(long agentUserId, long customerId, String codeHmac, Instant expiresAt, Instant now) {
        return insert(INSERT_OTP, new MapSqlParameterSource()
                .addValue("agentUserId", agentUserId)
                .addValue("customerId", customerId)
                .addValue("codeHmac", codeHmac)
                .addValue("expiresAt", utc(expiresAt))
                .addValue("now", utc(now)));
    }

    long insertKbaChecking(long agentUserId, long customerId, Instant now) {
        return insert(INSERT_KBA_CHECKING, new MapSqlParameterSource()
                .addValue("agentUserId", agentUserId)
                .addValue("customerId", customerId)
                .addValue("now", utc(now)));
    }

    /** 1 when the attempt was counted; 0 when the check is absent, not this agent's/customer's, or unusable. */
    int countOtpAttempt(long id, long agentUserId, long customerId, int maxAttempts, Instant now) {
        return jdbc.update(COUNT_OTP_ATTEMPT, Map.of("id", id, "agentUserId", agentUserId, "customerId", customerId,
                "maxAttempts", maxAttempts, "now", utc(now)));
    }

    Optional<CheckRow> find(long id) {
        return jdbc.query(FIND, Map.of("id", id), (rs, i) -> row(rs)).stream().findFirst();
    }

    Optional<CheckRow> findCurrent(long agentUserId, long customerId, Instant now) {
        return jdbc.query(FIND_CURRENT, Map.of("agentUserId", agentUserId, "customerId", customerId, "now", utc(now)),
                (rs, i) -> row(rs)).stream().findFirst();
    }

    /** R5: (check id, account id) of this agent's valid VERIFIED check for the customer. */
    record VerifiedRow(long checkId, long accountId) {}

    Optional<VerifiedRow> findVerified(long agentUserId, long customerId, Instant now) {
        return jdbc.query(FIND_VERIFIED, Map.of("agentUserId", agentUserId, "customerId", customerId, "now", utc(now)),
                (rs, i) -> new VerifiedRow(rs.getLong("id"), rs.getLong("account_id"))).stream().findFirst();
    }

    /** 1 when this call logged the first access of the check; 0 when it was already logged. */
    int markAccessLogged(long id, Instant now) {
        return jdbc.update(MARK_ACCESS_LOGGED, Map.of("id", id, "now", utc(now)));
    }

    int transition(long id, String from, String to, Instant now) {
        return jdbc.update(TRANSITION, Map.of("id", id, "from", from, "to", to, "now", utc(now)));
    }

    int markVerified(long id, String from, Instant validUntil, Instant now) {
        return jdbc.update(MARK_VERIFIED, Map.of("id", id, "from", from, "validUntil", utc(validUntil),
                "now", utc(now)));
    }

    int expirePending(Instant now) {
        return jdbc.update(EXPIRE_PENDING, Map.of("now", utc(now)));
    }

    private Window window(String sql, Map<String, ?> params) {
        return jdbc.queryForObject(sql, params, (rs, i) -> new Window(rs.getInt("n"),
                Optional.ofNullable(instant(rs, "oldest"))));
    }

    private long insert(String sql, MapSqlParameterSource params) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(sql, params, keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    private static CheckRow row(ResultSet rs) throws SQLException {
        return new CheckRow(rs.getLong("id"), rs.getLong("agent_user_id"), rs.getLong("customer_id"),
                rs.getString("method"), rs.getString("status"), rs.getString("code_hmac"), rs.getInt("attempts"),
                instant(rs, "expires_at"), instant(rs, "valid_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        // The column is a UTC DATETIME(6): read it as-is; a Timestamp would shift it by the JVM zone.
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
