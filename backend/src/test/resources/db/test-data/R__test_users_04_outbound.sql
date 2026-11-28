-- TESTS ONLY. Slice 04 test data (design 04-outbound backend §2, §9). Loaded by AbstractMySqlIT after the versioned
-- migrations; the name sorts after R__test_users_03_inbound.sql. Tests never read db/seed.
-- Users (password = the shared test password): T-OUT and T-OUT-SUP exist (R__test_users.sql, OUTBOUND_N); this adds
--   T-OUT-2 (OUTBOUND_AGENT, OUTBOUND_N: same team as T-OUT, for parallel claims) and
--   T-OUT-S (OUTBOUND_AGENT, OUTBOUND_S: another team, for queue scope).
-- Cases sit on a dedicated customer CUST-T-0010 / ACC-T-0010 (+919900000010), so the exact case lists asserted by the
-- 02/03 ITs stay unchanged. Created by T-IN on the inbound channel. References use the seq range 900401+:
--   900401  pool, CREATED, MEDIUM                       900402  pool, CREATED, CRITICAL, SLA overdue
--   900403  OUTBOUND_N / T-OUT, IN_PROGRESS, HIGH         900404  OUTBOUND_N / T-OUT-2, ASSIGNED, LOW
--   900405  OUTBOUND_N team only, CREATED                900406  OUTBOUND_S / T-OUT-S, PENDING_CUSTOMER
--   900407  T-OUT, RESOLVED 4 days ago (auto-close)       900408  T-OUT, CLOSED 3 days ago (CUSTOMER_CONFIRMED)
--   900409  T-OUT-2, CLOSED 10 days ago (WINDOW_ELAPSED)
-- Bookmarks of T-OUT: 900403 (seen) and 900402 (changed since seen). Rerunnable: upserts by unique keys; cases,
-- activity and bookmarks are inserted only when missing (case_activity is append-only).

SET @test_pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';
SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';

-- ---------------------------------------------------------------- outbound test agents
INSERT INTO users (username, password_hash, display_name, user_type, team_id, status, created_at, updated_at) VALUES
    ('T-OUT-2', @test_pwd, 'Test Outbound Agent 2',     'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('T-OUT-S', @test_pwd, 'Test Outbound Agent South', 'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_S'), 'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash, display_name = new.display_name, user_type = new.user_type,
    team_id = new.team_id, status = new.status, updated_at = new.updated_at;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'OUTBOUND_AGENT'
WHERE u.username IN ('T-OUT-2', 'T-OUT-S')
  AND NOT EXISTS (SELECT 1 FROM user_roles x WHERE x.user_id = u.id AND x.role_id = r.id);

-- ---------------------------------------------------------------- customer, account, subscription
INSERT INTO customers (customer_ref, full_name, alias_name, email, email_verified_at, secondary_msisdn,
                       secondary_verified_at, billing_address, status, created_at, updated_at) VALUES
    ('CUST-T-0010', 'Test Customer Outbound', NULL, NULL, NULL, NULL, NULL,
     JSON_OBJECT('line1', '10 Test Road', 'city', 'Chennai', 'state', 'TN', 'postalCode', '600001', 'country', 'IN'),
     'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    full_name = new.full_name, billing_address = new.billing_address, status = new.status, updated_at = new.updated_at;

INSERT INTO accounts (customer_id, account_ref, billing_cycle_day, status, suspension_reason, suspended_at,
                      created_at, updated_at)
SELECT * FROM (
    SELECT c.id AS customer_id, 'ACC-T-0010' AS account_ref, 10 AS billing_cycle_day, 'ACTIVE' AS status,
           NULL AS suspension_reason, NULL AS suspended_at, @now AS created_at, @now AS updated_at
    FROM customers c WHERE c.customer_ref = 'CUST-T-0010'
) AS new
ON DUPLICATE KEY UPDATE billing_cycle_day = new.billing_cycle_day, status = new.status,
    suspension_reason = new.suspension_reason, suspended_at = new.suspended_at, updated_at = new.updated_at;

INSERT INTO subscriptions (account_id, msisdn, plan_id, is_primary, status, activated_at, created_at, updated_at)
SELECT a.id, '+919900000010', p.id, TRUE, 'ACTIVE', @now - INTERVAL 400 DAY, @now, @now
FROM accounts a
JOIN plans p ON p.code = 'POST_399'
WHERE a.account_ref = 'ACC-T-0010'
  AND NOT EXISTS (SELECT 1 FROM subscriptions x WHERE x.account_id = a.id AND x.msisdn = '+919900000010');

-- ---------------------------------------------------------------- cases
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, assigned_team_id,
                   assigned_user_id, response_due_at, sla_due_at, created_at, updated_at, resolved_at,
                   resolved_by_user_id, resolution_remarks, closed_at, closure_type)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, s.id, cat.id, cat.case_type, 'INBOUND_AGENT', k.subject, k.description,
       k.severity, k.status, 'PHONE', s.msisdn, creator.id, t.id, owner.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.updated_at, k.resolved_at,
       CASE WHEN k.resolved_at IS NOT NULL THEN owner.id END,
       CASE WHEN k.resolved_at IS NOT NULL THEN 'Resolved in a test fixture call.' END,
       k.closed_at, k.closure_type
FROM (
    SELECT '900401' AS seq, 'PAYMENT_MISSING' AS category_code, 'MEDIUM' AS severity, 'CREATED' AS status,
           NULL AS team_code, NULL AS owner, 'Outbound test: pool case' AS subject,
           'Pool case for queue scope tests.' AS description, 240 AS response_minutes, 2880 AS resolution_minutes,
           @now - INTERVAL 1 DAY AS created_at, @now - INTERVAL 1 DAY AS updated_at,
           CAST(NULL AS DATETIME(6)) AS resolved_at, CAST(NULL AS DATETIME(6)) AS closed_at, CAST(NULL AS CHAR(20)) AS closure_type
    UNION ALL SELECT '900402', 'NO_SIGNAL', 'CRITICAL', 'CREATED', NULL, NULL, 'Outbound test: overdue critical',
           'Pool case past its SLA.', 30, 240, @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900403', 'BILL_DISPUTE', 'HIGH', 'IN_PROGRESS', 'OUTBOUND_N', 'T-OUT', 'Outbound test: T-OUT in progress',
           'Owned by T-OUT.', 60, 1440, @now - INTERVAL 3 DAY, @now - INTERVAL 2 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900404', 'BILL_COPY', 'LOW', 'ASSIGNED', 'OUTBOUND_N', 'T-OUT-2', 'Outbound test: T-OUT-2 assigned',
           'Owned by T-OUT-2.', 480, 4320, @now - INTERVAL 2 DAY, @now - INTERVAL 2 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900405', 'SIM_REPLACE', 'MEDIUM', 'CREATED', 'OUTBOUND_N', NULL, 'Outbound test: north team pool',
           'Routed to OUTBOUND_N, unassigned.', 240, 2880, @now - INTERVAL 5 HOUR, @now - INTERVAL 5 HOUR, NULL, NULL, NULL
    UNION ALL SELECT '900406', 'SLOW_DATA', 'MEDIUM', 'PENDING_CUSTOMER', 'OUTBOUND_S', 'T-OUT-S', 'Outbound test: south team',
           'Owned by T-OUT-S.', 240, 2880, @now - INTERVAL 4 DAY, @now - INTERVAL 1 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900407', 'PLAN_QUERY', 'LOW', 'RESOLVED', 'OUTBOUND_N', 'T-OUT', 'Outbound test: resolved 4 days ago',
           'Auto-close candidate.', 480, 4320, @now - INTERVAL 8 DAY, @now - INTERVAL 4 DAY, @now - INTERVAL 4 DAY, NULL, NULL
    UNION ALL SELECT '900408', 'SLOW_DATA', 'HIGH', 'CLOSED', 'OUTBOUND_N', 'T-OUT', 'Outbound test: closed 3 days ago',
           'Inside the reopen window.', 60, 1440, @now - INTERVAL 9 DAY, @now - INTERVAL 3 DAY, @now - INTERVAL 6 DAY,
           @now - INTERVAL 3 DAY, 'CUSTOMER_CONFIRMED'
    UNION ALL SELECT '900409', 'BILL_COPY', 'LOW', 'CLOSED', 'OUTBOUND_N', 'T-OUT-2', 'Outbound test: closed 10 days ago',
           'Outside the reopen window.', 480, 4320, @now - INTERVAL 20 DAY, @now - INTERVAL 10 DAY, @now - INTERVAL 14 DAY,
           @now - INTERVAL 10 DAY, 'WINDOW_ELAPSED'
) k
JOIN accounts a ON a.account_ref = 'ACC-T-0010'
JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
JOIN categories cat ON cat.code = k.category_code
JOIN users creator ON creator.username = 'T-IN'
LEFT JOIN teams t ON t.code = k.team_code
LEFT JOIN users owner ON owner.username = k.owner
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

-- ---------------------------------------------------------------- activity (append-only)
INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id AND a.account_ref = 'ACC-T-0010'
WHERE NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'ASSIGNED', 'INTERNAL', c.assigned_user_id, NULL, CAST(c.assigned_user_id AS CHAR), NULL,
       c.created_at + INTERVAL 30 MINUTE
FROM cases c
JOIN accounts a ON a.id = c.account_id AND a.account_ref = 'ACC-T-0010'
WHERE c.assigned_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'ASSIGNED');

-- ---------------------------------------------------------------- T-OUT bookmarks (OUT-06)
INSERT INTO bookmarks (user_id, case_id, last_seen_at, created_at)
SELECT u.id, c.id,
       CASE WHEN c.reference LIKE '%-900402' THEN c.created_at ELSE c.updated_at END,
       c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id AND a.account_ref = 'ACC-T-0010'
JOIN users u ON u.username = 'T-OUT'
WHERE (c.reference LIKE 'SR-%-900402' OR c.reference LIKE 'SR-%-900403')
  AND NOT EXISTS (SELECT 1 FROM bookmarks b WHERE b.user_id = u.id AND b.case_id = c.id);
