-- V5: case categories, SLA policies, cases and the append-only case activity (design 02-customer backend §2). Module: servicecase.
-- BRD CUS-05/CUS-06, BR-01 (reference), BR-04 (activity visibility), BR-07 (configurable SLA), §14 #4 (severity by category),
-- §14 #6 (placeholder SLA values). All times are UTC DATETIME(6).

-- Two levels (top level + subcategory); the depth is checked by the service, not the schema.
CREATE TABLE categories (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(32)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    parent_id        BIGINT       NULL,
    audience         VARCHAR(16)  NOT NULL,
    case_type        VARCHAR(16)  NOT NULL,
    default_severity VARCHAR(16)  NULL,
    active           BOOLEAN      NOT NULL,
    sort_order       INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_code (code),
    KEY ix_categories_parent (parent_id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT ck_categories_audience CHECK (audience IN ('CUSTOMER', 'INTERNAL')),
    CONSTRAINT ck_categories_case_type CHECK (case_type IN ('REQUEST', 'COMPLAINT')),
    CONSTRAINT ck_categories_default_severity
        CHECK (default_severity IS NULL OR default_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- SLA per (category, severity) (BR-07). Lookup is leaf first, then parent (R10).
CREATE TABLE sla_policies (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    category_id        BIGINT      NOT NULL,
    severity           VARCHAR(16) NOT NULL,
    response_minutes   INT         NOT NULL,
    resolution_minutes INT         NOT NULL,
    active             BOOLEAN     NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sla_policies (category_id, severity),
    CONSTRAINT fk_sla_policies_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_sla_policies_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_sla_policies_response CHECK (response_minutes > 0),
    CONSTRAINT ck_sla_policies_resolution CHECK (resolution_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- category_id is always the leaf (R10).
CREATE TABLE cases (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    reference          VARCHAR(24)  NOT NULL,
    account_id         BIGINT       NOT NULL,
    subscription_id    BIGINT       NULL,
    category_id        BIGINT       NOT NULL,
    case_type          VARCHAR(16)  NOT NULL,
    channel            VARCHAR(16)  NOT NULL,
    subject            VARCHAR(150) NOT NULL,
    description        TEXT         NOT NULL,
    severity           VARCHAR(16)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    preferred_contact  VARCHAR(8)   NOT NULL,
    contact_msisdn     VARCHAR(16)  NOT NULL,
    created_by_user_id BIGINT       NOT NULL,
    assigned_team_id   BIGINT       NULL,
    assigned_user_id   BIGINT       NULL,
    response_due_at    DATETIME(6)  NOT NULL,
    sla_due_at         DATETIME(6)  NOT NULL,
    version            INT          NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cases_reference (reference),
    KEY ix_cases_account_created (account_id, created_at),
    KEY ix_cases_subscription (subscription_id),
    KEY ix_cases_category (category_id),
    KEY ix_cases_created_by (created_by_user_id),
    KEY ix_cases_assigned_team (assigned_team_id),
    KEY ix_cases_assigned_user (assigned_user_id),
    CONSTRAINT fk_cases_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_cases_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT fk_cases_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_cases_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_cases_assigned_team FOREIGN KEY (assigned_team_id) REFERENCES teams (id),
    CONSTRAINT fk_cases_assigned_user FOREIGN KEY (assigned_user_id) REFERENCES users (id),
    CONSTRAINT ck_cases_case_type CHECK (case_type IN ('REQUEST', 'COMPLAINT')),
    CONSTRAINT ck_cases_channel CHECK (channel IN ('CUSTOMER_PORTAL', 'INBOUND_AGENT')),
    CONSTRAINT ck_cases_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_cases_status CHECK (status IN ('CREATED', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_CUSTOMER',
                                                 'PENDING_EXTERNAL', 'RESOLVED', 'CLOSED', 'REOPENED')),
    CONSTRAINT ck_cases_preferred_contact CHECK (preferred_contact IN ('PHONE', 'SMS', 'EMAIL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Case history (BR-04 visibility). Append-only: triggers below reject UPDATE and DELETE.
CREATE TABLE case_activity (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    case_id       BIGINT       NOT NULL,
    activity_type VARCHAR(16)  NOT NULL,
    visibility    VARCHAR(8)   NOT NULL,
    actor_user_id BIGINT       NULL,
    old_value     VARCHAR(64)  NULL,
    new_value     VARCHAR(64)  NULL,
    note_text     TEXT         NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_case_activity_case_created (case_id, created_at),
    KEY ix_case_activity_actor (actor_user_id),
    CONSTRAINT fk_case_activity_case FOREIGN KEY (case_id) REFERENCES cases (id),
    CONSTRAINT fk_case_activity_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_case_activity_type CHECK (activity_type IN ('CREATED', 'STATUS_CHANGED', 'ASSIGNED', 'NOTE')),
    CONSTRAINT ck_case_activity_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_case_activity_no_update BEFORE UPDATE ON case_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'case_activity is append-only';

CREATE TRIGGER trg_case_activity_no_delete BEFORE DELETE ON case_activity
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'case_activity is append-only';

-- Reference data for every environment (§14 #6, OQ 5): PLACEHOLDER customer categories and SLA values until the business
-- approves the real set, which a later migration will replace. Every leaf resolves a severity (its own default, else its
-- parent's) and an active SLA row for that severity (leaf row first, else parent row), as R10 requires.
SET @now = UTC_TIMESTAMP(6);

INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active, sort_order,
                        created_at, updated_at) VALUES
    ('BILLING',       'Billing and payments', NULL, 'CUSTOMER', 'REQUEST',   'MEDIUM', TRUE, 10, @now, @now),
    ('NETWORK',       'Network and coverage', NULL, 'CUSTOMER', 'COMPLAINT', 'HIGH',   TRUE, 20, @now, @now),
    ('PLAN_SERVICES', 'Plans and services',   NULL, 'CUSTOMER', 'REQUEST',   'LOW',    TRUE, 30, @now, @now),
    ('SIM_DEVICE',    'SIM and device',       NULL, 'CUSTOMER', 'REQUEST',   'MEDIUM', TRUE, 40, @now, @now),
    ('OTHER',         'Something else',       NULL, 'CUSTOMER', 'REQUEST',   'LOW',    TRUE, 90, @now, @now);

INSERT INTO categories (code, name, parent_id, audience, case_type, default_severity, active, sort_order,
                        created_at, updated_at)
SELECT c.code, c.name, p.id, 'CUSTOMER', c.case_type, c.default_severity, TRUE, c.sort_order, @now, @now
FROM (
    SELECT 'BILL_DISPUTE' AS code, 'Dispute a charge' AS name, 'BILLING' AS parent_code, 'COMPLAINT' AS case_type,
           'HIGH' AS default_severity, 10 AS sort_order
    UNION ALL SELECT 'BILL_COPY',        'Copy of a bill',              'BILLING',       'REQUEST',   'LOW',      20
    UNION ALL SELECT 'PAYMENT_MISSING',  'Payment not reflected',       'BILLING',       'COMPLAINT', NULL,       30
    UNION ALL SELECT 'NO_SIGNAL',        'No signal',                   'NETWORK',       'COMPLAINT', 'CRITICAL', 10
    UNION ALL SELECT 'SLOW_DATA',        'Slow mobile data',            'NETWORK',       'COMPLAINT', NULL,       20
    UNION ALL SELECT 'CALL_DROPS',       'Calls dropping',              'NETWORK',       'COMPLAINT', NULL,       30
    UNION ALL SELECT 'PLAN_QUERY',       'Question about my plan',      'PLAN_SERVICES', 'REQUEST',   NULL,       10
    UNION ALL SELECT 'VAS_DEACTIVATE',   'Stop a value-added service',  'PLAN_SERVICES', 'REQUEST',   'MEDIUM',   20
    UNION ALL SELECT 'ROAMING',          'International roaming',       'PLAN_SERVICES', 'REQUEST',   NULL,       30
    UNION ALL SELECT 'SIM_REPLACE',      'Replace a lost or damaged SIM', 'SIM_DEVICE',  'REQUEST',   'HIGH',     10
    UNION ALL SELECT 'PORT_OUT',         'Port my number out',          'SIM_DEVICE',    'REQUEST',   NULL,       20
) c
JOIN categories p ON p.code = c.parent_code;

-- Placeholder minutes per severity: CRITICAL 30/240, HIGH 60/1440, MEDIUM 240/2880, LOW 480/4320.
-- Parent rows cover leaves that inherit the parent's severity; leaf rows cover leaves with their own severity.
INSERT INTO sla_policies (category_id, severity, response_minutes, resolution_minutes, active, created_at, updated_at)
SELECT c.id, s.severity, s.response_minutes, s.resolution_minutes, TRUE, @now, @now
FROM (
    SELECT 'BILLING' AS code, 'MEDIUM' AS severity, 240 AS response_minutes, 2880 AS resolution_minutes
    UNION ALL SELECT 'NETWORK',        'HIGH',     60, 1440
    UNION ALL SELECT 'PLAN_SERVICES',  'LOW',     480, 4320
    UNION ALL SELECT 'SIM_DEVICE',     'MEDIUM',  240, 2880
    UNION ALL SELECT 'OTHER',          'LOW',     480, 4320
    UNION ALL SELECT 'BILL_DISPUTE',   'HIGH',     60, 1440
    UNION ALL SELECT 'BILL_COPY',      'LOW',     480, 4320
    UNION ALL SELECT 'NO_SIGNAL',      'CRITICAL', 30,  240
    UNION ALL SELECT 'VAS_DEACTIVATE', 'MEDIUM',  240, 2880
    UNION ALL SELECT 'SIM_REPLACE',    'HIGH',     60, 1440
) s
JOIN categories c ON c.code = s.code;
