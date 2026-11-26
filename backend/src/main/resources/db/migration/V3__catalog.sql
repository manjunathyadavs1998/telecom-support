-- V3: plan and promotion catalogue (design 02-customer backend §2). Module: catalog.
-- BRD CUS-03 (plans and benefits), CUS-04 (promotions). Plans and promotions change only by migration or seed until an
-- admin flow exists; the rows come from db/seed (dev) and db/test-data (tests).
-- All times are UTC DATETIME(6); money is DECIMAL(12,2) INR.

CREATE TABLE plans (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)   NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    plan_type      VARCHAR(16)   NOT NULL,
    monthly_rental DECIMAL(12,2) NOT NULL,
    description    VARCHAR(500)  NULL,
    status         VARCHAR(16)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plans_code (code),
    CONSTRAINT ck_plans_plan_type CHECK (plan_type IN ('PREPAID', 'POSTPAID')),
    CONSTRAINT ck_plans_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_plans_rental CHECK (monthly_rental >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE plan_benefits (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id      BIGINT       NOT NULL,
    benefit_type VARCHAR(16)  NOT NULL,
    label        VARCHAR(100) NOT NULL,
    value_text   VARCHAR(200) NOT NULL,
    is_key       BOOLEAN      NOT NULL,
    sort_order   INT          NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_plan_benefits_plan (plan_id, sort_order),
    CONSTRAINT fk_plan_benefits_plan FOREIGN KEY (plan_id) REFERENCES plans (id),
    CONSTRAINT ck_plan_benefits_type CHECK (benefit_type IN ('VOICE', 'DATA', 'SMS', 'ROAMING', 'OTT', 'OTHER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE promotions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    code              VARCHAR(32)  NOT NULL,
    title             VARCHAR(100) NOT NULL,
    short_description VARCHAR(300) NOT NULL,
    eligibility_text  VARCHAR(500) NOT NULL,
    terms             TEXT         NOT NULL,
    valid_from        DATETIME(6)  NOT NULL,
    valid_to          DATETIME(6)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotions_code (code),
    KEY ix_promotions_status_valid_to (status, valid_to),
    CONSTRAINT ck_promotions_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_promotions_validity CHECK (valid_from < valid_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- No rows for a promotion = eligible for all plans (R7).
CREATE TABLE promotion_plans (
    promotion_id BIGINT      NOT NULL,
    plan_id      BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (promotion_id, plan_id),
    KEY ix_promotion_plans_plan (plan_id),
    CONSTRAINT fk_promotion_plans_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id),
    CONSTRAINT fk_promotion_plans_plan FOREIGN KEY (plan_id) REFERENCES plans (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
