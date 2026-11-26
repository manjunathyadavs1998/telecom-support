package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Design 03-inbound backend §2, §9. B1: V7 account suspension CHECK and the {@link AbstractMySqlIT} suspension helpers.
 * B2: V8 identity_checks CHECKs, lookup token UK, INTERNAL placeholder categories, an SLA for every active leaf × 4
 * severities, the {@code case.severity.override} grant (01 §13 E3) and the slice 03 test data.
 * Each test runs in a rolled-back transaction, so the rows it changes never reach other ITs.
 */
@Transactional
class SchemaV7toV9IT extends AbstractMySqlIT {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    // ---------------------------------------------------------------- V7 accounts suspension

    @Test
    void suspendedAccountNeedsReasonAndTimeAndOnlySuspendedHasThem() {
        // SUSPENDED without reason/time
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET status = 'SUSPENDED' WHERE account_ref = 'ACC-T-0001'", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        // SUSPENDED with a reason but no time
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET status = 'SUSPENDED', suspension_reason = 'NON_PAYMENT'
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        // ACTIVE and CLOSED never carry a reason
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET suspension_reason = 'NON_PAYMENT', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET suspension_reason = 'OTHER', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0003'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension");
    }

    @Test
    void suspensionReasonIsOneOfTheSevenCodes() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE accounts SET status = 'SUSPENDED', suspension_reason = 'UNPAID', suspended_at = UTC_TIMESTAMP(6)
                WHERE account_ref = 'ACC-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_suspension_reason");

        for (String reason : new String[] {"NON_PAYMENT", "CUSTOMER_REQUEST", "KYC_PENDING", "FRAUD", "LEGAL",
                "REGULATORY", "OTHER"}) {
            suspendAccount("ACC-T-0001", reason);
            assertThat(suspension("ACC-T-0001")).isEqualTo("SUSPENDED/" + reason + "/true");
        }
    }

    @Test
    void helpersSuspendAndReinstateInOneStatement() {
        suspendAccount("ACC-T-0002", "NON_PAYMENT");
        assertThat(suspension("ACC-T-0002")).isEqualTo("SUSPENDED/NON_PAYMENT/true");

        reinstateAccount("ACC-T-0001", "ACC-T-0002");
        assertThat(suspension("ACC-T-0001")).isEqualTo("ACTIVE/-/false");
        assertThat(suspension("ACC-T-0002")).isEqualTo("ACTIVE/-/false");
    }

    @Test
    void testDataHasNoSuspendedAccountWithoutAReason() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts
                WHERE (status = 'SUSPENDED') <> (suspension_reason IS NOT NULL AND suspended_at IS NOT NULL)
                """, Map.of(), Long.class)).isZero();
    }

    // ---------------------------------------------------------------- V8 lookups, identity checks, categories, SLA

    @Test
    void identityCheckOtpHasAnHmacAndKbaHasNone() {
        insertCheck("OTP", "PENDING", HMAC, 10, null);
        insertCheck("KBA", "CHECKING", null, null, null);

        assertCheckRejected("OTP", "PENDING", null, 10, null,
                "ck_identity_checks_otp_hmac");
        assertCheckRejected("KBA", "CHECKING", HMAC, null, null, "ck_identity_checks_otp_hmac");
    }

    @Test
    void pendingNeedsAnOtpExpiryAndKbaIsNeverPending() {
        assertCheckRejected("OTP", "PENDING", HMAC, null, null, "ck_identity_checks_pending_expiry");
        // KBA PENDING breaks both pending_expiry and kba_status; MySQL names whichever it evaluates first
        assertCheckRejected("KBA", "PENDING", null, 10, null, "ck_identity_checks_");
        for (String status : new String[] {"EXPIRED", "LOCKED"}) {
            assertCheckRejected("KBA", status, null, null, null, "ck_identity_checks_kba_status");
        }
        for (String status : new String[] {"CHECKING", "FAILED", "SUPERSEDED"}) {
            insertCheck("KBA", status, null, null, null);
        }
        insertCheck("KBA", "VERIFIED", null, null, 30);
    }

    @Test
    void verifiedNeedsValidUntilAndStatusMethodAttemptsAreChecked() {
        assertCheckRejected("KBA", "VERIFIED", null, null, null, "ck_identity_checks_verified_until");
        assertCheckRejected("OTP", "VERIFIED", HMAC, 0, null, "ck_identity_checks_verified_until");
        assertCheckRejected("OTP", "DONE", HMAC, 0, null, "ck_identity_checks_status");
        assertCheckRejected("SMS", "FAILED", null, null, null, "ck_identity_checks_method");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, attempts, created_at, updated_at)
                SELECT u.id, c.id, 'KBA', 'FAILED', 11, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM users u JOIN customers c ON c.customer_ref = 'CUST-T-0001' WHERE u.username = 'T-IN'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_identity_checks_attempts");
    }

    @Test
    void lookupTokenHashIsUnique() {
        String insert = """
                INSERT INTO customer_lookups (agent_user_id, customer_id, token_hash, expires_at, created_at)
                SELECT u.id, c.id, :hash, UTC_TIMESTAMP(6) + INTERVAL 15 MINUTE, UTC_TIMESTAMP(6)
                FROM users u JOIN customers c ON c.customer_ref = 'CUST-T-0001' WHERE u.username = 'T-IN'
                """;
        jdbc.update(insert, Map.of("hash", HMAC));

        assertThatThrownBy(() -> jdbc.update(insert, Map.of("hash", HMAC)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_customer_lookups_token");
    }

    @Test
    void internalPlaceholderCategoriesAreTwoTopLevelsWithCustomerSafeNames() {
        List<String> internal = jdbc.queryForList("""
                SELECT CONCAT(c.code, '/', COALESCE(p.code, '-'), '/', c.case_type, '/',
                              COALESCE(c.default_severity, '-'), '/', c.active)
                FROM categories c LEFT JOIN categories p ON p.id = c.parent_id
                WHERE c.audience = 'INTERNAL'
                ORDER BY c.code
                """, Map.of(), String.class);

        assertThat(internal).containsExactly(
                "INT_BACKOFFICE/-/REQUEST/MEDIUM/1",
                "INT_BILL_ADJUST/INT_BACKOFFICE/REQUEST/-/1",
                "INT_ESCALATION/-/COMPLAINT/HIGH/1",
                "INT_PROVISIONING/INT_BACKOFFICE/REQUEST/HIGH/1");
        assertThat(jdbc.queryForList("SELECT name FROM categories WHERE code LIKE 'INT\\_%'", Map.of(), String.class))
                .allSatisfy(name -> assertThat(name).doesNotContainIgnoringCase("internal"));
    }

    @Test
    void everyActiveLeafResolvesAnSlaForAllFourSeveritiesLeafFirstThenParent() {
        String leaves = """
                FROM categories l
                LEFT JOIN categories p ON p.id = l.parent_id
                WHERE l.active AND (p.id IS NULL OR p.active)
                  AND NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = l.id AND ch.active)
                """;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) " + leaves, Map.of(), Long.class))
                .as("12 CUSTOMER + 3 INTERNAL leaves").isEqualTo(15L);

        List<String> unresolved = jdbc.queryForList("""
                SELECT CONCAT(l.code, '/', s.severity)
                FROM categories l
                LEFT JOIN categories p ON p.id = l.parent_id
                CROSS JOIN (SELECT 'LOW' AS severity UNION ALL SELECT 'MEDIUM' UNION ALL SELECT 'HIGH'
                            UNION ALL SELECT 'CRITICAL') s
                WHERE l.active AND (p.id IS NULL OR p.active)
                  AND NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = l.id AND ch.active)
                  AND NOT EXISTS (SELECT 1 FROM sla_policies x
                                  WHERE x.active AND x.severity = s.severity
                                    AND x.category_id IN (l.id, COALESCE(p.id, l.id)))
                """, Map.of(), String.class);
        assertThat(unresolved).isEmpty();

        // V5 leaf rows still win over the new parent rows (NO_SIGNAL CRITICAL 30; NETWORK CRITICAL added by V8)
        assertThat(sla("NO_SIGNAL", "CRITICAL")).isEqualTo("30/240");
        assertThat(sla("NETWORK", "CRITICAL")).isEqualTo("30/240");
        assertThat(sla("BILLING", "MEDIUM")).isEqualTo("240/2880");
        assertThat(sla("INT_ESCALATION", "LOW")).isEqualTo("480/4320");
    }

    @Test
    void severityOverrideIsGrantedToInboundAgentsOnly() {
        assertThat(jdbc.queryForList("""
                SELECT r.code
                FROM roles r
                JOIN role_permissions rp ON rp.role_id = r.id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'case.severity.override'
                """, Map.of(), String.class)).containsExactly("INBOUND_AGENT");
    }

    @Test
    void inboundTestDataHasSuspendedFraudAndInactiveCustomers() {
        List<String> rows = jdbc.queryForList("""
                SELECT CONCAT(c.customer_ref, '/', c.status, '/', a.status, '/', COALESCE(a.suspension_reason, '-'), '/',
                              (SELECT GROUP_CONCAT(s.status ORDER BY s.msisdn) FROM subscriptions s
                               WHERE s.account_id = a.id))
                FROM customers c JOIN accounts a ON a.customer_id = c.id
                WHERE c.customer_ref IN ('CUST-T-0006', 'CUST-T-0007', 'CUST-T-0008')
                ORDER BY c.customer_ref
                """, Map.of(), String.class);

        assertThat(rows).containsExactly(
                "CUST-T-0006/ACTIVE/SUSPENDED/NON_PAYMENT/SUSPENDED,SUSPENDED",
                "CUST-T-0007/ACTIVE/SUSPENDED/FRAUD/SUSPENDED",
                "CUST-T-0008/INACTIVE/ACTIVE/-/ACTIVE");
    }

    // ---------------------------------------------------------------- helpers

    private static final String HMAC = "a".repeat(64);

    /** Inserts a check of T-IN for CUST-T-0001; a null minutes value leaves the time column NULL. */
    private void insertCheck(String method, String status, String hmac, Integer expiresInMinutes,
            Integer validForMinutes) {
        Map<String, Object> params = new HashMap<>();
        params.put("m", method);
        params.put("s", status);
        params.put("h", hmac);
        params.put("exp", expiresInMinutes);
        params.put("valid", validForMinutes);
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, code_hmac, expires_at,
                                             valid_until, created_at, updated_at)
                SELECT u.id, c.id, :m, :s, :h, UTC_TIMESTAMP(6) + INTERVAL :exp MINUTE,
                       UTC_TIMESTAMP(6) + INTERVAL :valid MINUTE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM users u JOIN customers c ON c.customer_ref = 'CUST-T-0001' WHERE u.username = 'T-IN'
                """, params);
    }

    private void assertCheckRejected(String method, String status, String hmac, Integer expiresInMinutes,
            Integer validForMinutes, String constraint) {
        assertThatThrownBy(() -> insertCheck(method, status, hmac, expiresInMinutes, validForMinutes))
                .as("%s %s", method, status)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    /** Response/resolution minutes of the active SLA for (category, severity), leaf first then parent. */
    private String sla(String categoryCode, String severity) {
        return jdbc.queryForObject("""
                SELECT CONCAT(x.response_minutes, '/', x.resolution_minutes)
                FROM categories l
                JOIN sla_policies x ON x.active AND x.severity = :sev AND x.category_id IN (l.id, COALESCE(l.parent_id, l.id))
                WHERE l.code = :code
                ORDER BY x.category_id = l.id DESC
                LIMIT 1
                """, Map.of("code", categoryCode, "sev", severity), String.class);
    }

    private String suspension(String accountRef) {
        return jdbc.queryForObject("""
                SELECT CONCAT(status, '/', COALESCE(suspension_reason, '-'), '/',
                              IF(suspended_at IS NULL, 'false', 'true'))
                FROM accounts WHERE account_ref = :ref
                """, Map.of("ref", accountRef), String.class);
    }
}
