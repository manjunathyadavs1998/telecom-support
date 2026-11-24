-- Dev/test demo identities. Every demo user has the password: Password@123
-- Repeatable + idempotent (flyway-migration skill).

INSERT INTO teams (code, name) VALUES
    ('INBOUND-BLR', 'Inbound Care Bengaluru'),
    ('OUTBOUND-BLR', 'Outbound Resolution Bengaluru'),
    ('OUTBOUND-HYD', 'Outbound Resolution Hyderabad'),
    ('BUSINESS-OPS', 'Business Operations') AS new
ON DUPLICATE KEY UPDATE name = new.name;

SET @pwd = '$2a$10$4//ohw58A7FSan6vQCJgruA9p7VtzKKbsKTPmW/kofHPcGRn.dmg2';

INSERT INTO users (username, password_hash, display_name, user_type, team_id) VALUES
    ('+919876543210', @pwd, 'John Doe',        'CUSTOMER', NULL),
    ('+919876500001', @pwd, 'Priya Sharma',    'CUSTOMER', NULL),
    ('+919876500002', @pwd, 'Rahul Verma',     'CUSTOMER', NULL),
    ('+919876500003', @pwd, 'Neha Singh',      'CUSTOMER', NULL),
    ('inbound1',  @pwd, 'Asha Rao (Inbound)',          'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND-BLR')),
    ('inbound2',  @pwd, 'Vikram Iyer (Inbound)',       'STAFF', (SELECT id FROM teams WHERE code = 'INBOUND-BLR')),
    ('outbound1', @pwd, 'Meera Nair (Outbound)',       'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND-BLR')),
    ('outbound2', @pwd, 'Arjun Das (Outbound)',        'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND-BLR')),
    ('outbound3', @pwd, 'Kiran Reddy (Outbound)',      'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND-HYD')),
    ('supervisor1', @pwd, 'Sunita Menon (Supervisor)', 'STAFF', (SELECT id FROM teams WHERE code = 'OUTBOUND-BLR')),
    ('business1', @pwd, 'Rohan Gupta (Business)',      'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS-OPS')),
    ('auditor1',  @pwd, 'Fatima Khan (Auditor)',       'STAFF', (SELECT id FROM teams WHERE code = 'BUSINESS-OPS')) AS new
ON DUPLICATE KEY UPDATE display_name = new.display_name, team_id = new.team_id;

INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON
    (u.user_type = 'CUSTOMER' AND r.code = 'CUSTOMER')
 OR (u.username = 'inbound1' AND r.code IN ('INBOUND_AGENT', 'REACTIVATION_APPROVER'))
 OR (u.username = 'inbound2' AND r.code IN ('INBOUND_AGENT'))
 OR (u.username IN ('outbound1', 'outbound2', 'outbound3') AND r.code IN ('OUTBOUND_AGENT'))
 OR (u.username = 'supervisor1' AND r.code IN ('OUTBOUND_AGENT', 'OUTBOUND_SUPERVISOR'))
 OR (u.username = 'business1' AND r.code IN ('BUSINESS_ANALYST'))
 OR (u.username = 'auditor1' AND r.code IN ('BUSINESS_ANALYST', 'BUSINESS_AUDITOR'));
