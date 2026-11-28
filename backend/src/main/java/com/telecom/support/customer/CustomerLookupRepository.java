package com.telecom.support.customer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Customer search and {@code customer_lookups} (design 03-inbound backend §2, R1, R2). Only the SHA-256 of a lookup token
 * is stored; a token is valid for the searching agent and the found customer until {@code expires_at}.
 */
@Repository
class CustomerLookupRepository {

    /** One search hit: the live subscription with that MSISDN and its customer/account state. */
    record MatchRow(long customerId, String customerRef, String fullName, String msisdn, String subscriptionStatus,
                    String customerStatus, String accountStatus) {}

    /** R1: exact E.164 on {@code live_msisdn} (TERMINATED subscriptions have none); unique, so 0 or 1 row. */
    private static final String FIND_BY_LIVE_MSISDN = """
            SELECT c.id, c.customer_ref, c.full_name, s.msisdn, s.status AS subscription_status,
                   c.status AS customer_status, a.status AS account_status
            FROM subscriptions s
            JOIN accounts a ON a.id = s.account_id
            JOIN customers c ON c.id = a.customer_id
            WHERE s.live_msisdn = :msisdn
            """;

    private static final String INSERT = """
            INSERT INTO customer_lookups (agent_user_id, customer_id, token_hash, expires_at, created_at)
            VALUES (:agentUserId, :customerId, :tokenHash, :expiresAt, :now)
            """;

    /** R2: the token of a hit of this agent for this customer, not yet expired. */
    private static final String VALID_TOKEN = """
            SELECT COUNT(*) FROM customer_lookups
            WHERE token_hash = :tokenHash AND agent_user_id = :agentUserId AND customer_id = :customerId
              AND expires_at > :now
            """;

    /** The end of this agent's valid VERIFIED check for the customer (R5 window), if any. */
    private static final String VERIFIED_UNTIL = """
            SELECT MAX(valid_until) FROM identity_checks
            WHERE agent_user_id = :agentUserId AND customer_id = :customerId AND status = 'VERIFIED'
              AND valid_until > :now
            """;

    private static final String PURGE_CREATED_BEFORE = """
            DELETE FROM customer_lookups WHERE created_at < :cutoff
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CustomerLookupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MatchRow> findByLiveMsisdn(String msisdn) {
        return jdbc.query(FIND_BY_LIVE_MSISDN, Map.of("msisdn", msisdn), (rs, i) -> new MatchRow(
                        rs.getLong("id"), rs.getString("customer_ref"), rs.getString("full_name"),
                        rs.getString("msisdn"), rs.getString("subscription_status"), rs.getString("customer_status"),
                        rs.getString("account_status")))
                .stream()
                .findFirst();
    }

    void insert(long agentUserId, long customerId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("agentUserId", agentUserId)
                .addValue("customerId", customerId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", utc(expiresAt))
                .addValue("now", utc(now)));
    }

    boolean isValid(String tokenHash, long agentUserId, long customerId, Instant now) {
        Long n = jdbc.queryForObject(VALID_TOKEN, Map.of("tokenHash", tokenHash, "agentUserId", agentUserId,
                "customerId", customerId, "now", utc(now)), Long.class);
        return n != null && n > 0;
    }

    Optional<Instant> verifiedUntil(long agentUserId, long customerId, Instant now) {
        // Read the UTC DATETIME as-is (00-overview: UTC DATETIME(6)); a Timestamp would shift it by the JVM zone.
        LocalDateTime until = jdbc.queryForObject(VERIFIED_UNTIL, Map.of("agentUserId", agentUserId,
                "customerId", customerId, "now", utc(now)), LocalDateTime.class);
        return Optional.ofNullable(until).map(t -> t.toInstant(ZoneOffset.UTC));
    }

    /** Deletes lookups created before {@code cutoff}; returns the number removed. */
    int purgeCreatedBefore(Instant cutoff) {
        return jdbc.update(PURGE_CREATED_BEFORE, Map.of("cutoff", utc(cutoff)));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
