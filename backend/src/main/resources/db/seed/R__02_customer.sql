-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed; runs after R__01_identity). Design 02-customer backend §2.
-- 6 plans (1 RETIRED), 3 promotions (1 expired, 1 plan-restricted), 4 customers linked to the dev logins
-- +919800000001..04: customer 4 has 2 subscriptions, customer 3 has no cases or payments; cases and payments inside and
-- older than the 3-month default window; open invoices.
-- Repeatable and idempotent: upserts by unique keys; rows without a natural key (subscriptions, cases, activity,
-- payments) are inserted only when missing. case_activity is append-only, so nothing is ever deleted from it.

SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';

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
    ('DATA_BOOST', 'Extra 10 GB', '10 GB extra data for 30 days', 'All active subscriptions',
     'Dev terms: 10 GB added once per subscription, valid 30 days from the claim.',
     @now - INTERVAL 30 DAY, @now + INTERVAL 90 DAY, 'ACTIVE', @now, @now),
    ('OTT_POST599', 'Free streaming upgrade', 'Premium streaming for 3 months', 'Postpaid 599 subscribers',
     'Dev terms: premium streaming for 3 months, then the plan default.',
     @now - INTERVAL 30 DAY, @now + INTERVAL 90 DAY, 'ACTIVE', @now, @now),
    ('FESTIVE_BONUS', 'Festive bonus', 'Festive data bonus (ended)', 'All active subscriptions',
     'Dev terms: expired offer.',
     @now - INTERVAL 120 DAY, @now - INTERVAL 1 DAY, 'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    title = new.title, short_description = new.short_description, eligibility_text = new.eligibility_text,
    terms = new.terms, valid_from = new.valid_from, valid_to = new.valid_to, status = new.status,
    updated_at = new.updated_at;

DELETE pp FROM promotion_plans pp JOIN promotions pr ON pr.id = pp.promotion_id
WHERE pr.code IN ('DATA_BOOST', 'OTT_POST599', 'FESTIVE_BONUS');

INSERT INTO promotion_plans (promotion_id, plan_id, created_at)
SELECT pr.id, p.id, @now
FROM promotions pr JOIN plans p ON p.code = 'POST_599'
WHERE pr.code = 'OTT_POST599';

-- ---------------------------------------------------------------- customers, accounts, subscriptions
INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-D-0001', 'Asha Rao', 'Asha', 'asha.rao@example.test', @now - INTERVAL 300 DAY, NULL, NULL,
     JSON_OBJECT('line1', '12 MG Road', 'city', 'Bengaluru', 'state', 'KA', 'postalCode', '560001', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-D-0002', 'Vikram Singh', NULL, 'vikram.singh@example.test', NULL, NULL, NULL,
     JSON_OBJECT('line1', '4 Park Street', 'city', 'Kolkata', 'state', 'WB', 'postalCode', '700016', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-D-0003', 'Meera Iyer', NULL, NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '8 Anna Salai', 'city', 'Chennai', 'state', 'TN', 'postalCode', '600002', 'country', 'IN'),
     'ACTIVE', @now, @now),
    ('CUST-D-0004', 'Rahul Mehta', 'Rahul', 'rahul.mehta@example.test', @now - INTERVAL 100 DAY,
     '+919800000014', @now - INTERVAL 100 DAY,
     JSON_OBJECT('line1', '21 Marine Drive', 'city', 'Mumbai', 'state', 'MH', 'postalCode', '400020', 'country', 'IN'),
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
        SELECT 'CUST-D-0001' AS customer_ref, 'ACC-D-0001' AS account_ref, 5 AS billing_cycle_day, 'ACTIVE' AS status
        UNION ALL SELECT 'CUST-D-0002', 'ACC-D-0002', 12, 'ACTIVE'
        UNION ALL SELECT 'CUST-D-0003', 'ACC-D-0003', 20, 'ACTIVE'
        UNION ALL SELECT 'CUST-D-0004', 'ACC-D-0004', 1,  'ACTIVE'
    ) a
    JOIN customers c ON c.customer_ref = a.customer_ref
) AS new
ON DUPLICATE KEY UPDATE
    billing_cycle_day = new.billing_cycle_day, status = new.status, updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, s.msisdn, p.id, s.is_primary, 'ACTIVE', @now - INTERVAL 400 DAY, @now, @now
FROM (
    SELECT 'ACC-D-0001' AS account_ref, '+919800000001' AS msisdn, 'POST_399' AS plan_code, TRUE AS is_primary
    UNION ALL SELECT 'ACC-D-0002', '+919800000002', 'PRE_199',  TRUE
    UNION ALL SELECT 'ACC-D-0003', '+919800000003', 'PRE_299',  TRUE
    UNION ALL SELECT 'ACC-D-0004', '+919800000004', 'POST_599', TRUE
    UNION ALL SELECT 'ACC-D-0004', '+919800000024', 'PRE_199',  FALSE
) s
JOIN accounts a ON a.account_ref = s.account_ref
JOIN plans p ON p.code = s.plan_code
WHERE NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = s.msisdn);

-- Link the dev CUSTOMER logins (created by R__01_identity) to their customers.
UPDATE users u
JOIN (
    SELECT '+919800000001' AS username, 'CUST-D-0001' AS customer_ref
    UNION ALL SELECT '+919800000002', 'CUST-D-0002'
    UNION ALL SELECT '+919800000003', 'CUST-D-0003'
    UNION ALL SELECT '+919800000004', 'CUST-D-0004'
) l ON l.username = u.username
JOIN customers c ON c.customer_ref = l.customer_ref
SET u.customer_id = c.id;

-- ---------------------------------------------------------------- cases (customer 3 has none). References use the
-- seq range 900001+ so they never meet generated ones.
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                   created_at, updated_at)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, NULL, cat.id, k.case_type, 'CUSTOMER_PORTAL', k.subject, k.description,
       k.severity, k.status, k.preferred_contact, k.msisdn, u.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.created_at
FROM (
    SELECT 'ACC-D-0001' AS account_ref, '+919800000001' AS username, '+919800000001' AS msisdn, '900001' AS seq,
           'SLOW_DATA' AS category_code, 'COMPLAINT' AS case_type, 'HIGH' AS severity, 'IN_PROGRESS' AS status,
           'SMS' AS preferred_contact, 'Slow data in the evening' AS subject,
           'Mobile data is very slow every evening at home.' AS description,
           60 AS response_minutes, 1440 AS resolution_minutes, @now - INTERVAL 10 DAY AS created_at
    UNION ALL SELECT 'ACC-D-0001', '+919800000001', '+919800000001', '900002', 'BILL_COPY', 'REQUEST', 'LOW', 'RESOLVED',
           'EMAIL', 'Need a copy of my bill', 'Please email a copy of last month''s bill.', 480, 4320, @now - INTERVAL 40 DAY
    UNION ALL SELECT 'ACC-D-0001', '+919800000001', '+919800000001', '900003', 'NO_SIGNAL', 'COMPLAINT', 'CRITICAL', 'CLOSED',
           'PHONE', 'No signal at the office', 'No network signal at all inside the office building.', 30, 240,
           @now - INTERVAL 150 DAY
    UNION ALL SELECT 'ACC-D-0002', '+919800000002', '+919800000002', '900004', 'BILL_DISPUTE', 'COMPLAINT', 'HIGH', 'PENDING_CUSTOMER',
           'SMS', 'Wrong roaming charge', 'I was charged for roaming but never left the city.', 60, 1440, @now - INTERVAL 5 DAY
    UNION ALL SELECT 'ACC-D-0004', '+919800000004', '+919800000004', '900005', 'SIM_REPLACE', 'REQUEST', 'HIGH', 'CREATED',
           'SMS', 'Lost my SIM card', 'My SIM card was lost while travelling, please replace it.', 60, 1440, @now - INTERVAL 1 DAY
    UNION ALL SELECT 'ACC-D-0004', '+919800000004', '+919800000004', '900006', 'PLAN_QUERY', 'REQUEST', 'LOW', 'CLOSED',
           'EMAIL', 'Question about my plan', 'Does my plan include international roaming?', 480, 4320, @now - INTERVAL 100 DAY
) k
JOIN accounts a ON a.account_ref = k.account_ref
JOIN categories cat ON cat.code = k.category_code
JOIN users u ON u.username = k.username
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref IN ('ACC-D-0001', 'ACC-D-0002', 'ACC-D-0004')
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

-- ---------------------------------------------------------------- invoices and payments (customer 3: open invoice only)
INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date, amount_due, amount_paid,
                      status, created_at, updated_at)
SELECT * FROM (
    SELECT a.id AS account_id, i.invoice_ref, i.period_start, i.period_end, i.issue_date, i.issue_date + INTERVAL 15 DAY AS due_date,
           i.amount_due, i.amount_paid, i.status, @now AS created_at, @now AS updated_at
    FROM (
        SELECT 'ACC-D-0001' AS account_ref, 'INV-D-0001' AS invoice_ref, CURRENT_DATE - INTERVAL 150 DAY AS period_start,
               CURRENT_DATE - INTERVAL 121 DAY AS period_end, CURRENT_DATE - INTERVAL 120 DAY AS issue_date,
               399.00 AS amount_due, 399.00 AS amount_paid, 'PAID' AS status
        UNION ALL SELECT 'ACC-D-0001', 'INV-D-0002', CURRENT_DATE - INTERVAL 60 DAY, CURRENT_DATE - INTERVAL 31 DAY,
               CURRENT_DATE - INTERVAL 30 DAY, 399.00, 399.00, 'PAID'
        UNION ALL SELECT 'ACC-D-0001', 'INV-D-0003', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 399.00, 0.00, 'OPEN'
        UNION ALL SELECT 'ACC-D-0002', 'INV-D-0101', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 199.00, 0.00, 'OPEN'
        UNION ALL SELECT 'ACC-D-0003', 'INV-D-0201', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 299.00, 0.00, 'OPEN'
        UNION ALL SELECT 'ACC-D-0004', 'INV-D-0301', CURRENT_DATE - INTERVAL 60 DAY, CURRENT_DATE - INTERVAL 31 DAY,
               CURRENT_DATE - INTERVAL 30 DAY, 798.00, 798.00, 'PAID'
        UNION ALL SELECT 'ACC-D-0004', 'INV-D-0302', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY,
               CURRENT_DATE, 798.00, 0.00, 'OPEN'
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
    SELECT 'INV-D-0001' AS invoice_ref, '+919800000001' AS username, '900001' AS seq, 399.00 AS amount,
           'SUCCEEDED' AS status, NULL AS failure_reason, 'UPI' AS method, 'dev-sess-0001' AS session_id,
           'dev-txn-0001' AS txn_id, @now - INTERVAL 118 DAY AS initiated_at
    UNION ALL SELECT 'INV-D-0002', '+919800000001', '900002', 399.00, 'FAILED', 'declined', 'CARD',
           'dev-sess-0002', NULL, @now - INTERVAL 28 DAY
    UNION ALL SELECT 'INV-D-0002', '+919800000001', '900003', 399.00, 'SUCCEEDED', NULL, 'UPI',
           'dev-sess-0003', 'dev-txn-0003', @now - INTERVAL 27 DAY
    UNION ALL SELECT 'INV-D-0301', '+919800000004', '900004', 798.00, 'SUCCEEDED', NULL, 'NETBANKING',
           'dev-sess-0004', 'dev-txn-0004', @now - INTERVAL 25 DAY
) p
JOIN invoices i ON i.invoice_ref = p.invoice_ref
JOIN users u ON u.username = p.username
WHERE NOT EXISTS (SELECT 1 FROM payments x WHERE x.gateway_session_id = p.session_id);
