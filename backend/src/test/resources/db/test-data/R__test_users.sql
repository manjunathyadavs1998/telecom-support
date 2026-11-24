-- TESTS ONLY. Loaded by AbstractMySqlIT after the versioned migrations (design 01-foundation backend §2, §3.1, §8).
-- One user per §3.1 role combination. Tests never read db/seed.
-- Password for every test user: Password@123 (BCrypt cost 10 below).

INSERT INTO teams (code, name) VALUES
    ('INBOUND',    'Inbound'),
    ('OUTBOUND_N', 'Outbound North'),
    ('OUTBOUND_S', 'Outbound South'),
    ('BUSINESS',   'Business') AS new
ON DUPLICATE KEY UPDATE name = new.name;

SET @test_pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';
SET @now = UTC_TIMESTAMP(6);

INSERT INTO users (username, password_hash, display_name, user_type, team_id, status, created_at, updated_at) VALUES
    ('+919900000001', @test_pwd, 'Test Customer',            'CUSTOMER', NULL, 'ACTIVE', @now, @now),
    ('T-IN',          @test_pwd, 'Test Inbound Agent',       'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND'),    'ACTIVE', @now, @now),
    ('T-IN-REACT',    @test_pwd, 'Test Inbound Approver',    'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND'),    'ACTIVE', @now, @now),
    ('T-OUT',         @test_pwd, 'Test Outbound Agent',      'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('T-OUT-SUP',     @test_pwd, 'Test Outbound Supervisor', 'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND_N'), 'ACTIVE', @now, @now),
    ('T-BUS',         @test_pwd, 'Test Business Analyst',    'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS'),   'ACTIVE', @now, @now),
    ('T-BUS-AUD',     @test_pwd, 'Test Business Auditor',    'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS'),   'ACTIVE', @now, @now) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash,
    display_name  = new.display_name,
    user_type     = new.user_type,
    team_id       = new.team_id,
    status        = new.status,
    updated_at    = new.updated_at;

DELETE ur FROM user_roles ur
JOIN users u ON u.id = ur.user_id
WHERE u.username IN ('+919900000001', 'T-IN', 'T-IN-REACT', 'T-OUT', 'T-OUT-SUP', 'T-BUS', 'T-BUS-AUD');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON
       (u.username = '+919900000001' AND r.code = 'CUSTOMER')
    OR (u.username = 'T-IN'          AND r.code = 'INBOUND_AGENT')
    OR (u.username = 'T-IN-REACT'    AND r.code IN ('INBOUND_AGENT', 'REACTIVATION_APPROVER'))
    OR (u.username = 'T-OUT'         AND r.code = 'OUTBOUND_AGENT')
    OR (u.username = 'T-OUT-SUP'     AND r.code IN ('OUTBOUND_AGENT', 'OUTBOUND_SUPERVISOR'))
    OR (u.username = 'T-BUS'         AND r.code = 'BUSINESS_ANALYST')
    OR (u.username = 'T-BUS-AUD'     AND r.code IN ('BUSINESS_ANALYST', 'BUSINESS_AUDITOR'));
