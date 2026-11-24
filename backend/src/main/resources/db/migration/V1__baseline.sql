-- V1: baseline infrastructure tables.
-- Domain tables are added by later migrations, one per feature slice.

-- Monotonic reference sequences (BR-01: unique, non-reusable references such as SR-20261124-000001).
CREATE TABLE ref_sequences (
    seq_name   VARCHAR(64)  NOT NULL,
    seq_date   DATE         NOT NULL,
    next_value BIGINT       NOT NULL,
    PRIMARY KEY (seq_name, seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ShedLock table so scheduled jobs (e.g. reactivation expiry) run once even with several instances.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
