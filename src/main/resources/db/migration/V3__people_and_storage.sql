-- Departments table
CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed initial departments per PRD
INSERT INTO departments (code, name) VALUES
('DEP_IT', 'IT'),
('DEP_HR', 'HR'),
('DEP_FIN', 'Finance'),
('DEP_MKT', 'Marketing')
ON CONFLICT (name) DO NOTHING;

-- Employee profiles (1:1 with users)
-- manager_id is NOT duplicated here per change (1); users.manager_id is source of truth
CREATE TABLE employee_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    first_name VARCHAR(64) NOT NULL,
    last_name VARCHAR(64) NOT NULL,
    gender VARCHAR(16) NULL CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    date_of_birth DATE NULL,
    phone VARCHAR(32) NULL,
    address TEXT NULL,
    blood_group VARCHAR(8) NULL,
    emergency_contact VARCHAR(64) NULL,
    marital_status VARCHAR(16) NULL,
    photo_key VARCHAR(255) NULL,
    department_id UUID NULL REFERENCES departments(id) ON DELETE SET NULL,
    designation VARCHAR(64) NOT NULL,
    employment_type VARCHAR(32) NOT NULL DEFAULT 'FULL_TIME' CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT')),
    joining_date DATE NOT NULL DEFAULT CURRENT_DATE,
    exit_date DATE NULL,
    base_salary NUMERIC(12,2) NOT NULL DEFAULT 0.00 CHECK (base_salary >= 0),
    experience_years NUMERIC(4,1) NULL DEFAULT 0.0,
    skills TEXT[] NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_profiles_dept ON employee_profiles(department_id);

-- Bank accounts (AES-GCM encrypted account number)
CREATE TABLE bank_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    bank_name VARCHAR(128) NOT NULL,
    account_number_enc TEXT NOT NULL,
    account_last4 VARCHAR(4) NOT NULL,
    ifsc VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Employee statutory deductions
CREATE TABLE employee_deductions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL CHECK (type IN ('TDS', 'PF', 'PT', 'OTHER')),
    monthly_amount NUMERIC(12,2) NOT NULL DEFAULT 0.00 CHECK (monthly_amount >= 0),
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_deductions_user_type UNIQUE (user_id, type)
);

CREATE INDEX idx_employee_deductions_user ON employee_deductions(user_id);

-- Employee documents
CREATE TABLE employee_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    doc_type VARCHAR(32) NOT NULL CHECK (doc_type IN ('RESUME', 'OFFER_LETTER', 'ID_CARD', 'CERTIFICATE', 'PAN', 'AADHAAR', 'OTHER')),
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' CHECK (verification_status IN ('UPLOADED', 'VERIFIED', 'SIGNED', 'REJECTED')),
    verified_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_documents_user ON employee_documents(user_id);
CREATE INDEX idx_employee_documents_type ON employee_documents(doc_type);

-- User preferences
CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme VARCHAR(32) NOT NULL DEFAULT 'default',
    notify_email BOOLEAN NOT NULL DEFAULT TRUE,
    notify_push BOOLEAN NOT NULL DEFAULT TRUE,
    notify_payroll BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Temporary password expiry column on users (7-day expiry per BR-S8 / Change 8)
ALTER TABLE users ADD COLUMN IF NOT EXISTS temporary_password_expires_at TIMESTAMPTZ NULL;

-- Atomic sequences for human codes (EMP001, MGR001, ADM001)
CREATE SEQUENCE IF NOT EXISTS seq_emp_code START 1;
CREATE SEQUENCE IF NOT EXISTS seq_mgr_code START 1;
CREATE SEQUENCE IF NOT EXISTS seq_adm_code START 1;

-- Initialize sequences past existing user codes to avoid collisions (Change 3)
SELECT setval('seq_emp_code', COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^EMP[0-9]+$'), 0) + 1, false);
SELECT setval('seq_mgr_code', COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^MGR[0-9]+$'), 0) + 1, false);
SELECT setval('seq_adm_code', COALESCE((SELECT MAX(SUBSTRING(code FROM 4)::integer) FROM users WHERE code ~ '^ADM[0-9]+$'), 0) + 1, false);
