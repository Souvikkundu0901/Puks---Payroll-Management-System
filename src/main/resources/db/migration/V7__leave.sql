-- V7__leave.sql
-- Leave requests and append-only leave ledger entries

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    leave_type VARCHAR(32) NOT NULL CHECK (leave_type IN ('PAID', 'CASUAL', 'SICK', 'EMERGENCY', 'UNPAID')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    days NUMERIC(4,1) NOT NULL CHECK (days > 0),
    debited_days NUMERIC(4,1) NOT NULL DEFAULT 0 CHECK (debited_days >= 0),
    reason TEXT NOT NULL,
    attachment_key VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    insufficient_balance BOOLEAN NOT NULL DEFAULT false,
    manager_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    decided_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ NULL,
    decision_note TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_leave_date_order CHECK (start_date <= end_date)
);

CREATE INDEX idx_leave_requests_user_status ON leave_requests(user_id, status);
CREATE INDEX idx_leave_requests_dates ON leave_requests(start_date, end_date);
CREATE INDEX idx_leave_requests_status ON leave_requests(status);
CREATE INDEX idx_leave_requests_manager ON leave_requests(manager_id);

CREATE TABLE leave_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    leave_year INT NOT NULL,
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('CREDIT', 'DEBIT', 'RESET', 'CARRY_FORWARD', 'REVERSAL', 'ADJUSTMENT')),
    effective_on DATE NOT NULL,
    days NUMERIC(4,1) NOT NULL,
    period_month DATE NULL,
    request_id UUID NULL REFERENCES leave_requests(id) ON DELETE SET NULL,
    note TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ledger_entry_days CHECK (
        (entry_type = 'CREDIT' AND days > 0) OR
        (entry_type = 'DEBIT' AND days < 0) OR
        (entry_type = 'RESET' AND days <= 0) OR
        (entry_type = 'CARRY_FORWARD' AND days >= 0) OR
        (entry_type = 'REVERSAL' AND days > 0) OR
        (entry_type = 'ADJUSTMENT' AND days <> 0)
    )
);

CREATE UNIQUE INDEX uq_leave_ledger_credit ON leave_ledger_entries (user_id, entry_type, period_month) WHERE entry_type = 'CREDIT';
CREATE UNIQUE INDEX uq_leave_ledger_reset ON leave_ledger_entries (user_id, entry_type, leave_year) WHERE entry_type = 'RESET';
CREATE UNIQUE INDEX uq_leave_ledger_carry_forward ON leave_ledger_entries (user_id, entry_type, leave_year) WHERE entry_type = 'CARRY_FORWARD';

CREATE INDEX idx_leave_ledger_user_effective ON leave_ledger_entries(user_id, effective_on);
CREATE INDEX idx_leave_ledger_user_year ON leave_ledger_entries(user_id, leave_year);
CREATE INDEX idx_leave_ledger_request ON leave_ledger_entries(request_id);

-- Enforce append-only immutability on leave_ledger_entries
CREATE OR REPLACE FUNCTION leave_ledger_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'leave_ledger_entries table is append-only: UPDATE and DELETE operations are prohibited';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_leave_ledger_immutable
BEFORE UPDATE OR DELETE ON leave_ledger_entries
FOR EACH ROW
EXECUTE FUNCTION leave_ledger_immutable();
