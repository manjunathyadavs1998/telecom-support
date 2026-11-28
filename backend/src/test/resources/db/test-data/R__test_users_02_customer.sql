-- TESTS ONLY. Slice 02 test data (design 02-customer backend §2, §9). Loaded by AbstractMySqlIT after the versioned
-- migrations; the name sorts after R__test_users.sql, so the users it links already exist. Tests never read db/seed.
-- Shape: 6 plans (1 RETIRED), 4 promotions (1 all-plans, 1 plan-restricted, 1 expired, 1 INACTIVE), 3 customers:
--   +919900000001 -> CUST-T-0001, ACTIVE account, 2 subscriptions, cases and payments inside and older than 3 months, 1 open invoice
--   +919900000002 -> CUST-T-0002, ACTIVE account, 1 subscription, 1 case, 1 paid + 1 open invoice (the "other customer" for IDOR)
--   +919900000003 -> CUST-T-0003, CLOSED account, terminated subscription, nothing else
--   +919900000004 -> CUSTOMER login with no linked customer
-- Password for every test user: Password@123 (same hash as R__test_users.sql). Rerunnable: upserts by unique keys,
-- and rows without a natural key (subscriptions, cases, activity, payments) are inserted only when missing.

SET @test_pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';
SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';

-- ---------------------------------------------------------------- users
INSERT INTO users (username, password_hash, display_name, user_type, team_id, status, created_at, updated_at) VALUES
    ('+919900000002', @test_pwd, 'Test Customer 2',          'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('+919900000003', @test_pwd, 'Test Customer Closed',     'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('+919900000004', @test_pwd, 'Test Customer Unlinked',   'CUSTOMER', NULL, 'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash,
    display_name  = new.display_name,
    user_type     = new.user_type,
    team_id       = new.team_id,
    status        = new.status,
    updated_at    = new.updated_at;

DELETE ur FROM user_roles ur
JOIN users u ON u.id = ur.user_id
WHERE u.username IN ('+919900000002', '+919900000003', '+919900000004');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'CUSTOMER'
WHERE u.username IN ('+919900000002', '+919900000003', '+919900000004');

-- ---------------------------------------------------------------- catalog
INSERT INTO plans (code, name, plan_type, monthly_rental, description, status, created_at, updated_at) VALUES
    ('PRE_199',  'Smart 199',      'PREPAID',  199.00, '1.5 GB/day, unlimited calls',      'ACTIVE',  @now, @now),
    ('PRE_299',  'Smart 299',      'PREPAID',  299.00, '2 GB/day, unlimited calls',        'ACTIVE',  @now, @now),
    ('PRE_449',  'Smart 449',      'PREPAID',  449.00, '3 GB/day, unlimited calls, OTT',   'ACTIVE',  @now, @now),
    ('POST_399', 'Postpaid 399',   'POSTPAID', 399.00, '75 GB, unlimited calls',           'ACTIVE',  @now, @now),
    ('POST_599', 'Postpaid 599',   'POSTPAID', 599.00, '150 GB, unlimited calls, OTT',     'ACTIVE',  @now, @now),
    ('POST_349', 'Postpaid 349 (legacy)', 'POSTPAID', 349.00, NULL,                         'RETIRED', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    name = new.name, plan_type = new.plan_type, monthly_rental = new.monthly_rental,
    description = new.description, status = new.status, updated_at = new.updated_at;

DELETE pb FROM plan_benefits pb JOIN plans p ON p.id = pb.plan_id
WHERE p.code IN ('PRE_199', 'PRE_299', 'PRE_449', 'POST_399', 'POST_599', 'POST_349');

INSERT INTO plan_benefits (plan_id, benefit_type, label, value_text, is_key, sort_order, created_at)
SELECT p.id, b.benefit_type, b.label, b.value_text, b.is_key, b.sort_order, @now
FROM (
    SELECT 'PRE_199' AS code, 'DATA' AS benefit_type, 'Data' AS label, '1.5 GB/day' AS value_text, TRUE AS is_key, 10 AS sort_order
    UNION ALL SELECT 'PRE_199',  'VOICE',   'Calls',   'Unlimited',       TRUE,  20
    UNION ALL SELECT 'PRE_199',  'SMS',     'SMS',     '100/day',         FALSE, 30
    UNION ALL SELECT 'PRE_299',  'DATA',    'Data',    '2 GB/day',        TRUE,  10
    UNION ALL SELECT 'PRE_299',  'VOICE',   'Calls',   'Unlimited',       TRUE,  20
    UNION ALL SELECT 'PRE_299',  'SMS',     'SMS',     '100/day',         FALSE, 30
    UNION ALL SELECT 'PRE_449',  'DATA',    'Data',    '3 GB/day',        TRUE,  10
    UNION ALL SELECT 'PRE_449',  'VOICE',   'Calls',   'Unlimited',       TRUE,  20
    UNION ALL SELECT 'PRE_449',  'OTT',     'Streaming', 'Video app basic', FALSE, 30
    UNION ALL SELECT 'POST_399', 'DATA',    'Data',    '75 GB/month',     TRUE,  10
    UNION ALL SELECT 'POST_399', 'VOICE',   'Calls',   'Unlimited',       TRUE,  20
    UNION ALL SELECT 'POST_399', 'SMS',     'SMS',     '100/day',         FALSE, 30
    UNION ALL SELECT 'POST_599', 'DATA',    'Data',    '150 GB/month',    TRUE,  10
    UNION ALL SELECT 'POST_599', 'VOICE',   'Calls',   'Unlimited',       TRUE,  20
    UNION ALL SELECT 'POST_599', 'OTT',     'Streaming', 'Video app premium', TRUE, 30
    UNION ALL SELECT 'POST_599', 'ROAMING', 'Roaming', 'Incoming free',   FALSE, 40
    UNION ALL SELECT 'POST_349', 'DATA',    'Data',    '50 GB/month',     TRUE,  10
    UNION ALL SELECT 'POST_349', 'VOICE',   'Calls',   'Unlimited',       TRUE,  20
) b
JOIN plans p ON p.code = b.code;

INSERT INTO promotions (code, title, short_description, eligibility_text, terms, valid_from, valid_to, status,
                        created_at, updated_at) VALUES
    ('T_DATA_BOOST', 'Extra 10 GB', '10 GB extra data for 30 days', 'All active subscriptions',
     'Test terms: 10 GB added once per subscription, valid 30 days.',
     @now - INTERVAL 30 DAY, @now + INTERVAL 60 DAY, 'ACTIVE', @now, @now),
    ('T_OTT_POST', 'Free streaming upgrade', 'Premium streaming for 3 months', 'Postpaid 599 subscribers',
     'Test terms: premium streaming for 3 months, then the plan default.',
     @now - INTERVAL 30 DAY, @now + INTERVAL 60 DAY, 'ACTIVE', @now, @now),
    ('T_FESTIVE_OLD', 'Festive bonus', 'Expired festive data bonus', 'All active subscriptions',
     'Test terms: expired offer.',
     @now - INTERVAL 120 DAY, @now - INTERVAL 1 DAY, 'ACTIVE', @now, @now),
    ('T_INACTIVE', 'Paused offer', 'Withdrawn offer', 'All active subscriptions',
     'Test terms: inactive offer.',
     @now - INTERVAL 30 DAY, @now + INTERVAL 60 DAY, 'INACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    title = new.title, short_description = new.short_description, eligibility_text = new.eligibility_text,
    terms = new.terms, valid_from = new.valid_from, valid_to = new.valid_to, status = new.status,
    updated_at = new.updated_at;

DELETE pp FROM promotion_plans pp JOIN promotions pr ON pr.id = pp.promotion_id
WHERE pr.code IN ('T_DATA_BOOST', 'T_OTT_POST', 'T_FESTIVE_OLD', 'T_INACTIVE');

INSERT INTO promotion_plans (promotion_id, plan_id, created_at)
SELECT pr.id, p.id, @now
FROM promotions pr JOIN plans p ON p.code = 'POST_599'
WHERE pr.code = 'T_OTT_POST';

-- ---------------------------------------------------------------- customers, accounts, subscriptions
INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-T-0001', 'Test Customer', NULL, 'test.customer1@example.test', @now - INTERVAL 200 DAY, NULL, NULL,
     JSON_OBJECT('line1', '1 Test Street', 'city', 'Pune', 'state', 'MH', 'postalCode', '411001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-T-0002', 'Test Customer Two', 'Two', NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '2 Test Road', 'city', 'Chennai', 'state', 'TN', 'postalCode', '600001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-T-0003', 'Test Customer Closed', NULL, NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '3 Test Lane', 'city', 'Kolkata', 'state', 'WB', 'postalCode', '700001', 'country', 'IN'),
     'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    full_name = new.full_name, alias_name = new.alias_name, email = new.email,
    email_verified_at = new.email_verified_at, secondary_msisdn = new.secondary_msisdn,
    secondary_verified_at = new.secondary_verified_at, billing_address = new.billing_address,
    status = new.status, updated_at = new.updated_at;

INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, created_at, updated_at)
SELECT * FROM (
    SELECT c.id AS customer_id, a.account_ref, a.billing_cycle_day, a.status, @now AS created_at, @now AS updated_at
    FROM (
        SELECT 'CUST-T-0001' AS customer_ref, 'ACC-T-0001' AS account_ref, 5 AS billing_cycle_day, 'ACTIVE' AS status
        UNION ALL SELECT 'CUST-T-0002', 'ACC-T-0002', 15, 'ACTIVE'
        UNION ALL SELECT 'CUST-T-0003', 'ACC-T-0003', 1,  'CLOSED'
    ) a
    JOIN customers c ON c.customer_ref = a.customer_ref
) AS new
ON DUPLICATE KEY UPDATE
    billing_cycle_day = new.billing_cycle_day, status = new.status, updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, s.msisdn, p.id, s.is_primary, s.status, @now - INTERVAL 400 DAY, @now, @now
FROM (
    SELECT 'ACC-T-0001' AS account_ref, '+919900000001' AS msisdn, 'POST_399' AS plan_code, TRUE AS is_primary, 'ACTIVE' AS status
    UNION ALL SELECT 'ACC-T-0001', '+919900000011', 'PRE_199',  FALSE, 'ACTIVE'
    UNION ALL SELECT 'ACC-T-0002', '+919900000002', 'POST_599', TRUE,  'ACTIVE'
    UNION ALL SELECT 'ACC-T-0003', '+919900000003', 'PRE_199',  TRUE,  'TERMINATED'
) s
JOIN accounts a ON a.account_ref = s.account_ref
JOIN plans p ON p.code = s.plan_code
WHERE NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = s.msisdn);

-- Link the CUSTOMER logins (+919900000004 stays unlinked on purpose).
UPDATE users u
JOIN (
    SELECT '+919900000001' AS username, 'CUST-T-0001' AS customer_ref
    UNION ALL SELECT '+919900000002', 'CUST-T-0002'
    UNION ALL SELECT '+919900000003', 'CUST-T-0003'
) l ON l.username = u.username
JOIN customers c ON c.customer_ref = l.customer_ref
SET u.customer_id = c.id;

UPDATE users SET customer_id = NULL WHERE username = '+919900000004';

-- ---------------------------------------------------------------- cases (one inside the default window per active
-- customer, plus one older than 3 months). References use the seq range 900001+ so they never meet generated ones.
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                   created_at, updated_at, resolved_at, closed_at, closure_type)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, NULL, cat.id, k.case_type, 'CUSTOMER_PORTAL', k.subject, k.description,
       k.severity, k.status, 'SMS', k.msisdn, u.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.created_at,
       -- V11 (04-outbound §2): RESOLVED/CLOSED rows carry resolved_at; CLOSED rows closed_at + closure_type (as the backfill).
       CASE WHEN k.status IN ('RESOLVED', 'CLOSED') THEN k.created_at END,
       CASE WHEN k.status = 'CLOSED' THEN k.created_at END,
       CASE WHEN k.status = 'CLOSED' THEN 'WINDOW_ELAPSED' END
FROM (
    SELECT 'ACC-T-0001' AS account_ref, '+919900000001' AS username, '+919900000001' AS msisdn, '900001' AS seq,
           'SLOW_DATA' AS category_code, 'COMPLAINT' AS case_type, 'HIGH' AS severity, 'IN_PROGRESS' AS status,
           'Slow data in the evening' AS subject, 'Mobile data is very slow every evening at home.' AS description,
           60 AS response_minutes, 1440 AS resolution_minutes, @now - INTERVAL 10 DAY AS created_at
    UNION ALL SELECT 'ACC-T-0001', '+919900000001', '+919900000001', '900002', 'BILL_COPY', 'REQUEST', 'LOW', 'CREATED',
           'Need a copy of my bill', 'Please send a copy of last month''s bill.', 480, 4320, @now - INTERVAL 2 DAY
    UNION ALL SELECT 'ACC-T-0001', '+919900000001', '+919900000001', '900003', 'NO_SIGNAL', 'COMPLAINT', 'CRITICAL', 'CLOSED',
           'No signal at the office', 'No network signal at all inside the office building.', 30, 240, @now - INTERVAL 120 DAY
    UNION ALL SELECT 'ACC-T-0002', '+919900000002', '+919900000002', '900004', 'BILL_DISPUTE', 'COMPLAINT', 'HIGH', 'CREATED',
           'Wrong roaming charge', 'I was charged for roaming but never left the city.', 60, 1440, @now - INTERVAL 5 DAY
) k
JOIN accounts a ON a.account_ref = k.account_ref
JOIN categories cat ON cat.code = k.category_code
JOIN users u ON u.username = k.username
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref IN ('ACC-T-0001', 'ACC-T-0002')
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

-- ---------------------------------------------------------------- invoices and payments
INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date, amount_due, amount_paid,
                      status, created_at, updated_at)
SELECT * FROM (
    SELECT a.id AS account_id, i.invoice_ref, i.period_start, i.period_end, i.issue_date, i.issue_date + INTERVAL 15 DAY AS due_date,
           i.amount_due, i.amount_paid, i.status, @now AS created_at, @now AS updated_at
    FROM (
        SELECT 'ACC-T-0001' AS account_ref, 'INV-T-0001' AS invoice_ref, CURRENT_DATE - INTERVAL 150 DAY AS period_start,
               CURRENT_DATE - INTERVAL 121 DAY AS period_end, CURRENT_DATE - INTERVAL 120 DAY AS issue_date,
               399.00 AS amount_due, 399.00 AS amount_paid, 'PAID' AS status
        UNION ALL SELECT 'ACC-T-0001', 'INV-T-0002', CURRENT_DATE - INTERVAL 60 DAY, CURRENT_DATE - INTERVAL 31 DAY,
               CURRENT_DATE - INTERVAL 30 DAY, 399.00, 399.00, 'PAID'
        UNION ALL SELECT 'ACC-T-0001', 'INV-T-0003', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 399.00, 0.00, 'OPEN'
        UNION ALL SELECT 'ACC-T-0002', 'INV-T-0101', CURRENT_DATE - INTERVAL 60 DAY, CURRENT_DATE - INTERVAL 31 DAY,
               CURRENT_DATE - INTERVAL 30 DAY, 599.00, 599.00, 'PAID'
        UNION ALL SELECT 'ACC-T-0002', 'INV-T-0102', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 599.00, 0.00, 'OPEN'
    ) i
    JOIN accounts a ON a.account_ref = i.account_ref
) AS new
ON DUPLICATE KEY UPDATE invoice_ref = new.invoice_ref;

INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, failure_reason, method,
                      gateway_session_id, redirect_url, gateway_txn_id, initiated_by_user_id, initiated_at, completed_at,
                      created_at, updated_at)
SELECT CONCAT('PAY-', DATE_FORMAT(CONVERT_TZ(p.initiated_at, '+00:00', @ist), '%Y%m%d'), '-', p.seq),
       i.account_id, i.id, p.amount, 'INR', p.status, p.failure_reason, p.method,
       p.session_id, NULL, p.txn_id, u.id, p.initiated_at, p.initiated_at + INTERVAL 2 MINUTE,
       p.initiated_at, p.initiated_at
FROM (
    SELECT 'INV-T-0001' AS invoice_ref, '+919900000001' AS username, '900001' AS seq, 399.00 AS amount,
           'SUCCEEDED' AS status, NULL AS failure_reason, 'UPI' AS method, 'test-sess-0001' AS session_id,
           'test-txn-0001' AS txn_id, @now - INTERVAL 118 DAY AS initiated_at
    UNION ALL SELECT 'INV-T-0002', '+919900000001', '900002', 399.00, 'FAILED', 'declined', 'CARD',
           'test-sess-0002', NULL, @now - INTERVAL 28 DAY
    UNION ALL SELECT 'INV-T-0002', '+919900000001', '900003', 399.00, 'SUCCEEDED', NULL, 'UPI',
           'test-sess-0003', 'test-txn-0003', @now - INTERVAL 27 DAY
    UNION ALL SELECT 'INV-T-0101', '+919900000002', '900004', 599.00, 'SUCCEEDED', NULL, 'NETBANKING',
           'test-sess-0004', 'test-txn-0004', @now - INTERVAL 25 DAY
) p
JOIN invoices i ON i.invoice_ref = p.invoice_ref
JOIN users u ON u.username = p.username
WHERE NOT EXISTS (SELECT 1 FROM payments x WHERE x.gateway_session_id = p.session_id);
