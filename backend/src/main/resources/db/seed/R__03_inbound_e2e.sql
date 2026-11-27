-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed, so also by `dev,e2e`; runs after R__03_inbound).
-- Inbound workspace e2e data (design 03-inbound backend §2, as 02 erratum E5), dates relative to NOW():
--   * customer 1 (+919800000001, ACC-D-0001): two INTERNAL-category cases created on the inbound channel by IN001,
--     each with one INTERNAL note (BR-04: shown to agents with case.note.internal only, never on customer endpoints).
-- The suspended/fraud/inactive customers 6–8 and their KBA answers are in R__03_inbound.sql.
-- The e2e profile starts from a clean database every run; in plain dev this file is repeatable and idempotent (rows are
-- inserted only when missing; case_activity is append-only, nothing is deleted from it).

SET @now = UTC_TIMESTAMP(6);
SET @ist = '+05:30';

-- ---------------------------------------------------------------- customer 1: INTERNAL-category cases
-- References use the seq range 900201+ so they never meet generated ones or the 02 seed cases.
INSERT INTO cases (reference, account_id, subscription_id, category_id, case_type, channel, subject, description,
                   severity, status, preferred_contact, contact_msisdn, created_by_user_id, response_due_at, sla_due_at,
                   created_at, updated_at)
SELECT CONCAT('SR-', DATE_FORMAT(CONVERT_TZ(k.created_at, '+00:00', @ist), '%Y%m%d'), '-', k.seq),
       a.id, s.id, cat.id, k.case_type, 'INBOUND_AGENT', k.subject, k.description,
       k.severity, k.status, 'PHONE', s.msisdn, u.id,
       k.created_at + INTERVAL k.response_minutes MINUTE, k.created_at + INTERVAL k.resolution_minutes MINUTE,
       k.created_at, k.created_at
FROM (
    SELECT '900201' AS seq, 'INT_BILL_ADJUST' AS category_code, 'REQUEST' AS case_type, 'MEDIUM' AS severity,
           'IN_PROGRESS' AS status, 'Bill adjustment for a roaming charge' AS subject,
           'Customer called about a roaming charge on the last bill; adjustment requested.' AS description,
           240 AS response_minutes, 2880 AS resolution_minutes, @now - INTERVAL 6 DAY AS created_at
    UNION ALL SELECT '900202', 'INT_ESCALATION', 'COMPLAINT', 'HIGH', 'CREATED', 'Escalation: repeated slow data',
           'Customer called again about slow evening data after the earlier request.', 60, 1440,
           @now - INTERVAL 2 DAY
) k
JOIN accounts a ON a.account_ref = 'ACC-D-0001'
JOIN subscriptions s ON s.account_id = a.id AND s.is_primary
JOIN categories cat ON cat.code = k.category_code
JOIN users u ON u.username = 'IN001'
WHERE NOT EXISTS (SELECT 1 FROM cases x WHERE x.account_id = a.id AND x.subject = k.subject);

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'CREATED', 'PUBLIC', c.created_by_user_id, NULL, 'CREATED', NULL, c.created_at
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref = 'ACC-D-0001' AND c.channel = 'INBOUND_AGENT'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'CREATED');

INSERT INTO case_activity (case_id, activity_type, visibility, actor_user_id, old_value, new_value, note_text, created_at)
SELECT c.id, 'NOTE', 'INTERNAL', c.created_by_user_id, NULL, NULL,
       'Internal: identity verified by OTP; customer was calm, check the earlier request first.',
       c.created_at + INTERVAL 1 MINUTE
FROM cases c
JOIN accounts a ON a.id = c.account_id
WHERE a.account_ref = 'ACC-D-0001' AND c.channel = 'INBOUND_AGENT'
  AND NOT EXISTS (SELECT 1 FROM case_activity x WHERE x.case_id = c.id AND x.activity_type = 'NOTE');
