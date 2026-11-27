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
 * B3: V9 reactivation policies (prohibited CHECK), the open-request UK, the 24 h CHECK, the notification outbox UK and
 * the ENDED reactivation / INTERNAL cases of the test data.
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

    // ---------------------------------------------------------------- V9 reactivation and notification outbox

    @Test
    void onlyNonPaymentIsEligibleAndProhibitedReasonsNeverCanBe() {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(suspension_reason, '/', IF(eligible, 'true', 'false'), '/', max_hours)
                FROM reactivation_policies ORDER BY suspension_reason
                """, Map.of(), String.class)).containsExactly(
                "CUSTOMER_REQUEST/false/24", "FRAUD/false/24", "KYC_PENDING/false/24", "LEGAL/false/24",
                "NON_PAYMENT/true/24", "OTHER/false/24", "REGULATORY/false/24");

        for (String reason : new String[] {"FRAUD", "LEGAL", "REGULATORY"}) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE reactivation_policies SET eligible = TRUE WHERE suspension_reason = :r", Map.of("r", reason)))
                    .as(reason)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_reactivation_policies_prohibited");
        }
        // any other reason may be made eligible
        jdbc.update("UPDATE reactivation_policies SET eligible = TRUE WHERE suspension_reason = 'OTHER'", Map.of());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reactivation_policies SET max_hours = 25 WHERE suspension_reason = 'OTHER'", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reactivation_policies_max_hours");
    }

    @Test
    void anAccountHasAtMostOneOpenReactivation() {
        // ACC-T-0009 already has an ENDED request (test data); closed rows never block
        insertReactivation("REA-T-1", "ACC-T-0009", "PENDING", 24, null, null);
        assertThatThrownBy(() -> insertReactivation("REA-T-2", "ACC-T-0009", "PENDING", 24, null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_reactivation_requests_open_account");

        for (String open : new String[] {"ACTIVE", "EXPIRING"}) {
            jdbc.update("UPDATE reactivation_requests SET status = 'FAILED' WHERE account_id = (SELECT id FROM accounts "
                    + "WHERE account_ref = 'ACC-T-0009') AND status <> 'ENDED'", Map.of());
            insertReactivation("REA-T-" + open, "ACC-T-0009", open, 24, 0, 24);
            assertThatThrownBy(() -> insertReactivation("REA-T-X" + open, "ACC-T-0009", "PENDING", 24, null, null))
                    .as(open)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("uk_reactivation_requests_open_account");
        }

        // closing the open one frees the account; terminal rows may repeat
        jdbc.update("""
                UPDATE reactivation_requests SET status = 'EXPIRED', version = version + 1
                WHERE reference = 'REA-T-EXPIRING' AND status = 'EXPIRING'
                """, Map.of());
        insertReactivation("REA-T-3", "ACC-T-0009", "FAILED", 24, null, null);
        insertReactivation("REA-T-4", "ACC-T-0009", "PENDING", 24, null, null);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reactivation_requests r JOIN accounts a ON a.id = r.account_id
                WHERE a.account_ref = 'ACC-T-0009' AND r.status IN ('PENDING', 'ACTIVE', 'EXPIRING')
                """, Map.of(), Long.class)).isEqualTo(1);
    }

    @Test
    void aReactivationLastsAtMost24HoursAndAsksForOneTo24() {
        insertReactivation("REA-T-24H", "ACC-T-0006", "ACTIVE", 24, 0, 24);
        jdbc.update("UPDATE reactivation_requests SET status = 'ENDED' WHERE reference = 'REA-T-24H'", Map.of());

        assertThatThrownBy(() -> insertReactivation("REA-T-25H", "ACC-T-0006", "ACTIVE", 24, 0, 25))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reactivation_requests_max_24h");
        // a late restore: activated in the past, window already over, still within 24 h of activation
        insertReactivation("REA-T-LATE", "ACC-T-0006", "EXPIRING", 2, -180, 2);

        for (int hours : new int[] {0, 25}) {
            assertThatThrownBy(() -> insertReactivation("REA-T-H" + hours, "ACC-T-0007", "PENDING", hours, null, null))
                    .as("%d h", hours)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_reactivation_requests_hours");
        }
        assertThatThrownBy(() -> insertReactivation("REA-T-ST", "ACC-T-0007", "RESTORED", 24, null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_reactivation_requests_status");
    }

    @Test
    void reactivationRowsStartWithNoAttemptsNoAttentionAndVersionZero() {
        insertReactivation("REA-T-NEW", "ACC-T-0006", "PENDING", 4, null, null);
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(attempts, '/', needs_attention, '/', version, '/', COALESCE(next_attempt_at, '-'), '/',
                              COALESCE(attention_reason, '-'), '/', open_account_id = account_id)
                FROM reactivation_requests WHERE reference = 'REA-T-NEW'
                """, Map.of(), String.class)).isEqualTo("0/0/0/-/-/1");
    }

    @Test
    void theOutboxHasOneRowPerSourceTemplateAndChannel() {
        insertNotification("REA-T-1", "REACTIVATION_STARTED", "SMS", "PENDING");
        insertNotification("REA-T-1", "REACTIVATION_STARTED", "EMAIL", "PENDING");
        insertNotification("REA-T-1", "REACTIVATION_ENDED", "SMS", "PENDING");
        assertThatThrownBy(() -> insertNotification("REA-T-1", "REACTIVATION_STARTED", "SMS", "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_notifications_source");

        // the claim state SENDING is allowed; unknown status, channel and template are not
        jdbc.update("""
                UPDATE notifications SET status = 'SENDING', claimed_at = UTC_TIMESTAMP(6)
                WHERE source_ref = 'REA-T-1' AND channel = 'SMS' AND template = 'REACTIVATION_STARTED'
                  AND status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6)
                """, Map.of());
        assertThatThrownBy(() -> insertNotification("REA-T-2", "REACTIVATION_STARTED", "SMS", "QUEUED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_notifications_status");
        assertThatThrownBy(() -> insertNotification("REA-T-2", "REACTIVATION_STARTED", "PUSH", "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_notifications_channel");
        assertThatThrownBy(() -> insertNotification("REA-T-2", "PAYMENT_RECEIVED", "SMS", "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_notifications_template");
        assertThat(jdbc.queryForList(
                "SELECT status FROM notifications WHERE source_ref = 'REA-T-1' ORDER BY id", Map.of(), String.class))
                .containsExactly("SENDING", "PENDING", "PENDING");
    }

    @Test
    void inboundTestDataHasAnEndedReactivationTenDaysAgoAndInternalCases() {
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(a.status, '/', a.suspension_reason, '/', r.status, '/', r.end_reason, '/', u.username, '/',
                              TIMESTAMPDIFF(DAY, r.created_at, UTC_TIMESTAMP(6)), '/', ic.status, '/',
                              r.open_account_id IS NULL)
                FROM reactivation_requests r
                JOIN accounts a ON a.id = r.account_id
                JOIN users u ON u.id = r.requested_by_user_id
                JOIN identity_checks ic ON ic.id = r.identity_check_id AND ic.customer_id = a.customer_id
                WHERE a.account_ref = 'ACC-T-0009'
                """, Map.of(), String.class))
                .isEqualTo("SUSPENDED/NON_PAYMENT/ENDED/account_reinstated/T-IN-REACT/10/VERIFIED/1");

        // CUST-T-0006: two INTERNAL-category inbound cases, each with exactly one INTERNAL note; CUST-T-0001 has none
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(cat.code, '/', p.audience, '/', c.channel, '/', u.username, '/',
                              (SELECT COUNT(*) FROM case_activity x
                               WHERE x.case_id = c.id AND x.activity_type = 'NOTE' AND x.visibility = 'INTERNAL'))
                FROM cases c
                JOIN accounts a ON a.id = c.account_id
                JOIN categories cat ON cat.id = c.category_id
                JOIN categories p ON p.id = COALESCE(cat.parent_id, cat.id)
                JOIN users u ON u.id = c.created_by_user_id
                WHERE a.account_ref = 'ACC-T-0006'
                  -- only the seeded test-data cases; other ITs create more cases on this customer
                  AND c.reference LIKE 'SR-%-9001%'
                ORDER BY cat.code
                """, Map.of(), String.class)).containsExactly(
                "INT_BILL_ADJUST/INTERNAL/INBOUND_AGENT/T-IN/1", "INT_ESCALATION/INTERNAL/INBOUND_AGENT/T-IN/1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM case_activity x JOIN cases c ON c.id = x.case_id
                JOIN accounts a ON a.id = c.account_id
                WHERE a.account_ref = 'ACC-T-0001' AND x.visibility = 'INTERNAL'
                  -- the test data's own cases; inbound ITs create agent cases with internal notes on this customer
                  AND c.channel <> 'INBOUND_AGENT'
                """, Map.of(), Long.class)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Inserts a reactivation of T-IN-REACT on the account, evidenced by a fresh VERIFIED KBA check. A null
     * {@code activatedMinutesFromNow} leaves activated_at/expires_at NULL; otherwise expires_at = activated_at + hours.
     */
    private void insertReactivation(String reference, String accountRef, String status, int requestedHours,
            Integer activatedMinutesFromNow, Integer expiresAfterHours) {
        jdbc.update("""
                INSERT INTO identity_checks (agent_user_id, customer_id, method, status, verified_at, valid_until,
                                             created_at, updated_at)
                SELECT u.id, a.customer_id, 'KBA', 'VERIFIED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM users u JOIN accounts a ON a.account_ref = :acc WHERE u.username = 'T-IN-REACT'
                """, Map.of("acc", accountRef));
        Map<String, Object> params = new HashMap<>();
        params.put("ref", reference);
        params.put("acc", accountRef);
        params.put("s", status);
        params.put("h", requestedHours);
        params.put("act", activatedMinutesFromNow);
        params.put("exp", expiresAfterHours);
        jdbc.update("""
                INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours,
                                                   reason_text, identity_check_id, requested_by_user_id, status,
                                                   activated_at, expires_at, created_at, updated_at)
                SELECT :ref, a.id, 'NON_PAYMENT', JSON_ARRAY('+919900000009'), :h, 'Schema test reactivation',
                       ic.id, ic.agent_user_id, :s,
                       UTC_TIMESTAMP(6) + INTERVAL :act MINUTE,
                       UTC_TIMESTAMP(6) + INTERVAL :act MINUTE + INTERVAL :exp HOUR,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM accounts a
                JOIN identity_checks ic ON ic.id = (SELECT MAX(x.id) FROM identity_checks x
                                                    WHERE x.customer_id = a.customer_id)
                WHERE a.account_ref = :acc
                """, params);
    }

    private void insertNotification(String sourceRef, String template, String channel, String status) {
        jdbc.update("""
                INSERT INTO notifications (customer_id, channel, template, destination, params, source_ref, status,
                                           next_attempt_at, created_at, updated_at)
                SELECT c.id, :ch, :t, '+919900000006', JSON_OBJECT('reference', :src), :src, :s,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM customers c WHERE c.customer_ref = 'CUST-T-0006'
                """, Map.of("src", sourceRef, "t", template, "ch", channel, "s", status));
    }

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
