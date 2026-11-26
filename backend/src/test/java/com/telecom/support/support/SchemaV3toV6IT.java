package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * B1/B2 (design 02-customer backend §2, §9): V3–V6 tables, unique keys, money CHECKs, the {@code users.customer_id} FK and
 * linked test users, append-only {@code case_activity}, and an SLA row for every CUSTOMER leaf category.
 * Each test runs in a rolled-back transaction, so the rows it inserts never reach other ITs.
 */
@Transactional
class SchemaV3toV6IT extends AbstractMySqlIT {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    // ---------------------------------------------------------------- migrations and test data

    @Test
    void appliesV3toV6AndTheSlice02TestDataAfterTheUsersScript() {
        List<String> applied = jdbc.queryForList("""
                SELECT script FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank
                """, Map.of(), String.class);

        assertThat(applied).containsExactly(
                "V1__baseline.sql", "V2__identity_rbac_audit.sql", "V3__catalog.sql", "V4__customer.sql",
                "V5__servicecase.sql", "V6__payment.sql", "V7__account_suspension.sql", "V8__inbound.sql",
                "V9__reactivation_notification.sql",
                "R__test_users.sql", "R__test_users_02_customer.sql", "R__test_users_03_inbound.sql");
        assertThat(count("SELECT COUNT(*) FROM customers WHERE customer_ref LIKE 'CUST-D-%'")).isZero();
    }

    @Test
    void customerTestUsersAreLinkedExceptTheUnlinkedOne() {
        List<String> links = jdbc.queryForList("""
                SELECT CONCAT(u.username, '=', COALESCE(c.customer_ref, '-'))
                FROM users u LEFT JOIN customers c ON c.id = u.customer_id
                WHERE u.user_type = 'CUSTOMER'
                ORDER BY u.username
                """, Map.of(), String.class);

        assertThat(links).containsExactly(
                "+919900000001=CUST-T-0001",
                "+919900000002=CUST-T-0002",
                "+919900000003=CUST-T-0003",
                "+919900000004=-");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM accounts WHERE account_ref = 'ACC-T-0003'", Map.of(), String.class))
                .isEqualTo("CLOSED");
        assertThat(count("""
                SELECT COUNT(*) FROM subscriptions s JOIN accounts a ON a.id = s.account_id
                WHERE a.account_ref = 'ACC-T-0001'
                """)).isEqualTo(2);
    }

    @Test
    void usersCustomerIdMustReferenceACustomer() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET customer_id = 999999999 WHERE username = '+919900000004'", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_users_customer");
    }

    // ---------------------------------------------------------------- V3 catalog

    @Test
    void planCodeIsUniqueAndRentalCannotBeNegative() {
        assertThatThrownBy(() -> insertPlan("PRE_199", "10.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_plans_code");
        assertThatThrownBy(() -> insertPlan("NEG_PLAN", "-0.01"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_plans_rental");
    }

    @Test
    void promotionMustStartBeforeItEnds() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promotions (code, title, short_description, eligibility_text, terms, valid_from, valid_to,
                                        status, created_at, updated_at)
                VALUES ('BAD_PROMO', 't', 's', 'e', 'terms', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
                        'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_promotions_validity");
    }

    // ---------------------------------------------------------------- V4 customer

    @Test
    void oneAccountPerCustomerAndBillingDayWithinOneToTwentyEight() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, created_at, updated_at)
                SELECT id, 'ACC-T-DUP', 5, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM customers WHERE customer_ref = 'CUST-T-0001'
                """, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_accounts_customer");

        long customerId = insertCustomer("CUST-T-SCHEMA");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, created_at, updated_at)
                VALUES (:c, 'ACC-T-SCHEMA', 29, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("c", customerId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_accounts_cycle_day");
    }

    @Test
    void liveMsisdnIsUniqueButATerminatedNumberCanBeReused() {
        long account = accountId("ACC-T-0002");
        assertThatThrownBy(() -> insertSubscription(account, "+919900000001", false, "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_subscriptions_live_msisdn");

        // +919900000003 is TERMINATED on the closed account: the number may be live again elsewhere
        insertSubscription(account, "+919900000003", false, "ACTIVE");
        // and a number may be terminated more than once
        insertSubscription(account, "+919900000077", false, "TERMINATED");
        insertSubscription(account, "+919900000077", false, "TERMINATED");
    }

    @Test
    void onlyOnePrimarySubscriptionPerAccount() {
        long account = accountId("ACC-T-0002");
        assertThatThrownBy(() -> insertSubscription(account, "+919900000088", true, "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_subscriptions_primary_account");
        insertSubscription(account, "+919900000089", false, "ACTIVE");
    }

    @Test
    void onlyOneScheduledPlanChangePerSubscriptionAndChargeNotNegative() {
        long sub = subscriptionId("+919900000002");
        insertPlanChange(sub, "SCHEDULED", "0.00");
        assertThatThrownBy(() -> insertPlanChange(sub, "SCHEDULED", "0.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_plan_changes_scheduled_sub");
        insertPlanChange(sub, "APPLIED", "0.00");
        insertPlanChange(sub, "CANCELLED", "0.00");
        assertThatThrownBy(() -> insertPlanChange(sub, "CANCELLED", "-1.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_plan_changes_charge");
    }

    @Test
    void contactVerificationAttemptsAreBounded() {
        long customerId = customerId("CUST-T-0001");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO contact_verifications (customer_id, channel, new_value, code_hmac, attempts, status, expires_at,
                                                   created_at, updated_at)
                VALUES (:c, 'EMAIL', 'x@example.test', REPEAT('a', 64), 11, 'PENDING', UTC_TIMESTAMP(6),
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("c", customerId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_contact_verifications_attempts");
    }

    @Test
    void aPromotionIsClaimedOncePerSubscription() {
        long sub = subscriptionId("+919900000002");
        insertClaim(sub);
        assertThatThrownBy(() -> insertClaim(sub))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_promotion_claims");
    }

    // ---------------------------------------------------------------- V5 service case

    @Test
    void everyActiveCustomerLeafResolvesASeverityAndAnActiveSlaRow() {
        List<String> unresolved = jdbc.queryForList("""
                SELECT leaf.code
                FROM categories leaf
                LEFT JOIN categories parent ON parent.id = leaf.parent_id
                WHERE leaf.audience = 'CUSTOMER' AND leaf.active
                  AND NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = leaf.id AND ch.active)
                  AND NOT EXISTS (
                      SELECT 1 FROM sla_policies s
                      WHERE s.active
                        AND s.severity = COALESCE(leaf.default_severity, parent.default_severity)
                        AND s.category_id IN (leaf.id, parent.id))
                """, Map.of(), String.class);

        assertThat(unresolved).isEmpty();
        assertThat(count("""
                SELECT COUNT(*) FROM categories leaf
                WHERE leaf.audience = 'CUSTOMER' AND leaf.active
                  AND NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = leaf.id)
                """)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void slaMinutesMustBePositiveAndUniquePerCategoryAndSeverity() {
        long category = categoryId("BILLING");
        assertThatThrownBy(() -> insertSla(category, "MEDIUM", 10, 10))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_sla_policies");
        assertThatThrownBy(() -> insertSla(category, "CRITICAL", 0, 10))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_sla_policies_response");
        assertThatThrownBy(() -> insertSla(category, "CRITICAL", 10, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_sla_policies_resolution");
    }

    @Test
    void caseActivityIsAppendOnly() {
        assertThat(count("SELECT COUNT(*) FROM case_activity")).isPositive();

        assertThatThrownBy(() -> jdbc.update("UPDATE case_activity SET note_text = 'changed'", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("case_activity is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM case_activity", Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("case_activity is append-only");
    }

    @Test
    void caseReferenceIsUnique() {
        String existing = jdbc.queryForObject("SELECT MIN(reference) FROM cases", Map.of(), String.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cases (reference, account_id, category_id, case_type, channel, subject, description, severity,
                                   status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at,
                                   sla_due_at, created_at, updated_at)
                SELECT :ref, account_id, category_id, case_type, channel, subject, description, severity, status,
                       preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                       created_at, updated_at
                FROM cases WHERE reference = :ref
                """, Map.of("ref", existing)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_cases_reference");
    }

    // ---------------------------------------------------------------- V6 payment

    @Test
    void invoiceAmountsCannotBeNegative() {
        long account = accountId("ACC-T-0002");
        assertThatThrownBy(() -> insertInvoice(account, "INV-T-NEG1", "-1.00", "0.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_invoices_amount_due");
        assertThatThrownBy(() -> insertInvoice(account, "INV-T-NEG2", "1.00", "-1.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_invoices_amount_paid");
    }

    @Test
    void paymentAmountMustBePositive() {
        long invoice = invoiceId("INV-T-0102");
        assertThatThrownBy(() -> insertPayment(invoice, "PAY-T-ZERO", "0.00", "FAILED", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_payments_amount");
    }

    @Test
    void oneOpenPaymentPerInvoiceWhileClosedOnesMayRepeat() {
        long invoice = invoiceId("INV-T-0102");
        insertPayment(invoice, "PAY-T-OPEN1", "599.00", "PENDING", null);
        assertThatThrownBy(() -> insertPayment(invoice, "PAY-T-OPEN2", "599.00", "PENDING", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payments_open_invoice");
        insertPayment(invoice, "PAY-T-FAIL1", "599.00", "FAILED", null);
        insertPayment(invoice, "PAY-T-EXP1", "599.00", "EXPIRED", null);
    }

    @Test
    void openPaymentKeyCoversPendingOnlySoReviewMayCoexist() {
        // Erratum E1: REVIEW is outside uk_payments_open_invoice (two REVIEW, or REVIEW + PENDING, on one invoice).
        long invoice = invoiceId("INV-T-0102");
        insertPayment(invoice, "PAY-T-REV1", "599.00", "REVIEW", null);
        insertPayment(invoice, "PAY-T-REV2", "599.00", "REVIEW", null);
        insertPayment(invoice, "PAY-T-PEND1", "599.00", "PENDING", null);
        assertThatThrownBy(() -> insertPayment(invoice, "PAY-T-PEND2", "599.00", "PENDING", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payments_open_invoice");
    }

    @Test
    void gatewaySessionIdAndPaymentReferenceAreUnique() {
        long invoice = invoiceId("INV-T-0102");
        assertThatThrownBy(() -> insertPayment(invoice, "PAY-T-SESS", "599.00", "FAILED", "test-sess-0001"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payments_gateway_session");
        insertPayment(invoice, "PAY-T-REF", "599.00", "FAILED", null);
        assertThatThrownBy(() -> insertPayment(invoice, "PAY-T-REF", "599.00", "FAILED", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payments_reference");
    }

    @Test
    void gatewayEventIdIsUnique() {
        String sql = """
                INSERT INTO payment_gateway_events (gateway_event_id, payment_id, event_type, outcome, created_at)
                VALUES ('evt-schema-1', NULL, 'payment.succeeded', 'REJECTED', UTC_TIMESTAMP(6))
                """;
        jdbc.update(sql, Map.of());
        assertThatThrownBy(() -> jdbc.update(sql, Map.of()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payment_gateway_events_event");
    }

    // ---------------------------------------------------------------- helpers

    private void insertPlan(String code, String rental) {
        jdbc.update("""
                INSERT INTO plans (code, name, plan_type, monthly_rental, status, created_at, updated_at)
                VALUES (:code, 'Schema plan', 'PREPAID', :rental, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("code", code, "rental", new BigDecimal(rental)));
    }

    private long insertCustomer(String ref) {
        jdbc.update("""
                INSERT INTO customers (customer_ref, full_name, billing_address, status, created_at, updated_at)
                VALUES (:ref, 'Schema Customer', JSON_OBJECT('city', 'Pune'), 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("ref", ref));
        return customerId(ref);
    }

    private void insertSubscription(long accountId, String msisdn, boolean primary, String status) {
        jdbc.update("""
                INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
                VALUES (:a, :m, (SELECT id FROM plans WHERE code = 'PRE_199'), :p, :s,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("a", accountId, "m", msisdn, "p", primary, "s", status));
    }

    private void insertPlanChange(long subscriptionId, String status, String charge) {
        jdbc.update("""
                INSERT INTO plan_changes (subscription_id, from_plan_id, to_plan_id, effective_date, charge_amount, status,
                                          requested_by_user_id, created_at, updated_at)
                VALUES (:s, (SELECT id FROM plans WHERE code = 'POST_399'), (SELECT id FROM plans WHERE code = 'POST_599'),
                        CURRENT_DATE + INTERVAL 10 DAY, :charge, :status,
                        (SELECT id FROM users WHERE username = '+919900000002'), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("s", subscriptionId, "status", status, "charge", new BigDecimal(charge)));
    }

    private void insertClaim(long subscriptionId) {
        jdbc.update("""
                INSERT INTO promotion_claims (promotion_id, subscription_id, terms_hash, claimed_by_user_id, status, created_at)
                VALUES ((SELECT id FROM promotions WHERE code = 'T_DATA_BOOST'), :s, REPEAT('0', 64),
                        (SELECT id FROM users WHERE username = '+919900000002'), 'CLAIMED', UTC_TIMESTAMP(6))
                """, Map.of("s", subscriptionId));
    }

    private void insertSla(long categoryId, String severity, int response, int resolution) {
        jdbc.update("""
                INSERT INTO sla_policies (category_id, severity, response_minutes, resolution_minutes, active,
                                          created_at, updated_at)
                VALUES (:c, :sev, :resp, :res, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("c", categoryId, "sev", severity, "resp", response, "res", resolution));
    }

    private void insertInvoice(long accountId, String ref, String due, String paid) {
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date, amount_due,
                                      amount_paid, status, created_at, updated_at)
                VALUES (:a, :ref, CURRENT_DATE, CURRENT_DATE, CURRENT_DATE, CURRENT_DATE, :due, :paid, 'OPEN',
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("a", accountId, "ref", ref, "due", new BigDecimal(due),
                "paid", new BigDecimal(paid)));
    }

    private void insertPayment(long invoiceId, String reference, String amount, String status, String sessionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("i", invoiceId);
        params.put("ref", reference);
        params.put("amt", new BigDecimal(amount));
        params.put("st", status);
        params.put("sess", sessionId);
        jdbc.update("""
                INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, gateway_session_id,
                                      initiated_by_user_id, initiated_at, created_at, updated_at)
                SELECT :ref, i.account_id, i.id, :amt, 'INR', :st, :sess,
                       (SELECT id FROM users WHERE username = '+919900000002'),
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM invoices i WHERE i.id = :i
                """, params);
    }

    private long accountId(String ref) {
        return id("SELECT id FROM accounts WHERE account_ref = :v", ref);
    }

    private long customerId(String ref) {
        return id("SELECT id FROM customers WHERE customer_ref = :v", ref);
    }

    private long subscriptionId(String msisdn) {
        return id("SELECT id FROM subscriptions WHERE live_msisdn = :v", msisdn);
    }

    private long categoryId(String code) {
        return id("SELECT id FROM categories WHERE code = :v", code);
    }

    private long invoiceId(String ref) {
        return id("SELECT id FROM invoices WHERE invoice_ref = :v", ref);
    }

    private long id(String sql, String value) {
        Long id = jdbc.queryForObject(sql, Map.of("v", value), Long.class);
        assertThat(id).as(sql + " " + value).isNotNull();
        return id;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
