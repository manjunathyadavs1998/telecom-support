-- V9: temporary reactivation and the customer notification outbox (design 03-inbound backend §2, §3 R13–R16, §3.1).
-- Modules: reactivation, notification. BRD INB-04.1/04.2 (eligibility by suspension reason, never FRAUD/LEGAL/REGULATORY),
-- INB-04.5 (automatic end, max 24 h), INB-04.7 (customer notification), NFR-REL. All times are UTC DATETIME(6).

-- Eligibility and maximum duration per suspension reason (OQ 2 placeholders). FRAUD/LEGAL/REGULATORY can never be made
-- eligible (also hard-coded in R13).
CREATE TABLE reactivation_policies (
    suspension_reason VARCHAR(16) NOT NULL,
    eligible          BOOLEAN     NOT NULL,
    max_hours         TINYINT     NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (suspension_reason),
    CONSTRAINT ck_reactivation_policies_reason CHECK (suspension_reason IN
        ('NON_PAYMENT', 'CUSTOMER_REQUEST', 'KYC_PENDING', 'FRAUD', 'LEGAL', 'REGULATORY', 'OTHER')),
    CONSTRAINT ck_reactivation_policies_max_hours CHECK (max_hours BETWEEN 1 AND 24),
    CONSTRAINT ck_reactivation_policies_prohibited
        CHECK (NOT (suspension_reason IN ('FRAUD', 'LEGAL', 'REGULATORY') AND eligible))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

SET @now = UTC_TIMESTAMP(6);

INSERT INTO reactivation_policies (suspension_reason, eligible, max_hours, created_at, updated_at) VALUES
    ('NON_PAYMENT',      TRUE,  24, @now, @now),
    ('CUSTOMER_REQUEST', FALSE, 24, @now, @now),
    ('KYC_PENDING',      FALSE, 24, @now, @now),
    ('FRAUD',            FALSE, 24, @now, @now),
    ('LEGAL',            FALSE, 24, @now, @now),
    ('REGULATORY',       FALSE, 24, @now, @now),
    ('OTHER',            FALSE, 24, @now, @now);

-- A temporary restoration of a suspended account (reference REA-…). Status changes only through the guarded transitions
-- of §3.1 (ReactivationTransitionService); accounts/subscriptions are never changed by a reactivation (R15).
-- suspension_reason and msisdns are snapshots taken at creation. attempts/next_attempt_at drive the unlimited reconcile
-- and suspend retries; needs_attention is an alert flag, not a state (cleared by the next transition).
-- open_account_id: at most one open (PENDING, ACTIVE, EXPIRING) request per account.
CREATE TABLE reactivation_requests (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    reference            VARCHAR(24)  NOT NULL,
    account_id           BIGINT       NOT NULL,
    suspension_reason    VARCHAR(16)  NOT NULL,
    msisdns              JSON         NOT NULL,
    requested_hours      TINYINT      NOT NULL,
    reason_text          VARCHAR(500) NOT NULL,
    approval_reference   VARCHAR(64)  NULL,
    identity_check_id    BIGINT       NOT NULL,
    requested_by_user_id BIGINT       NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    failure_reason       VARCHAR(64)  NULL,
    end_reason           VARCHAR(32)  NULL,
    provider_ref         VARCHAR(64)  NULL,
    activated_at         DATETIME(6)  NULL,
    expires_at           DATETIME(6)  NULL,
    ended_at             DATETIME(6)  NULL,
    attempts             SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at      DATETIME(6)  NULL,
    needs_attention      BOOLEAN      NOT NULL DEFAULT FALSE,
    attention_reason     VARCHAR(32)  NULL,
    overdue_alerted_at   DATETIME(6)  NULL,
    version              INT          NOT NULL DEFAULT 0,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    open_account_id      BIGINT       GENERATED ALWAYS AS
        (IF(status IN ('PENDING', 'ACTIVE', 'EXPIRING'), account_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reactivation_requests_reference (reference),
    UNIQUE KEY uk_reactivation_requests_open_account (open_account_id),
    KEY ix_reactivation_requests_status_next (status, next_attempt_at),
    KEY ix_reactivation_requests_status_expires (status, expires_at),
    KEY ix_reactivation_requests_account_created (account_id, created_at),
    KEY ix_reactivation_requests_requested_by (requested_by_user_id, created_at),
    KEY ix_reactivation_requests_identity_check (identity_check_id),
    CONSTRAINT fk_reactivation_requests_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_reactivation_requests_identity_check FOREIGN KEY (identity_check_id) REFERENCES identity_checks (id),
    CONSTRAINT fk_reactivation_requests_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_reactivation_requests_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRING', 'EXPIRED', 'ENDED', 'FAILED')),
    CONSTRAINT ck_reactivation_requests_reason CHECK (suspension_reason IN
        ('NON_PAYMENT', 'CUSTOMER_REQUEST', 'KYC_PENDING', 'FRAUD', 'LEGAL', 'REGULATORY', 'OTHER')),
    CONSTRAINT ck_reactivation_requests_hours CHECK (requested_hours BETWEEN 1 AND 24),
    CONSTRAINT ck_reactivation_requests_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_reactivation_requests_max_24h
        CHECK (expires_at IS NULL OR expires_at <= activated_at + INTERVAL 24 HOUR)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Customer notification outbox (R16), written in the same tx as the reactivation transition. The dispatch job claims a
-- row PENDING -> SENDING (claimed_at), sends outside any tx, then SENT or back to PENDING with backoff (3rd failure ->
-- FAILED). params holds the reference and times only, no PII. destination is replaced by its masked form on SENT/FAILED.
CREATE TABLE notifications (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id     BIGINT       NOT NULL,
    channel         VARCHAR(8)   NOT NULL,
    template        VARCHAR(32)  NOT NULL,
    destination     VARCHAR(254) NOT NULL,
    params          JSON         NOT NULL,
    source_ref      VARCHAR(24)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        TINYINT      NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    claimed_at      DATETIME(6)  NULL,
    last_error      VARCHAR(200) NULL,
    sent_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_source (source_ref, template, channel),
    KEY ix_notifications_status_next (status, next_attempt_at),
    KEY ix_notifications_customer (customer_id),
    CONSTRAINT fk_notifications_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('SMS', 'EMAIL')),
    CONSTRAINT ck_notifications_template CHECK (template IN ('REACTIVATION_STARTED', 'REACTIVATION_ENDED')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
