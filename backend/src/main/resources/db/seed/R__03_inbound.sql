-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed, so also by `dev,e2e`; runs after R__02_customer*).
-- Inbound workspace data (design 03-inbound backend §2). Customers without a CUSTOMER login; agents (IN001, IN002)
-- search them by MSISDN:
--   +919800000006 -> CUST-D-0006 Nikhil Verma, account SUSPENDED NON_PAYMENT, 2 SUSPENDED subscriptions, verified email
--                    (eligible for a temporary reactivation by IN002, the REACTIVATION_APPROVER)
--   +919800000007 -> CUST-D-0007 Farah Khan, account SUSPENDED FRAUD (never reactivated), verified secondary MSISDN
--   +919800000008 -> CUST-D-0008 Arjun Das, INACTIVE customer, ACTIVE account, 1 ACTIVE subscription
-- KBA answers (OQ 4: billing postal code + verified email or verified secondary MSISDN):
--   CUST-D-0006: 110017 + nikhil.verma@example.test
--   CUST-D-0007: 302001 + +919800000017
--   CUST-D-0008: none verified (KBA unavailable; OTP only)
-- The INTERNAL-category cases on customer 1 are in R__03_inbound_e2e.sql.
-- Every write of accounts.status sets or clears suspension_reason and suspended_at in the same statement (V7 CHECK).
-- Repeatable and idempotent: upserts by unique keys; subscriptions are inserted only when missing.

SET @now = UTC_TIMESTAMP(6);

-- ---------------------------------------------------------------- customers, accounts, subscriptions
INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-D-0006', 'Nikhil Verma', 'Nikhil', 'nikhil.verma@example.test', @now - INTERVAL 200 DAY, NULL, NULL,
     JSON_OBJECT('line1', '5 Lodhi Road', 'city', 'New Delhi', 'state', 'DL', 'postalCode', '110017', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-D-0007', 'Farah Khan', NULL, NULL, NULL, '+919800000017', @now - INTERVAL 200 DAY,
     JSON_OBJECT('line1', '9 MI Road', 'city', 'Jaipur', 'state', 'RJ', 'postalCode', '302001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-D-0008', 'Arjun Das', NULL, NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '2 Fraser Road', 'city', 'Patna', 'state', 'BR', 'postalCode', '800001', 'country', 'IN'),
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
        SELECT 'CUST-D-0006' AS customer_ref, 'ACC-D-0006' AS account_ref, 6 AS billing_cycle_day,
               'SUSPENDED' AS status, 'NON_PAYMENT' AS suspension_reason, @now - INTERVAL 4 DAY AS suspended_at
        UNION ALL SELECT 'CUST-D-0007', 'ACC-D-0007', 15, 'SUSPENDED', 'FRAUD', @now - INTERVAL 2 DAY
        UNION ALL SELECT 'CUST-D-0008', 'ACC-D-0008', 25, 'ACTIVE', NULL, NULL
    ) a
    JOIN customers c ON c.customer_ref = a.customer_ref
) AS new
ON DUPLICATE KEY UPDATE
    billing_cycle_day = new.billing_cycle_day, status = new.status, suspension_reason = new.suspension_reason,
    suspended_at = new.suspended_at, updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, s.msisdn, p.id, s.is_primary, s.status, @now - INTERVAL 500 DAY, @now, @now
FROM (
    SELECT 'ACC-D-0006' AS account_ref, '+919800000006' AS msisdn, 'POST_399' AS plan_code, TRUE AS is_primary,
           'SUSPENDED' AS status
    UNION ALL SELECT 'ACC-D-0006', '+919800000016', 'PRE_199', FALSE, 'SUSPENDED'
    UNION ALL SELECT 'ACC-D-0007', '+919800000007', 'PRE_299', TRUE,  'SUSPENDED'
    UNION ALL SELECT 'ACC-D-0008', '+919800000008', 'PRE_199', TRUE,  'ACTIVE'
) s
JOIN accounts a ON a.account_ref = s.account_ref
JOIN plans p ON p.code = s.plan_code
WHERE NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = s.msisdn);
