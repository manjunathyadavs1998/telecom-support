package com.telecom.support.customer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code customers} with the account reference and primary MSISDN for the profile (design 02-customer backend R2). */
@Repository
class CustomerRepository {

    /** One profile row; {@code billingAddressJson} is the raw {@code billing_address} JSON. */
    record ProfileRow(long id, String customerRef, String accountRef, String fullName, String aliasName,
                      String primaryMsisdn, String email, boolean emailVerified, String secondaryMsisdn,
                      boolean secondaryMsisdnVerified, String billingAddressJson, int version) {}

    /** The primary MSISDN is derived from the primary subscription (§2, at most one per account by UK). */
    private static final String FIND_PROFILE = """
            SELECT c.id, c.customer_ref, a.account_ref, c.full_name, c.alias_name, s.msisdn AS primary_msisdn,
                   c.email, c.email_verified_at, c.secondary_msisdn, c.secondary_verified_at,
                   c.billing_address, c.version
            FROM customers c
            JOIN accounts a ON a.customer_id = c.id
            LEFT JOIN subscriptions s ON s.account_id = a.id AND s.is_primary = TRUE
            WHERE c.id = :customerId
            """;

    /** Optimistic lock (R2): no row updated means the version is stale. */
    private static final String UPDATE_ALIAS = """
            UPDATE customers
            SET alias_name = :aliasName, version = version + 1, updated_at = :updatedAt
            WHERE id = :customerId AND version = :version
            """;

    /** Serialises contact verification starts of one customer (R3 rate limit, supersede). */
    private static final String LOCK_CUSTOMER = """
            SELECT id FROM customers WHERE id = :customerId FOR UPDATE
            """;

    /** R3: a verified email replaces the current one (always lower-cased by the caller). */
    private static final String UPDATE_VERIFIED_EMAIL = """
            UPDATE customers
            SET email = :value, email_verified_at = :verifiedAt, version = version + 1, updated_at = :verifiedAt
            WHERE id = :customerId
            """;

    /** R3: a verified secondary MSISDN replaces the current one. */
    private static final String UPDATE_VERIFIED_SECONDARY_MSISDN = """
            UPDATE customers
            SET secondary_msisdn = :value, secondary_verified_at = :verifiedAt, version = version + 1,
                updated_at = :verifiedAt
            WHERE id = :customerId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    CustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ProfileRow> findProfile(long customerId) {
        return jdbc.query(FIND_PROFILE, Map.of("customerId", customerId), (rs, i) -> new ProfileRow(
                        rs.getLong("id"), rs.getString("customer_ref"), rs.getString("account_ref"),
                        rs.getString("full_name"), rs.getString("alias_name"), rs.getString("primary_msisdn"),
                        rs.getString("email"), rs.getObject("email_verified_at", Timestamp.class) != null,
                        rs.getString("secondary_msisdn"), rs.getObject("secondary_verified_at", Timestamp.class) != null,
                        rs.getString("billing_address"), rs.getInt("version")))
                .stream()
                .findFirst();
    }

    /** Locks the customer row until the end of the current transaction; false when it does not exist. */
    boolean lockForUpdate(long customerId) {
        return !jdbc.queryForList(LOCK_CUSTOMER, Map.of("customerId", customerId), Long.class).isEmpty();
    }

    /** Writes a verified email ({@code emailChannel}) or secondary MSISDN; returns the number of rows updated. */
    int updateVerifiedContact(long customerId, boolean emailChannel, String value, Instant verifiedAt) {
        return jdbc.update(emailChannel ? UPDATE_VERIFIED_EMAIL : UPDATE_VERIFIED_SECONDARY_MSISDN, Map.of(
                "customerId", customerId, "value", value, "verifiedAt", LocalDateTime.ofInstant(verifiedAt, ZoneOffset.UTC)));
    }

    /** Sets the alias when {@code version} is current; returns the number of rows updated (0 = stale). */
    int updateAlias(long customerId, String aliasName, int version, Instant at) {
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);
        params.put("aliasName", aliasName);
        params.put("version", version);
        params.put("updatedAt", LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        return jdbc.update(UPDATE_ALIAS, params);
    }
}
