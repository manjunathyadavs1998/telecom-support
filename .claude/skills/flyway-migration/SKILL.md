---
name: flyway-migration
description: Create a MySQL schema change or dev seed data for this project with Flyway. Use whenever a table, column, index, trigger or seed row is added or changed.
---

# Flyway migration recipe (MySQL 8.4)

## Rules
- Location: `backend/src/main/resources/db/migration/V<next>__<snake_desc>.sql`. Find `<next>` with
  `Glob backend/src/main/resources/db/migration/V*__*.sql` and add 1. **Never edit an existing V file.** If one is wrong, write a new one.
- Seed/demo data: `backend/src/main/resources/db/seed/R__<NN>_<name>.sql` (repeatable, dev + test only). It must be **idempotent**:
  `INSERT IGNORE` or `INSERT … VALUES (…) AS new ON DUPLICATE KEY UPDATE col = new.col` (MySQL 8.4 deprecates
  `VALUES(col)`), with fixed natural keys (codes, usernames, refs).
- One feature slice per migration file, with a comment header naming the BRD IDs it serves.

## Table conventions
```sql
CREATE TABLE things (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id  BIGINT       NOT NULL,
    code         VARCHAR(40)  NOT NULL,
    status       VARCHAR(30)  NOT NULL,                 -- enums stored as VARCHAR + CHECK
    amount       DECIMAL(12,2) NULL,                    -- money: DECIMAL, never FLOAT
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),   -- UTC
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version      INT          NOT NULL DEFAULT 0,       -- optimistic locking for mutable aggregates
    PRIMARY KEY (id),
    UNIQUE KEY uk_things_code (code),
    KEY ix_things_customer_created (customer_id, created_at),
    CONSTRAINT fk_things_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT ck_things_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```
- snake_case plural table names; `pk` = `id BIGINT AUTO_INCREMENT`; FK `fk_<table>_<ref>`, index `ix_`, unique `uk_`, check `ck_`.
- Index every FK and every column used in list filters and sorts. Composite index order: equality columns first, then the range column (`created_at`).
- Audit / activity tables are **append-only**. Add `BEFORE UPDATE` and `BEFORE DELETE` triggers that `SIGNAL SQLSTATE '45000'`.
- No PII in index names or comments. Store phone numbers in E.164 (`+919876543210`).
- Passwords: BCrypt hash only (`$2a$10$…`). Seed users share the documented dev password `Password@123`.

## Verify
`cd backend && ./mvnw -q verify` runs every migration against Testcontainers MySQL 8.4. For dev, start the app with the `dev` profile.
