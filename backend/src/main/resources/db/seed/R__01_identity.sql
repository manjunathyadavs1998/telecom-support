-- DEV ONLY (loaded by the `dev` profile via classpath:db/seed). Design 01-foundation backend §2, §3.1.
-- 4 teams and the 12 seed users. Shared dev password for every user: Password@123 (BCrypt cost 10 below).
-- Repeatable and idempotent: rerunning upserts users and resets their roles to exactly the list below.

INSERT INTO teams (code, name) VALUES
    ('INBOUND',    'Inbound'),
    ('OUTBOUND_N', 'Outbound North'),
    ('OUTBOUND_S', 'Outbound South'),
    ('BUSINESS',   'Business') AS new
ON DUPLICATE KEY UPDATE name = new.name;

SET @dev_pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';
SET @now = UTC_TIMESTAMP(6);

INSERT INTO users (username, password_hash, display_name, user_type, team_id, status, created_at, updated_at) VALUES
    ('+919800000001', @dev_pwd, 'Dev Customer 1',  'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('+919800000002', @dev_pwd, 'Dev Customer 2',  'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('+919800000003', @dev_pwd, 'Dev Customer 3',  'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('+919800000004', @dev_pwd, 'Dev Customer 4',  'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('IN001',  @dev_pwd, 'Inbound Agent 1',         'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND'),    'ACTIVE', @now, @now),
    ('IN002',  @dev_pwd, 'Inbound Agent 2',         'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND'),    'ACTIVE', @now, @now),
    ('OUT001', @dev_pwd, 'Outbound Agent 1',        'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('OUT002', @dev_pwd, 'Outbound Agent 2',        'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('OUT003', @dev_pwd, 'Outbound Agent 3',        'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_S'), 'ACTIVE', @now, @now),
    ('SUP001', @dev_pwd, 'Outbound Supervisor 1',   'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('BUS001', @dev_pwd, 'Business Analyst 1',      'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS'),   'ACTIVE', @now, @now),
    ('AUD001', @dev_pwd, 'Business Auditor 1',      'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS'),   'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash,
    display_name  = new.display_name,
    user_type     = new.user_type,
    team_id       = new.team_id,
    status        = new.status,
    updated_at    = new.updated_at;

DELETE ur FROM user_roles ur
JOIN users u ON u.id = ur.user_id
WHERE u.username IN ('+919800000001', '+919800000002', '+919800000003', '+919800000004',
                     'IN001', 'IN002', 'OUT001', 'OUT002', 'OUT003', 'SUP001', 'BUS001', 'AUD001');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON
       (u.username IN ('+919800000001', '+919800000002', '+919800000003', '+919800000004') AND r.code = 'CUSTOMER')
    OR (u.username = 'IN001' AND r.code = 'INBOUND_AGENT')
    OR (u.username = 'IN002' AND r.code IN ('INBOUND_AGENT', 'REACTIVATION_APPROVER'))
    OR (u.username IN ('OUT001', 'OUT002', 'OUT003') AND r.code = 'OUTBOUND_AGENT')
    OR (u.username = 'SUP001' AND r.code IN ('OUTBOUND_AGENT', 'OUTBOUND_SUPERVISOR'))
    OR (u.username = 'BUS001' AND r.code = 'BUSINESS_ANALYST')
    OR (u.username = 'AUD001' AND r.code IN ('BUSINESS_ANALYST', 'BUSINESS_AUDITOR'));
