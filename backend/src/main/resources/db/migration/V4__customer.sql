-- V4: customers, accounts, subscriptions and the customer-owned workflows (design 02-customer backend §2). Module: customer.
-- BRD CUS-01.3 (own data only), CUS-02 (profile, verified contacts), CUS-03.5 (plan change), CUS-04.3 (claims),
-- §14 #1 (one account, many subscriptions). All times are UTC DATETIME(6); money is DECIMAL(12,2) INR.

CREATE TABLE customers (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    customer_ref          VARCHAR(20)  NOT NULL,
    full_name             VARCHAR(100) NOT NULL,
    alias_name            VARCHAR(60)  NULL,
    email                 VARCHAR(254) NULL,
    email_verified_at     DATETIME(6)  NULL,
    secondary_msisdn      VARCHAR(16)  NULL,
    secondary_verified_at DATETIME(6)  NULL,
    billing_address       JSON         NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    version               INT          NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customers_ref (customer_ref),
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One account per customer (OQ 1). The primary MSISDN is derived from the primary subscription, never copied here.
CREATE TABLE accounts (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id       BIGINT      NOT NULL,
    account_ref       VARCHAR(20) NOT NULL,
    billing_cycle_day TINYINT     NOT NULL,
    status            VARCHAR(16) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_customer (customer_id),
    UNIQUE KEY uk_accounts_ref (account_ref),
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_accounts_cycle_day CHECK (billing_cycle_day BETWEEN 1 AND 28),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- live_msisdn: a number is unique only while not TERMINATED (numbers can be recycled).
-- primary_account_id: at most one primary subscription per account.
CREATE TABLE subscriptions (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    account_id         BIGINT      NOT NULL,
    msisdn             VARCHAR(16) NOT NULL,
    plan_id            BIGINT      NOT NULL,
    is_primary         BOOLEAN     NOT NULL,
    status             VARCHAR(16) NOT NULL,
    activated_at       DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    live_msisdn        VARCHAR(16) GENERATED ALWAYS AS (IF(status <> 'TERMINATED', msisdn, NULL)) STORED,
    primary_account_id BIGINT      GENERATED ALWAYS AS (IF(is_primary, account_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscriptions_live_msisdn (live_msisdn),
    UNIQUE KEY uk_subscriptions_primary_account (primary_account_id),
    KEY ix_subscriptions_account (account_id),
    KEY ix_subscriptions_plan (plan_id),
    CONSTRAINT fk_subscriptions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_subscriptions_plan FOREIGN KEY (plan_id) REFERENCES plans (id),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- OTP contact verification (R3, §3.2). Only an HMAC of the code is stored, never the code.
CREATE TABLE contact_verifications (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id BIGINT       NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    new_value   VARCHAR(254) NOT NULL,
    code_hmac   CHAR(64)     NOT NULL,
    attempts    TINYINT      NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_contact_verifications_customer (customer_id, created_at),
    CONSTRAINT fk_contact_verifications_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_contact_verifications_channel CHECK (channel IN ('EMAIL', 'SECONDARY_MSISDN')),
    CONSTRAINT ck_contact_verifications_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'LOCKED', 'SUPERSEDED')),
    CONSTRAINT ck_contact_verifications_attempts CHECK (attempts BETWEEN 0 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Scheduled plan changes (R6). scheduled_sub_id: at most one SCHEDULED change per subscription.
CREATE TABLE plan_changes (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    subscription_id      BIGINT        NOT NULL,
    from_plan_id         BIGINT        NOT NULL,
    to_plan_id           BIGINT        NOT NULL,
    effective_date       DATE          NOT NULL,
    charge_amount        DECIMAL(12,2) NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    cancel_reason        VARCHAR(200)  NULL,
    apply_attempts       TINYINT       NOT NULL DEFAULT 0,
    requested_by_user_id BIGINT        NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    scheduled_sub_id     BIGINT        GENERATED ALWAYS AS (IF(status = 'SCHEDULED', subscription_id, NULL)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_changes_scheduled_sub (scheduled_sub_id),
    KEY ix_plan_changes_subscription (subscription_id),
    KEY ix_plan_changes_status_effective (status, effective_date),
    CONSTRAINT fk_plan_changes_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT fk_plan_changes_from_plan FOREIGN KEY (from_plan_id) REFERENCES plans (id),
    CONSTRAINT fk_plan_changes_to_plan FOREIGN KEY (to_plan_id) REFERENCES plans (id),
    CONSTRAINT fk_plan_changes_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_plan_changes_status CHECK (status IN ('SCHEDULED', 'APPLIED', 'CANCELLED')),
    CONSTRAINT ck_plan_changes_charge CHECK (charge_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Promotion claims (R8): recorded only, no fulfilment (OQ 7). terms_hash = SHA-256 of the accepted terms.
CREATE TABLE promotion_claims (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    promotion_id       BIGINT      NOT NULL,
    subscription_id    BIGINT      NOT NULL,
    terms_hash         CHAR(64)    NOT NULL,
    claimed_by_user_id BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_claims (promotion_id, subscription_id),
    KEY ix_promotion_claims_subscription (subscription_id),
    CONSTRAINT fk_promotion_claims_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_promotion_claims_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT fk_promotion_claims_user FOREIGN KEY (claimed_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_promotion_claims_status CHECK (status IN ('CLAIMED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A CUSTOMER login is linked to its customer record (column created in V2, FK added in this slice).
ALTER TABLE users
    ADD KEY ix_users_customer (customer_id),
    ADD CONSTRAINT fk_users_customer FOREIGN KEY (customer_id) REFERENCES customers (id);
