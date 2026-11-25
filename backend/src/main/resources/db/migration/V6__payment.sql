-- V6: invoices, payments and gateway webhook events (design 02-customer backend §2, §3.1). Module: payment.
-- BRD CUS-07 (invoices, history, receipts), CUS-08 (hosted payment, webhook), BR-08, NFR-INTEG, §12 (no card data:
-- no card number, CVV or gateway payload is stored). All times are UTC DATETIME(6); money is DECIMAL(12,2) INR.

CREATE TABLE invoices (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    account_id   BIGINT        NOT NULL,
    invoice_ref  VARCHAR(24)   NOT NULL,
    period_start DATE          NOT NULL,
    period_end   DATE          NOT NULL,
    issue_date   DATE          NOT NULL,
    due_date     DATE          NOT NULL,
    amount_due   DECIMAL(12,2) NOT NULL,
    amount_paid  DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status       VARCHAR(16)   NOT NULL,
    version      INT           NOT NULL DEFAULT 0,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoices_ref (invoice_ref),
    KEY ix_invoices_account_issue (account_id, issue_date),
    CONSTRAINT fk_invoices_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_invoices_status CHECK (status IN ('OPEN', 'PAID')),
    CONSTRAINT ck_invoices_amount_due CHECK (amount_due >= 0),
    CONSTRAINT ck_invoices_amount_paid CHECK (amount_paid >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- reference (PAY-…) is also the gateway merchant reference. Status changes only through the guarded transitions of §3.1.
-- open_invoice_id: at most one PENDING payment per invoice (design erratum E1). REVIEW is outside the key so a transition
-- to REVIEW never fails on it; "no new payment while one is PENDING or REVIEW" is enforced by initiate (R12, invoice lock).
CREATE TABLE payments (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    reference            VARCHAR(24)   NOT NULL,
    account_id           BIGINT        NOT NULL,
    invoice_id           BIGINT        NOT NULL,
    amount               DECIMAL(12,2) NOT NULL,
    currency             CHAR(3)       NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    failure_reason       VARCHAR(64)   NULL,
    method               VARCHAR(16)   NULL,
    gateway_session_id   VARCHAR(64)   NULL,
    redirect_url         VARCHAR(500)  NULL,
    gateway_txn_id       VARCHAR(64)   NULL,
    initiated_by_user_id BIGINT        NOT NULL,
    initiated_at         DATETIME(6)   NOT NULL,
    completed_at         DATETIME(6)   NULL,
    version              INT           NOT NULL DEFAULT 0,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    open_invoice_id      BIGINT        GENERATED ALWAYS AS (IF(status = 'PENDING', invoice_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_reference (reference),
    UNIQUE KEY uk_payments_gateway_session (gateway_session_id),
    UNIQUE KEY uk_payments_open_invoice (open_invoice_id),
    KEY ix_payments_account_initiated (account_id, initiated_at),
    KEY ix_payments_status_initiated (status, initiated_at),
    KEY ix_payments_invoice (invoice_id),
    KEY ix_payments_initiated_by (initiated_by_user_id),
    CONSTRAINT fk_payments_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_payments_initiated_by FOREIGN KEY (initiated_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'REVIEW')),
    CONSTRAINT ck_payments_method CHECK (method IS NULL OR method IN ('UPI', 'CARD', 'NETBANKING', 'WALLET')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Webhook dedupe and outcome log. Only ids and the outcome are kept, never the payload (§12).
CREATE TABLE payment_gateway_events (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    gateway_event_id VARCHAR(64) NOT NULL,
    payment_id       BIGINT      NULL,
    event_type       VARCHAR(32) NOT NULL,
    outcome          VARCHAR(16) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_gateway_events_event (gateway_event_id),
    KEY ix_payment_gateway_events_payment (payment_id),
    CONSTRAINT fk_payment_gateway_events_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payment_gateway_events_outcome CHECK (outcome IN ('APPLIED', 'IGNORED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
