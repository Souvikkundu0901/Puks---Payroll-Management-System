-- V8__payroll_workflow.sql
-- Payroll workflow: runs, records, lines, retentions

CREATE TABLE payroll_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    month DATE NOT NULL,
    department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    generated_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    params JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_runs_month ON payroll_runs(month);
CREATE INDEX idx_payroll_runs_department ON payroll_runs(department_id);

CREATE TABLE payroll_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    run_id UUID NOT NULL REFERENCES payroll_runs(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    month DATE NOT NULL,
    gross NUMERIC(12,2) NOT NULL CHECK (gross >= 0),
    tasks_total INTEGER NOT NULL CHECK (tasks_total >= 0),
    tasks_approved INTEGER NOT NULL CHECK (tasks_approved >= 0),
    completion_pct NUMERIC(5,2) NOT NULL CHECK (completion_pct >= 0 AND completion_pct <= 100),
    retention_days INTEGER NOT NULL CHECK (retention_days >= 0),
    retention_amount NUMERIC(12,2) NOT NULL CHECK (retention_amount >= 0),
    absent_days NUMERIC(4,1) NOT NULL CHECK (absent_days >= 0),
    absent_deduction NUMERIC(12,2) NOT NULL CHECK (absent_deduction >= 0),
    unpaid_leave_days NUMERIC(4,1) NOT NULL CHECK (unpaid_leave_days >= 0),
    unpaid_leave_deduction NUMERIC(12,2) NOT NULL CHECK (unpaid_leave_deduction >= 0),
    unpaid_days NUMERIC(4,1) NOT NULL CHECK (unpaid_days >= 0),
    unpaid_deduction NUMERIC(12,2) NOT NULL CHECK (unpaid_deduction >= 0),
    tds_deduction NUMERIC(12,2) NOT NULL CHECK (tds_deduction >= 0),
    pf_deduction NUMERIC(12,2) NOT NULL CHECK (pf_deduction >= 0),
    pt_deduction NUMERIC(12,2) NOT NULL CHECK (pt_deduction >= 0),
    statutory_total NUMERIC(12,2) NOT NULL CHECK (statutory_total >= 0),
    retention_released_amount NUMERIC(12,2) NOT NULL DEFAULT 0.00 CHECK (retention_released_amount >= 0),
    net_payable NUMERIC(12,2) NOT NULL CHECK (net_payable >= 0),
    net_clamped BOOLEAN NOT NULL DEFAULT false,
    present_days NUMERIC(4,1) NOT NULL CHECK (present_days >= 0),
    working_days INTEGER NOT NULL CHECK (working_days >= 0),
    default_release_amount NUMERIC(12,2) NOT NULL CHECK (default_release_amount >= 0),
    task_linked_amount NUMERIC(12,2) NOT NULL CHECK (task_linked_amount >= 0),
    task_unlocked_amount NUMERIC(12,2) NOT NULL CHECK (task_unlocked_amount >= 0),
    released_pct NUMERIC(5,2) NOT NULL CHECK (released_pct >= 0 AND released_pct <= 100),
    tranche1_amount NUMERIC(12,2) NOT NULL CHECK (tranche1_amount >= 0),
    tranche2_amount NUMERIC(12,2) NOT NULL CHECK (tranche2_amount >= 0),
    paid_amount NUMERIC(12,2) NOT NULL DEFAULT 0.00 CHECK (paid_amount >= 0 AND paid_amount <= net_payable),
    tranche1_paid_at TIMESTAMPTZ,
    tranche1_payment_ref VARCHAR(100),
    tranche1_payment_method VARCHAR(50),
    tranche1_payment_date DATE,
    payment_date DATE,
    payment_ref VARCHAR(100),
    payment_method VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED' CHECK (status IN ('GENERATED', 'APPROVED', 'REJECTED', 'RELEASED')),
    approved_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ,
    released_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    released_at TIMESTAMPTZ,
    reject_note TEXT,
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payroll_records_user_month UNIQUE (user_id, month)
);

CREATE INDEX idx_payroll_records_month_status ON payroll_records(month, status);
CREATE INDEX idx_payroll_records_user_month ON payroll_records(user_id, month);
CREATE INDEX idx_payroll_records_run ON payroll_records(run_id);
CREATE INDEX idx_payroll_records_status ON payroll_records(status);

CREATE TABLE payroll_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id UUID NOT NULL REFERENCES payroll_records(id) ON DELETE RESTRICT,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('EARNING', 'DEDUCTION', 'RETENTION', 'ADJUSTMENT')),
    label VARCHAR(100) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_payroll_lines_record ON payroll_lines(record_id);

CREATE TABLE payroll_retentions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    record_id UUID NOT NULL REFERENCES payroll_records(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    days INTEGER NOT NULL CHECK (days IN (2, 7)),
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'RELEASED', 'FORFEITED')),
    decided_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    decided_at TIMESTAMPTZ,
    note TEXT,
    released_in_record_id UUID REFERENCES payroll_records(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_retentions_user_status ON payroll_retentions(user_id, status);
CREATE INDEX idx_payroll_retentions_record ON payroll_retentions(record_id);
CREATE INDEX idx_payroll_retentions_released_in ON payroll_retentions(released_in_record_id);
