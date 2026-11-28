-- V11: outbound workspace (design 04-outbound backend §2). Module: servicecase. BRD OUT-01..OUT-06, NFR-INTEG.
-- Adds the resolution / closure columns the status workflow needs (OUT-05.3: RESOLVED records who, when and why; a
-- CLOSED case records how it closed), the indexes of the queue search (O2) and the personal bookmarks (OUT-06.1).
-- Existing RESOLVED / CLOSED rows are backfilled from updated_at before the consistency CHECKs are added, so the
-- migration is safe on a populated database. Times are UTC DATETIME(6).

ALTER TABLE cases
    ADD COLUMN resolved_at         DATETIME(6)   NULL AFTER sla_due_at,
    ADD COLUMN resolved_by_user_id BIGINT        NULL AFTER resolved_at,
    ADD COLUMN resolution_remarks  VARCHAR(1000) NULL AFTER resolved_by_user_id,
    ADD COLUMN closed_at           DATETIME(6)   NULL AFTER resolution_remarks,
    ADD COLUMN closure_type        VARCHAR(20)   NULL AFTER closed_at,
    ADD COLUMN reopen_count        INT           NOT NULL DEFAULT 0 AFTER closure_type,
    ADD CONSTRAINT fk_cases_resolved_by FOREIGN KEY (resolved_by_user_id) REFERENCES users (id);

-- Backfill (§2): rows resolved or closed before V11 have no resolver; their last change is the best known time.
UPDATE cases
SET resolved_at = updated_at
WHERE status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NULL;

UPDATE cases
SET closed_at = updated_at, closure_type = 'WINDOW_ELAPSED'
WHERE status = 'CLOSED' AND closed_at IS NULL;

ALTER TABLE cases
    ADD CONSTRAINT ck_cases_closure_type CHECK (closure_type IS NULL OR closure_type IN ('CUSTOMER_CONFIRMED', 'WINDOW_ELAPSED')),
    ADD CONSTRAINT ck_cases_resolved CHECK ((status IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL)),
    ADD CONSTRAINT ck_cases_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closure_type IS NOT NULL)),
    ADD CONSTRAINT ck_cases_reopen_count CHECK (reopen_count >= 0);

-- Queue search (O2): date window on created/updated, the overdue view, the auto-close job, the mobile filter.
CREATE INDEX ix_cases_created ON cases (created_at);
CREATE INDEX ix_cases_updated ON cases (updated_at);
CREATE INDEX ix_cases_status_sla ON cases (status, sla_due_at);
CREATE INDEX ix_cases_status_resolved ON cases (status, resolved_at);
CREATE INDEX ix_cases_contact_msisdn ON cases (contact_msisdn);

-- Personal bookmarks (OUT-06.1): not audited; last_seen_at drives "changed since seen" (OQ 8).
CREATE TABLE bookmarks (
    user_id      BIGINT      NOT NULL,
    case_id      BIGINT      NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, case_id),
    KEY ix_bookmarks_case (case_id),
    CONSTRAINT fk_bookmarks_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookmarks_case FOREIGN KEY (case_id) REFERENCES cases (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
