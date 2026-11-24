-- V2: identity, RBAC and audit foundation.
-- BRD §2 (roles), §8 (permission matrix), BR-03/XC-06 (audit), BR-10 (server-side authorization), ADR 0003.

CREATE TABLE teams (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(40)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teams_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL,                 -- E.164 mobile for customers, employee id for staff
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(120) NOT NULL,
    user_type       VARCHAR(20)  NOT NULL,
    customer_id     BIGINT       NULL,                     -- FK added when customers table exists (V3)
    team_id         BIGINT       NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_logins   INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME(6)  NULL,
    last_login_at   DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY ix_users_customer (customer_id),
    KEY ix_users_team (team_id),
    CONSTRAINT fk_users_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT ck_users_type CHECK (user_type IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(40)  NOT NULL,
    description VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(60)  NOT NULL,
    description VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY ix_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY ix_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Immutable audit trail (XC-06, INB-04.7, BUS-03.4). Append-only, enforced by triggers below.
CREATE TABLE audit_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    actor_user_id  BIGINT       NULL,
    actor_username VARCHAR(64)  NULL,
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(40)  NULL,
    entity_id      VARCHAR(64)  NULL,
    outcome        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    correlation_id VARCHAR(64)  NULL,
    client_ip      VARCHAR(45)  NULL,
    details        JSON         NULL,
    PRIMARY KEY (id),
    KEY ix_audit_events_occurred (occurred_at),
    KEY ix_audit_events_entity (entity_type, entity_id),
    KEY ix_audit_events_actor (actor_user_id, occurred_at),
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_audit_events_no_update BEFORE UPDATE ON audit_events
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';

CREATE TRIGGER trg_audit_events_no_delete BEFORE DELETE ON audit_events
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_events is append-only';

-- Idempotency keys (NFR-REL, CUS-06.6, CUS-08.4): one stored response per (user, key, operation).
CREATE TABLE idempotency_keys (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    idem_key        VARCHAR(100)  NOT NULL,
    operation       VARCHAR(60)   NOT NULL,
    request_hash    CHAR(64)      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    response_status INT           NULL,
    response_body   JSON          NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (user_id, operation, idem_key),
    KEY ix_idempotency_created (created_at),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Spring Session JDBC (ADR 0003). Schema from spring-session-jdbc schema-mysql.sql.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC;

-- Reference data needed in every environment: roles and permissions (BRD §8). Users are seeded per environment.
INSERT INTO roles (code, description) VALUES
    ('CUSTOMER', 'Self-service postpaid customer'),
    ('INBOUND_AGENT', 'Inbound assistant handling live customer queries'),
    ('REACTIVATION_APPROVER', 'Special permission: temporary service reactivation (INB-04)'),
    ('OUTBOUND_AGENT', 'Outbound assistant owning case follow-up'),
    ('OUTBOUND_SUPERVISOR', 'Outbound lead allowed to reassign cases'),
    ('BUSINESS_ANALYST', 'Business team: aggregate analytics, audit view, non-PII export'),
    ('BUSINESS_AUDITOR', 'Business user allowed to export PII fields');

INSERT INTO permissions (code, description) VALUES
    ('customer.profile.read', 'View customer profile (own, or verified customer for agents)'),
    ('customer.profile.update', 'Edit allowed contact fields of own profile'),
    ('customer.lookup', 'Search customers by mobile number'),
    ('customer.verify', 'Run identity verification for a customer'),
    ('plan.read', 'View plan details'),
    ('plan.change', 'Request a plan change for own account'),
    ('promotion.read', 'View eligible promotions'),
    ('promotion.claim', 'Claim an eligible promotion'),
    ('case.create', 'Create a service request for own account'),
    ('case.create.onbehalf', 'Create a service request on behalf of a verified customer'),
    ('case.category.internal', 'Use internal-only case categories'),
    ('case.read', 'View cases within scope'),
    ('case.read.queue', 'View the operational case queue'),
    ('case.assign', 'Claim unassigned cases'),
    ('case.assign.any', 'Reassign cases owned by other agents'),
    ('case.status.update', 'Move cases through lifecycle states'),
    ('case.note.internal', 'Read and write internal case notes'),
    ('case.bookmark', 'Bookmark cases for personal follow-up'),
    ('service.reactivate', 'Request temporary 24h service reactivation'),
    ('payment.read', 'View payment history within scope'),
    ('payment.initiate', 'Initiate a bill payment for own account'),
    ('analytics.read', 'View aggregate case analytics'),
    ('audit.read', 'View case lifecycle audit history'),
    ('reports.export', 'Export approved reports (non-PII columns)'),
    ('reports.export.pii', 'Include customer-identifying columns in exports');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON
    (r.code = 'CUSTOMER' AND p.code IN ('customer.profile.read', 'customer.profile.update', 'plan.read', 'plan.change',
        'promotion.read', 'promotion.claim', 'case.create', 'case.read', 'payment.read', 'payment.initiate'))
 OR (r.code = 'INBOUND_AGENT' AND p.code IN ('customer.lookup', 'customer.verify', 'customer.profile.read', 'plan.read',
        'promotion.read', 'case.create.onbehalf', 'case.category.internal', 'case.read', 'case.note.internal', 'payment.read'))
 OR (r.code = 'REACTIVATION_APPROVER' AND p.code IN ('service.reactivate'))
 OR (r.code = 'OUTBOUND_AGENT' AND p.code IN ('case.read', 'case.read.queue', 'case.assign', 'case.status.update',
        'case.note.internal', 'case.bookmark', 'analytics.read'))
 OR (r.code = 'OUTBOUND_SUPERVISOR' AND p.code IN ('case.assign.any'))
 OR (r.code = 'BUSINESS_ANALYST' AND p.code IN ('analytics.read', 'audit.read', 'reports.export'))
 OR (r.code = 'BUSINESS_AUDITOR' AND p.code IN ('reports.export.pii'));
