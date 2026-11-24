-- V2: identity, RBAC, audit, idempotency and Spring Session tables (design 01-foundation backend §2, §3).
-- BRD §2 (users and teams), §8 (permission matrix), XC-06 / BR-03 (append-only audit), NFR-REL (idempotency).
-- Login failure counters are not stored here; they live in the in-memory limiter (§4.1).
-- All times are UTC DATETIME(6).

CREATE TABLE teams (
    id   BIGINT       NOT NULL AUTO_INCREMENT,
    code VARCHAR(32)  NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teams_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,   -- E.164 mobile (customers) or employee id (staff)
    password_hash VARCHAR(100) NOT NULL,   -- BCrypt
    display_name  VARCHAR(100) NOT NULL,
    user_type     VARCHAR(16)  NOT NULL,
    customer_id   BIGINT       NULL,       -- FK to customers added in slice 02
    team_id       BIGINT       NULL,
    status        VARCHAR(16)  NOT NULL,
    last_login_at DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY ix_users_team (team_id),
    CONSTRAINT fk_users_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT ck_users_user_type CHECK (user_type IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_customer_no_team CHECK (user_type = 'STAFF' OR team_id IS NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY ix_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY ix_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Append-only audit trail (XC-06, 00-overview §6). Triggers below reject UPDATE and DELETE.
CREATE TABLE audit_events (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6) NOT NULL,
    actor_user_id  BIGINT      NULL,
    actor_username VARCHAR(64) NULL,
    action         VARCHAR(64) NOT NULL,
    entity_type    VARCHAR(64) NULL,
    entity_id      VARCHAR(64) NULL,
    outcome        VARCHAR(16) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    client_ip      VARCHAR(45) NOT NULL,   -- fits IPv6
    details        JSON        NULL,
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

-- Idempotency keys (NFR-REL, design §6.1, 00-overview §7).
CREATE TABLE idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    operation       VARCHAR(64)  NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,   -- hex SHA-256 of the canonical request
    status          VARCHAR(16)  NOT NULL,
    response_status INT          NULL,
    response_body   JSON         NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_keys (user_id, operation, idem_key),
    KEY ix_idempotency_keys_created (created_at),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Spring Session JDBC (ADR 0003); copied from spring-session-jdbc 3.5 schema-mysql.sql
-- (spring.session.jdbc.initialize-schema = never).
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
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
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC;

-- Reference data for every environment: roles, permissions and the §3 matrix (BRD §8).
-- Special roles hold only their special permission and are always granted with a base role (§3.1).
INSERT INTO roles (code) VALUES
    ('CUSTOMER'),
    ('INBOUND_AGENT'),
    ('REACTIVATION_APPROVER'),
    ('OUTBOUND_AGENT'),
    ('OUTBOUND_SUPERVISOR'),
    ('BUSINESS_ANALYST'),
    ('BUSINESS_AUDITOR');

INSERT INTO permissions (code) VALUES
    ('customer.profile.read'),
    ('customer.profile.update'),
    ('customer.lookup'),
    ('customer.verify'),
    ('plan.read'),
    ('plan.change'),
    ('promotion.read'),
    ('promotion.claim'),
    ('case.create'),
    ('case.create.onbehalf'),
    ('case.category.internal'),
    ('case.read'),
    ('case.read.queue'),
    ('case.assign'),
    ('case.assign.any'),
    ('case.status.update'),
    ('case.note.internal'),
    ('case.bookmark'),
    ('service.reactivate'),
    ('payment.read'),
    ('payment.initiate'),
    ('analytics.read'),
    ('audit.read'),
    ('reports.export'),
    ('reports.export.pii');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
       (r.code = 'CUSTOMER' AND p.code IN (
            'customer.profile.read', 'customer.profile.update', 'plan.read', 'plan.change', 'promotion.read',
            'promotion.claim', 'case.create', 'case.read', 'payment.read', 'payment.initiate'))
    OR (r.code = 'INBOUND_AGENT' AND p.code IN (
            'customer.profile.read', 'customer.lookup', 'customer.verify', 'plan.read', 'promotion.read',
            'case.create.onbehalf', 'case.category.internal', 'case.read', 'case.note.internal', 'payment.read'))
    OR (r.code = 'REACTIVATION_APPROVER' AND p.code IN ('service.reactivate'))
    OR (r.code = 'OUTBOUND_AGENT' AND p.code IN (
            'case.read', 'case.read.queue', 'case.assign', 'case.status.update', 'case.note.internal',
            'case.bookmark', 'analytics.read'))
    OR (r.code = 'OUTBOUND_SUPERVISOR' AND p.code IN ('case.assign.any'))
    OR (r.code = 'BUSINESS_ANALYST' AND p.code IN ('analytics.read', 'audit.read', 'reports.export'))
    OR (r.code = 'BUSINESS_AUDITOR' AND p.code IN ('reports.export.pii'));
