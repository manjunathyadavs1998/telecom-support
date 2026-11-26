-- TESTS ONLY. Slice 03 test data (design 03-inbound backend §2, §9). Loaded by AbstractMySqlIT after the versioned
-- migrations; the name sorts after R__test_users_02_customer.sql, so its plans already exist. Tests never read db/seed.
-- Shape (customers without a CUSTOMER login; agents search them by MSISDN):
--   +919900000006 -> CUST-T-0006, account SUSPENDED NON_PAYMENT, 2 SUSPENDED subscriptions, verified email
--   +919900000007 -> CUST-T-0007, account SUSPENDED FRAUD, 1 SUSPENDED subscription, verified secondary MSISDN
--   +919900000008 -> CUST-T-0008, INACTIVE customer, ACTIVE account, 1 ACTIVE subscription
--   +919900000009 -> CUST-T-0009, account SUSPENDED NON_PAYMENT, 1 SUSPENDED subscription, verified email, and an ENDED
--                    reactivation (REA-…-900001, by T-IN-REACT) 10 days ago: the 30-day account limit case (R13)
-- CUST-T-0006 also has two INTERNAL-category cases (INT_BILL_ADJUST, INT_ESCALATION) created by T-IN on the inbound
-- channel, each with an INTERNAL note (BR-04, R9). They sit on CUST-T-0006, not CUST-T-0001 (design §2 "same shape"), so
-- the exact case lists asserted by CustomerCaseIT stay unchanged.
-- KBA answers (OQ 4): billing postal code + verified email or secondary MSISDN:
--   CUST-T-0006: 560001 + test.customer6@example.test; CUST-T-0007: 110001 + +919900000017; CUST-T-0008: none verified;
--   CUST-T-0009: 411001 + test.customer9@example.test.
-- Every write of accounts.status sets or clears suspension_reason and suspended_at in the same statement (V7 CHECK).
-- Rerunnable: upserts by unique keys; subscriptions, cases, activity, identity checks and reactivations are inserted
-- only when missing (case_activity is append-only).

SET @now = UTC_TIMESTAMP(6);

-- ---------------------------------------------------------------- customers, accounts, subscriptions
INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-T-0006', 'Test Customer Suspended', NULL, 'test.customer6@example.test', @now - INTERVAL 300 DAY, NULL, NULL,
     JSON_OBJECT('line1', '6 Test Avenue', 'city', 'Bengaluru', 'state', 'KA', 'postalCode', '560001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-T-0007', 'Test Customer Fraud', NULL, NULL, NULL, '+919900000017', @now - INTERVAL 300 DAY,
     JSON_OBJECT('line1', '7 Test Marg', 'city', 'New Delhi', 'state', 'DL', 'postalCode', '110001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-T-0008', 'Test Customer Inactive', NULL, NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '8 Test Nagar', 'city', 'Hyderabad', 'state', 'TS', 'postalCode', '500001', 'country', 'IN'),
     'INACTIVE', @now, @now),
    ('CUST-T-0009', 'Test Customer Limit', NULL, 'test.customer9@example.test', @now - INTERVAL 300 DAY, NULL, NULL,
     JSON_OBJECT('line1', '9 Test Peth', 'city', 'Pune', 'state', 'MH', 'postalCode', '411001', 'country', 'IN'),
     'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    full_name = new.full_name, alias_name = new.alias_name, email = new.email,
    email_verified_at = new.email_verified_at, secondary_msisdn = new.secondary_msisdn,
    secondary_verified_at = new.secondary_verified_at, billing_address = new.billing_address,
    status = new.status, updated_at = new.updated_at;

INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, suspension_reason, suspended_at,
                      created_at, updated_at)
SELECT * FROM (
    SELECT c.id AS customer_id, a.account_ref, a.billing_cycle_day, a.status, a.suspension_reason, a.suspended_at,
           @now AS created_at, @now AS updated_at
    FROM (
        SELECT 'CUST-T-0006' AS customer_ref, 'ACC-T-0006' AS account_ref, 6 AS billing_cycle_day,
               'SUSPENDED' AS status, 'NON_PAYMENT' AS suspension_reason, @now - INTERVAL 5 DAY AS suspended_at
        UNION ALL SELECT 'CUST-T-0007', 'ACC-T-0007', 7, 'SUSPENDED', 'FRAUD', @now - INTERVAL 3 DAY
        UNION ALL SELECT 'CUST-T-0008', 'ACC-T-0008', 8, 'ACTIVE', NULL, NULL
        UNION ALL SELECT 'CUST-T-0009', 'ACC-T-0009', 9, 'SUSPENDED', 'NON_PAYMENT', @now - INTERVAL 2 DAY
    ) a
    JOIN customers c ON c.customer_ref = a.customer_ref
) AS new
ON DUPLICATE KEY UPDATE
    billing_cycle_day = new.billing_cycle_day, status = new.status, suspension_reason = new.suspension_reason,
    suspended_at = new.suspended_at, updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, s.msisdn, p.id, s.is_primary, s.status, @now - INTERVAL 400 DAY, @now, @now
FROM (
    SELECT 'ACC-T-0006' AS account_ref, '+919900000006' AS msisdn, 'POST_399' AS plan_code, TRUE AS is_primary,
           'SUSPENDED' AS status
    UNION ALL SELECT 'ACC-T-0006', '+919900000016', 'PRE_199', FALSE, 'SUSPENDED'
    UNION ALL SELECT 'ACC-T-0007', '+919900000007', 'PRE_299', TRUE,  'SUSPENDED'
    UNION ALL SELECT 'ACC-T-0008', '+919900000008', 'PRE_199', TRUE,  'ACTIVE'
    UNION ALL SELECT 'ACC-T-0009', '+919900000009', 'PRE_299', TRUE,  'SUSPENDED'
) s
JOIN accounts a ON a.account_ref = s.account_ref
JOIN plans p ON p.code = s.plan_code
WHERE NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = s.msisdn);

-- ---------------------------------------------------------------- INTERNAL-category cases on CUST-T-0006 (R9, BR-04)
-- References use the seq range 900101+ so they never meet generated ones or the 02 test cases.
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                   created_at, updated_at)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', '+05:30'), '%Y%m%d'), '-', k.seq),
       a.id, s.id, cat.id, k.case_type, 'INBOUND_AGENT', k.subject, k.description,
       k.severity, k.status, 'SMS', s.msisdn, u.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.created_at
FROM (
    SELECT '900101' AS seq, 'INT_BILL_ADJUST' AS category_code, 'REQUEST' AS case_type, 'MEDIUM' AS severity,
           'CREATED' AS status, 'Bill adjustment review' AS subject,
           'Customer says the last bill includes charges after the suspension.' AS description,
           240 AS response_minutes, 2880 AS resolution_minutes, @now - INTERVAL 3 DAY AS created_at
    UNION ALL SELECT '900102', 'INT_ESCALATION', 'COMPLAINT', 'HIGH', 'IN_PROGRESS', 'Escalated service complaint',
           'Customer reports repeated disconnections before the suspension.', 60, 1440, @now - INTERVAL 1 DAY
) k
JOIN accounts a ON a.account_ref = 'ACC-T-0006'
JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
JOIN categories cat ON cat.code = k.category_code
JOIN users u ON u.username = 'T-IN'
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref = 'ACC-T-0006'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'NOTE', 'INTERNAL', c.created_by_user_id, NULL, NULL,
       'Internal: customer disputes charges; check billing history before replying.', c.created_at + INTERVAL 1 MINUTE
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref = 'ACC-T-0006'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'NOTE');

-- ---------------------------------------------------------------- CUST-T-0009: ENDED reactivation 10 days ago
-- The identity check that evidenced it (KBA, VERIFIED, long expired) and the request; inserted only when missing.
INSERT INTO identity_checks (agent_user_id, customer_id, method, status, code_hmac, attempts, expires_at, verified_at,
                             valid_until, access_logged_at, created_at, updated_at)
SELECT u.id, c.id, 'KBA', 'VERIFIED', NULL, 0, NULL, @now - INTERVAL 10 DAY,
       @now - INTERVAL 10 DAY + INTERVAL 30 MINUTE, @now - INTERVAL 10 DAY, @now - INTERVAL 10 DAY,
       @now - INTERVAL 10 DAY
FROM users u
JOIN customers c ON c.customer_ref = 'CUST-T-0009'
WHERE u.username = 'T-IN-REACT'
  AND NOT EXISTS (SELECT 1 FROM identity_checks x WHERE x.customer_id = c.id AND x.agent_user_id = u.id);

INSERT INTO reactivation_requests (reference, account_id, suspension_reason, msisdns, requested_hours, reason_text,
                                   identity_check_id, requested_by_user_id, status, end_reason, provider_ref,
                                   activated_at, expires_at, ended_at, created_at, updated_at)
SELECT r.reference, r.account_id, 'NON_PAYMENT', JSON_ARRAY('+919900000009'), 24,
       'Customer promised payment today and needs service to pay online.', r.identity_check_id, r.agent_user_id,
       'ENDED', 'account_reinstated', 'MOCK-T-900001', r.started_at, r.started_at + INTERVAL 24 HOUR,
       r.started_at + INTERVAL 24 HOUR,
       r.started_at, r.started_at + INTERVAL 24 HOUR
FROM (
    SELECT CONCAT('REA-', DATE_FORMAT(CONVERT_TZ(@now - INTERVAL 10 DAY, '+00:00', '+05:30'), '%Y%m%d'), '-900001')
               AS reference,
           a.id AS account_id, MIN(ic.id) AS identity_check_id, MIN(ic.agent_user_id) AS agent_user_id,
           @now - INTERVAL 10 DAY AS started_at
    FROM accounts a
    JOIN identity_checks ic ON ic.customer_id = a.customer_id AND ic.method = 'KBA' AND ic.status = 'VERIFIED'
    JOIN users u ON u.id = ic.agent_user_id AND u.username = 'T-IN-REACT'
    WHERE a.account_ref = 'ACC-T-0009'
    GROUP BY a.id
) r
WHERE NOT EXISTS (SELECT 1 FROM reactivation_requests x WHERE x.account_id = r.account_id);
