-- V1 baseline (design 01-foundation backend §2). The other foundation tables arrive in V2.

-- Reference-ID sequences (BR-01, 00-overview §8): one row per prefix and Asia/Kolkata day.
-- next_value holds the last value issued.
CREATE TABLE ref_sequences (
    seq_name   VARCHAR(8) NOT NULL,
    seq_date   DATE       NOT NULL,
    next_value BIGINT     NOT NULL,
    PRIMARY KEY (seq_name, seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
