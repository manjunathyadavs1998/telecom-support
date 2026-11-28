-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed, so also by `dev,e2e`; runs after R__02_customer).
-- Customer portal e2e data (design 02-customer backend erratum E5, frontend design §7 / D2), dates relative to NOW():
--   * customer 1 (+919800000001): 20 more cases and 20 more payments inside the last 3 months, so with
--     R__02_customer it has >= 21 of each (incl. SUCCEEDED payments) and its OPEN invoice INV-D-0003;
--   * customer 5 (+919800000005, linked CUSTOMER login, dev password): one subscription and exactly 2 OPEN invoices,
--     no cases and no payments (used only by the e2e payment spec).
-- The e2e profile starts from a clean database every run; in plain dev this file is repeatable and idempotent like
-- R__02_customer (rows are inserted only when missing; case_activity is append-only, nothing is deleted from it).

SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';
SET @dev_pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';   -- Password@123, as R__01_identity

-- ---------------------------------------------------------------- customer 1: 20 cases, 1..80 days old
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                   created_at, updated_at, resolved_at, closed_at, closure_type)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, NULL, cat.id, k.case_type, 'CUSTOMER_PORTAL', k.subject, k.description,
       k.severity, k.status, 'SMS', '+919800000001', u.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.created_at,
       -- V11 (04-outbound §2): RESOLVED/CLOSED rows carry resolved_at; CLOSED rows closed_at + closure_type (as the backfill).
       CASE WHEN k.status IN ('RESOLVED', 'CLOSED') THEN k.created_at END,
       CASE WHEN k.status = 'CLOSED' THEN k.created_at END,
       CASE WHEN k.status = 'CLOSED' THEN 'WINDOW_ELAPSED' END
FROM (
    SELECT CAST(900100 + n.i AS CHAR) AS seq,
           c.category_code, c.case_type, c.severity, c.response_minutes, c.resolution_minutes,
           ELT(1 + MOD(n.i, 5), 'CREATED', 'IN_PROGRESS', 'PENDING_CUSTOMER', 'RESOLVED', 'CLOSED') AS status,
           CONCAT('Seeded request ', LPAD(n.i, 2, '0'), ': ', c.title) AS subject,
           CONCAT('Seeded e2e case ', n.i, ' for paging and search.') AS description,
           @now - INTERVAL (n.i * 4) DAY - INTERVAL n.i HOUR AS created_at
    FROM n
    JOIN (
        SELECT 0 AS k, 'SLOW_DATA' AS category_code, 'COMPLAINT' AS case_type, 'HIGH' AS severity,
               60 AS response_minutes, 1440 AS resolution_minutes, 'Slow data' AS title
        UNION ALL SELECT 1, 'BILL_COPY',   'REQUEST', 'LOW',  480, 4320, 'Bill copy'
        UNION ALL SELECT 2, 'PLAN_QUERY',  'REQUEST', 'LOW',  480, 4320, 'Plan question'
        UNION ALL SELECT 3, 'SIM_REPLACE', 'REQUEST', 'HIGH',  60, 1440, 'SIM replacement'
    ) c ON c.k = MOD(n.i, 4)
) k
JOIN accounts a ON a.account_ref = 'ACC-D-0001'
JOIN categories cat ON cat.code = k.category_code
JOIN users u ON u.username = '+919800000001'
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref = 'ACC-D-0001'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

-- ---------------------------------------------------------------- customer 1: 20 payments, 30 h .. 25 days old
-- On the PAID invoice INV-D-0002 (issued 30 days ago): declined / expired attempts only, so no extra credit, and every
-- one completed more than 24 h ago (outside the late-capture re-check window).
INSERT INTO payments (reference, account_id, invoice_id, amount, currency, status, failure_reason, method,
                      gateway_session_id, redirect_url, gateway_txn_id, initiated_by_user_id, initiated_at, completed_at,
                      created_at, updated_at)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20)
SELECT CONCAT('PAY-', DATE_FORMAT(CONVERT_TZ(p.initiated_at, '+00:00', @ist), '%Y%m%d'), '-', p.seq),
       i.account_id, i.id, i.amount_due, 'INR', p.status, p.failure_reason, p.method,
       p.session_id, NULL, NULL, u.id, p.initiated_at, p.initiated_at + INTERVAL 2 MINUTE,
       p.initiated_at, p.initiated_at
FROM (
    SELECT CAST(900100 + n.i AS CHAR) AS seq,
           IF(MOD(n.i, 2) = 0, 'FAILED', 'EXPIRED') AS status,
           IF(MOD(n.i, 2) = 0, 'declined', 'expired') AS failure_reason,
           IF(MOD(n.i, 2) = 0, 'CARD', NULL) AS method,
           CONCAT('e2e-sess-', LPAD(n.i, 4, '0')) AS session_id,
           @now - INTERVAL (n.i * 30) HOUR AS initiated_at
    FROM n
) p
JOIN invoices i ON i.invoice_ref = 'INV-D-0002'
JOIN users u ON u.username = '+919800000001'
WHERE NOT EXISTS (SELECT 1 FROM payments x WHERE x.gateway_session_id = p.session_id);

-- ---------------------------------------------------------------- customer 5: login, customer, account, 2 OPEN invoices
INSERT INTO users (username, password_hash, display_name, user_type, team_id, status, created_at, updated_at) VALUES
    ('+919800000005', @dev_pwd, 'Dev Customer 5', 'CUSTOMER', NULL, 'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash, display_name = new.display_name, user_type = new.user_type,
    team_id = new.team_id, status = new.status, updated_at = new.updated_at;

DELETE ur FROM user_roles ur JOIN users u ON u.id = ur.user_id WHERE u.username = '+919800000005';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.code = 'CUSTOMER' WHERE u.username = '+919800000005';

INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-D-0005', 'Kavya Nair', NULL, 'kavya.nair@example.test', NULL, NULL, NULL,
     JSON_OBJECT('line1', '3 Beach Road', 'city', 'Kochi', 'state', 'KL', 'postalCode', '682001', 'country', 'IN'),
     'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    full_name = new.full_name, alias_name = new.alias_name, email = new.email,
    email_verified_at = new.email_verified_at, secondary_msisdn = new.secondary_msisdn,
    secondary_verified_at = new.secondary_verified_at, billing_address = new.billing_address,
    status = new.status, updated_at = new.updated_at;

INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, created_at, updated_at)
SELECT * FROM (
    SELECT c.id AS customer_id, 'ACC-D-0005' AS account_ref, 10 AS billing_cycle_day, 'ACTIVE' AS status,
           @now AS created_at, @now AS updated_at
    FROM customers c WHERE c.customer_ref = 'CUST-D-0005'
) AS new
ON DUPLICATE KEY UPDATE
    billing_cycle_day = new.billing_cycle_day, status = new.status,
    suspension_reason = NULL, suspended_at = NULL,   -- ACTIVE again: clear the V7 suspension columns together
    updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, '+919800000005', p.id, TRUE, 'ACTIVE', @now - INTERVAL 200 DAY, @now, @now
FROM accounts a JOIN plans p ON p.code = 'PRE_299'
WHERE a.account_ref = 'ACC-D-0005'
  AND NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = '+919800000005');

UPDATE users u JOIN customers c ON c.customer_ref = 'CUST-D-0005'
SET u.customer_id = c.id
WHERE u.username = '+919800000005';

-- invoice A (last cycle, overdue) and invoice B (current cycle); both OPEN, nothing paid
INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date, amount_due, amount_paid,
                      status, created_at, updated_at)
SELECT * FROM (
    SELECT a.id AS account_id, i.invoice_ref, i.period_start, i.period_end, i.issue_date,
           i.issue_date + INTERVAL 15 DAY AS due_date, i.amount_due, 0.00 AS amount_paid, 'OPEN' AS status,
           @now AS created_at, @now AS updated_at
    FROM (
        SELECT 'INV-D-0501' AS invoice_ref, CURRENT_DATE - INTERVAL 60 DAY AS period_start,
               CURRENT_DATE - INTERVAL 31 DAY AS period_end, CURRENT_DATE - INTERVAL 30 DAY AS issue_date,
               299.00 AS amount_due
        UNION ALL SELECT 'INV-D-0502', CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
               349.00
    ) i
    JOIN accounts a ON a.account_ref = 'ACC-D-0005'
) AS new
ON DUPLICATE KEY UPDATE invoice_ref = new.invoice_ref;
