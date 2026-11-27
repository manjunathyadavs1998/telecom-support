-- V7: account suspension reason and time (design 03-inbound backend §2). Module: customer.
-- BRD INB-04.1 (reactivation eligibility by suspension reason), NFR-INTEG. Shipped alone so the slice 02 IT updates
-- (suspendAccount/reinstateAccount helpers) land with it. Every writer of accounts.status (seeds, tests) sets or clears
-- both columns in the same statement. All times are UTC DATETIME(6).

ALTER TABLE accounts
    ADD COLUMN suspension_reason VARCHAR(16) NULL AFTER status,
    ADD COLUMN suspended_at      DATETIME(6) NULL AFTER suspension_reason;

-- Rows suspended before this migration have no known reason: OTHER since their last update.
UPDATE accounts
SET suspension_reason = 'OTHER', suspended_at = updated_at
WHERE status = 'SUSPENDED';

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_suspension_reason CHECK (suspension_reason IS NULL OR suspension_reason IN
        ('NON_PAYMENT', 'CUSTOMER_REQUEST', 'KYC_PENDING', 'FRAUD', 'LEGAL', 'REGULATORY', 'OTHER')),
    ADD CONSTRAINT ck_accounts_suspension
        CHECK ((status = 'SUSPENDED') = (suspension_reason IS NOT NULL AND suspended_at IS NOT NULL));
