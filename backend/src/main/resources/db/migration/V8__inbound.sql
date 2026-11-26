-- V8: customer lookups, agent identity checks, the severity override permission, placeholder INTERNAL categories and
-- complete SLA coverage (design 03-inbound backend §2; 01-foundation backend §13 E3). Modules: customer, identity,
-- servicecase. BRD INB-01.1/01.2 (search, identity check), INB-03.2 (internal categories), BR-07 (SLA per category and
-- severity), §14 #4 (severity override with permission). All times are UTC DATETIME(6).

-- A search hit (R1): the agent may start an identity check for this customer with the token until expires_at (+15 min).
-- token_hash = SHA-256 (hex) of a 128-bit random token; the token itself is never stored. Purged after 24 h.
CREATE TABLE customer_lookups (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    agent_user_id BIGINT      NOT NULL,
    customer_id   BIGINT      NOT NULL,
    token_hash    CHAR(64)    NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_lookups_token (token_hash),
    KEY ix_customer_lookups_agent_customer (agent_user_id, customer_id, expires_at),
    CONSTRAINT fk_customer_lookups_agent FOREIGN KEY (agent_user_id) REFERENCES users (id),
    CONSTRAINT fk_customer_lookups_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Agent identity check (R2–R4): OTP to the primary MSISDN (code_hmac, expires_at) or KBA (evaluated at once, never
-- PENDING). VERIFIED grants the agent access to the customer until valid_until (R5).
CREATE TABLE identity_checks (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    agent_user_id    BIGINT      NOT NULL,
    customer_id      BIGINT      NOT NULL,
    method           VARCHAR(8)  NOT NULL,
    status           VARCHAR(16) NOT NULL,
    code_hmac        CHAR(64)    NULL,
    attempts         TINYINT     NOT NULL DEFAULT 0,
    expires_at       DATETIME(6) NULL,
    verified_at      DATETIME(6) NULL,
    valid_until      DATETIME(6) NULL,
    access_logged_at DATETIME(6) NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_identity_checks_agent_customer (agent_user_id, customer_id, status),
    KEY ix_identity_checks_customer_method (customer_id, method, created_at),
    KEY ix_identity_checks_agent_method (agent_user_id, method, created_at),
    CONSTRAINT fk_identity_checks_agent FOREIGN KEY (agent_user_id) REFERENCES users (id),
    CONSTRAINT fk_identity_checks_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_identity_checks_method CHECK (method IN ('OTP', 'KBA')),
    CONSTRAINT ck_identity_checks_status
        CHECK (status IN ('PENDING', 'CHECKING', 'VERIFIED', 'FAILED', 'EXPIRED', 'LOCKED', 'SUPERSEDED')),
    CONSTRAINT ck_identity_checks_attempts CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT ck_identity_checks_otp_hmac CHECK ((method = 'OTP') = (code_hmac IS NOT NULL)),
    CONSTRAINT ck_identity_checks_pending_expiry
        CHECK (status <> 'PENDING' OR (method = 'OTP' AND expires_at IS NOT NULL)),
    CONSTRAINT ck_identity_checks_kba_status
        CHECK (method <> 'KBA' OR status IN ('CHECKING', 'VERIFIED', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_identity_checks_verified_until CHECK (status <> 'VERIFIED' OR valid_until IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 01 §13 E3 (03 OQ 1): agent severity override on case-on-behalf, INBOUND_AGENT only. V2 is never edited.
INSERT INTO permissions (code) VALUES ('case.severity.override');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p ON p.code = 'case.severity.override'
WHERE r.code = 'INBOUND_AGENT';

-- Reference data for every environment: PLACEHOLDER INTERNAL categories (OQ 6: customer-safe names, the customer sees
-- them in CUS-05) until the business approves the real set.
SET @now = UTC_TIMESTAMP(6);

INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active, sort_order,
                        created_at, updated_at) VALUES
    ('INT_BACKOFFICE', 'Account services',    NULL, 'INTERNAL', 'REQUEST',   'MEDIUM', TRUE, 100, @now, @now),
    ('INT_ESCALATION', 'Escalated complaint', NULL, 'INTERNAL', 'COMPLAINT', 'HIGH',   TRUE, 110, @now, @now);

INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active, sort_order,
                        created_at, updated_at)
SELECT c.code, c.name, p.id, 'INTERNAL', c.case_type, c.default_severity, TRUE, c.sort_order, @now, @now
FROM (
    SELECT 'INT_BILL_ADJUST' AS code, 'Bill adjustment' AS name, 'INT_BACKOFFICE' AS parent_code,
           'REQUEST' AS case_type, NULL AS default_severity, 10 AS sort_order
    UNION ALL SELECT 'INT_PROVISIONING', 'Service activation', 'INT_BACKOFFICE', 'REQUEST', 'HIGH', 20
) c
JOIN categories p ON p.code = c.parent_code;

-- R11 / BR-07: an agent may override the severity, so every (active top-level category, severity) pair gets an SLA row
-- when it has none; with the leaf-first-then-parent lookup every leaf resolves all four severities. Placeholder minutes
-- as V5: CRITICAL 30/240, HIGH 60/1440, MEDIUM 240/2880, LOW 480/4320.
INSERT INTO sla_policies (category_id, severity, response_minutes, resolution_minutes, active, created_at, updated_at)
SELECT c.id, s.severity, s.response_minutes, s.resolution_minutes, TRUE, @now, @now
FROM categories c
CROSS JOIN (
    SELECT 'CRITICAL' AS severity, 30 AS response_minutes, 240 AS resolution_minutes
    UNION ALL SELECT 'HIGH',     60, 1440
    UNION ALL SELECT 'MEDIUM',  240, 2880
    UNION ALL SELECT 'LOW',     480, 4320
) s
WHERE c.parent_id IS NULL
  AND c.active
  AND NOT EXISTS (SELECT 1 FROM sla_policies x WHERE x.category_id = c.id AND x.severity = s.severity);
