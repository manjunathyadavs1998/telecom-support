-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed). Outbound workspace data (design 04-outbound backend §2),
-- dates relative to NOW(). Runs after R__01_identity (users OUT001-OUT003, SUP001; teams OUTBOUND_N / OUTBOUND_S) and the
-- 02/03 seeds (accounts ACC-D-0001/0002/0004; customer 3 keeps no cases). References use the seq range 900401+.
--   900401  pool, CREATED, MEDIUM                      900402  pool, CREATED, CRITICAL, SLA overdue (2 days old, 4 h SLA)
--   900403  OUTBOUND_N / OUT001, IN_PROGRESS, HIGH      900404  OUTBOUND_N / OUT002, ASSIGNED, LOW
--   900405  OUTBOUND_N team only, CREATED, MEDIUM       900406  OUTBOUND_S / OUT003, PENDING_CUSTOMER, MEDIUM
--   900407  OUT001, RESOLVED 4 days ago (verification window elapsed → the auto-close job closes it)
--   900408  OUT001, CLOSED 3 days ago (CUSTOMER_CONFIRMED, inside the 7-day reopen window)
--   900409  OUT002, CLOSED 10 days ago (WINDOW_ELAPSED, outside the reopen window)
-- Bookmarks of OUT001: 900403 (seen, unchanged) and 900402 (changed since seen). Repeatable and idempotent: rows are
-- inserted only when missing; case_activity is append-only.

SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';

INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, assigned_team_id,
                   assigned_user_id, response_due_at, sla_due_at, created_at, updated_at, resolved_at,
                   resolved_by_user_id, resolution_remarks, closed_at, closure_type)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, s.id, cat.id, cat.case_type, 'CUSTOMER_PORTAL', k.subject, k.description,
       k.severity, k.status, 'SMS', s.msisdn, creator.id, t.id, owner.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.updated_at, k.resolved_at,
       CASE WHEN k.resolved_at IS NOT NULL THEN owner.id END,
       CASE WHEN k.resolved_at IS NOT NULL THEN 'Fixed and confirmed with the customer by phone.' END,
       k.closed_at, k.closure_type
FROM (
    SELECT '900401' AS seq, 'ACC-D-0002' AS account_ref, '+919800000002' AS creator, 'PAYMENT_MISSING' AS category_code,
           'MEDIUM' AS severity, 'CREATED' AS status, NULL AS team_code, NULL AS owner,
           'Payment not reflected' AS subject, 'I paid my bill yesterday but it still shows as due.' AS description,
           240 AS response_minutes, 2880 AS resolution_minutes,
           @now - INTERVAL 1 DAY AS created_at, @now - INTERVAL 1 DAY AS updated_at,
           CAST(NULL AS DATETIME(6)) AS resolved_at, CAST(NULL AS DATETIME(6)) AS closed_at, CAST(NULL AS CHAR(20)) AS closure_type
    UNION ALL SELECT '900402', 'ACC-D-0004', '+919800000004', 'NO_SIGNAL', 'CRITICAL', 'CREATED', NULL, NULL,
           'No signal since the tower work', 'No network at home since the tower maintenance two days ago.', 30, 240,
           @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900403', 'ACC-D-0001', '+919800000001', 'BILL_DISPUTE', 'HIGH', 'IN_PROGRESS', 'OUTBOUND_N', 'OUT001',
           'Charged twice for data pack', 'The 10 GB data pack was charged twice this month.', 60, 1440,
           @now - INTERVAL 3 DAY, @now - INTERVAL 2 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900404', 'ACC-D-0002', '+919800000002', 'BILL_COPY', 'LOW', 'ASSIGNED', 'OUTBOUND_N', 'OUT002',
           'Need bills for six months', 'Please send copies of the last six bills for my office claim.', 480, 4320,
           @now - INTERVAL 2 DAY, @now - INTERVAL 2 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900405', 'ACC-D-0004', '+919800000004', 'SIM_REPLACE', 'MEDIUM', 'CREATED', 'OUTBOUND_N', NULL,
           'SIM card damaged', 'My SIM card stopped working after it got wet.', 240, 2880,
           @now - INTERVAL 5 HOUR, @now - INTERVAL 5 HOUR, NULL, NULL, NULL
    UNION ALL SELECT '900406', 'ACC-D-0004', '+919800000004', 'SLOW_DATA', 'MEDIUM', 'PENDING_CUSTOMER', 'OUTBOUND_S', 'OUT003',
           'Slow data on second line', 'Data on my second number is slow in the mornings.', 240, 2880,
           @now - INTERVAL 4 DAY, @now - INTERVAL 1 DAY, NULL, NULL, NULL
    UNION ALL SELECT '900407', 'ACC-D-0001', '+919800000001', 'PLAN_QUERY', 'LOW', 'RESOLVED', 'OUTBOUND_N', 'OUT001',
           'How to add international roaming', 'Which pack should I add for a trip to Dubai?', 480, 4320,
           @now - INTERVAL 8 DAY, @now - INTERVAL 4 DAY, @now - INTERVAL 4 DAY, NULL, NULL
    UNION ALL SELECT '900408', 'ACC-D-0001', '+919800000001', 'SLOW_DATA', 'HIGH', 'CLOSED', 'OUTBOUND_N', 'OUT001',
           'Slow data at the weekend', 'Data is very slow every weekend near the market.', 60, 1440,
           @now - INTERVAL 9 DAY, @now - INTERVAL 3 DAY, @now - INTERVAL 6 DAY, @now - INTERVAL 3 DAY, 'CUSTOMER_CONFIRMED'
    UNION ALL SELECT '900409', 'ACC-D-0002', '+919800000002', 'BILL_COPY', 'LOW', 'CLOSED', 'OUTBOUND_N', 'OUT002',
           'Bill copy for March', 'Please email the March bill again.', 480, 4320,
           @now - INTERVAL 20 DAY, @now - INTERVAL 10 DAY, @now - INTERVAL 14 DAY, @now - INTERVAL 10 DAY, 'WINDOW_ELAPSED'
) k
JOIN accounts a ON a.account_ref = k.account_ref
JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
JOIN categories cat ON cat.code = k.category_code
JOIN users creator ON creator.username = k.creator
LEFT JOIN teams t ON t.code = k.team_code
LEFT JOIN users owner ON owner.username = k.owner
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

-- Activity (append-only): CREATED for every seeded case; ASSIGNED (INTERNAL, new = assignee id) for owned ones; the
-- status path for the rest.
INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
WHERE c.reference LIKE 'SR-%-9004__'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'ASSIGNED', 'INTERNAL', c.assigned_user_id, NULL, CAST(c.assigned_user_id AS CHAR), NULL,
       c.created_at + INTERVAL 30 MINUTE
FROM cases c
WHERE c.reference LIKE 'SR-%-9004__' AND c.assigned_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'ASSIGNED');

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'STATUS_CHANGED', 'PUBLIC', c.assigned_user_id, p.old_status, p.new_status, NULL, p.at
FROM cases c
JOIN (
    SELECT c2.id AS case_id, 'CREATED' AS old_status, 'ASSIGNED' AS new_status, c2.created_at + INTERVAL 30 MINUTE AS at
    FROM cases c2 WHERE c2.reference LIKE 'SR-%-9004__' AND c2.assigned_user_id IS NOT NULL
    UNION ALL
    SELECT c2.id, 'ASSIGNED', 'IN_PROGRESS', c2.created_at + INTERVAL 2 HOUR
    FROM cases c2 WHERE c2.reference LIKE 'SR-%-9004__'
      AND c2.status IN ('IN_PROGRESS', 'PENDING_CUSTOMER', 'RESOLVED', 'CLOSED')
    UNION ALL
    SELECT c2.id, 'IN_PROGRESS', 'PENDING_CUSTOMER', c2.updated_at
    FROM cases c2 WHERE c2.reference LIKE 'SR-%-9004__' AND c2.status = 'PENDING_CUSTOMER'
    UNION ALL
    SELECT c2.id, 'IN_PROGRESS', 'RESOLVED', c2.resolved_at
    FROM cases c2 WHERE c2.reference LIKE 'SR-%-9004__' AND c2.status IN ('RESOLVED', 'CLOSED')
    UNION ALL
    SELECT c2.id, 'RESOLVED', 'CLOSED', c2.closed_at
    FROM cases c2 WHERE c2.reference LIKE 'SR-%-9004__' AND c2.status = 'CLOSED'
) p ON p.case_id = c.id
WHERE NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'STATUS_CHANGED');

-- OUT001 bookmarks: 900403 seen after its last change; 900402 changed since seen (OUT-06.4).
INSERT INTO bookmarks (user_id, case_id, last_seen_at, created_at)
SELECT u.id, c.id,
       CASE WHEN c.reference LIKE '%-900402' THEN c.created_at ELSE c.updated_at END,
       c.created_at
FROM cases c
JOIN users u ON u.username = 'OUT001'
WHERE (c.reference LIKE 'SR-%-900402' OR c.reference LIKE 'SR-%-900403')
  AND NOT EXISTS (SELECT 1 FROM bookmarks b WHERE b.user_id = u.id AND b.case_id = c.id);
