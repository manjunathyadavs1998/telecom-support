-- TESTS ONLY. Slice 03 test data (design 03-inbound backend §2, §9). Loaded by AbstractMySqlIT after the versioned
-- migrations; the name sorts after R__test_users_02_customer.sql, so its plans already exist. Tests never read db/seed.
-- Shape (customers without a CUSTOMER login; agents search them by MSISDN):
--   +919900000006 -> CUST-T-0006, account SUSPENDED NON_PAYMENT, 2 SUSPENDED subscriptions, verified email
--   +919900000007 -> CUST-T-0007, account SUSPENDED FRAUD, 1 SUSPENDED subscription, verified secondary MSISDN
--   +919900000008 -> CUST-T-0008, INACTIVE customer, ACTIVE account, 1 ACTIVE subscription
-- KBA answers (OQ 4): billing postal code + verified email or secondary MSISDN:
--   CUST-T-0006: 560001 + test.customer6@example.test; CUST-T-0007: 110001 + +919900000017; CUST-T-0008: none verified.
-- Every write of accounts.status sets or clears suspension_reason and suspended_at in the same statement (V7 CHECK).
-- Rerunnable: upserts by unique keys; subscriptions are inserted only when missing.

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
     'INACTIVE', @now, @now) AS new
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
) s
JOIN accounts a ON a.account_ref = s.account_ref
JOIN plans p ON p.code = s.plan_code
WHERE NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = s.msisdn);
