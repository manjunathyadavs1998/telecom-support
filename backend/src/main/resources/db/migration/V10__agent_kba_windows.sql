-- V10: per-agent KBA hour windows (design 03-inbound backend §11 E1, R4, §3.1 lock order). Module: customer.
-- BRD INB-01.2, NFR-SEC. A KBA attempt first upserts and locks (FOR UPDATE) the agent's current-hour row, then locks
-- the customer row, so one agent's parallel attempts on different customers are serialised and the 10-per-hour agent
-- limit holds. attempts counts the attempts admitted in that hour. window_start is the UTC hour (DATETIME(6)).

CREATE TABLE agent_kba_windows (
    agent_user_id BIGINT      NOT NULL,
    window_start  DATETIME(6) NOT NULL,
    attempts      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (agent_user_id, window_start),
    CONSTRAINT fk_agent_kba_windows_agent FOREIGN KEY (agent_user_id) REFERENCES users (id),
    CONSTRAINT ck_agent_kba_windows_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
