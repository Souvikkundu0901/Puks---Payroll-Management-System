-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(32) NOT NULL UNIQUE,
    email citext NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'EMPLOYEE')),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'RESIGNED')),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ NULL,
    last_login_at TIMESTAMPTZ NULL,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    password_changed_at TIMESTAMPTZ NULL,
    manager_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    token_version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_manager_id ON users(manager_id);

-- Refresh tokens table
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    replaced_by VARCHAR(255) NULL,
    user_agent TEXT NULL,
    ip VARCHAR(45) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);

-- Append-only audit logs table
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    actor_role VARCHAR(32) NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NULL,
    before JSONB NULL,
    after JSONB NULL,
    ip VARCHAR(45) NULL,
    user_agent TEXT NULL,
    request_id VARCHAR(64) NULL
);

CREATE INDEX idx_audit_logs_at ON audit_logs(at);
CREATE INDEX idx_audit_logs_actor_id_at ON audit_logs(actor_id, at);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);

-- Trigger to enforce append-only immutability for audit_logs
CREATE OR REPLACE FUNCTION audit_logs_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs table is append-only: UPDATE and DELETE operations are prohibited';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_immutable
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW
EXECUTE FUNCTION audit_logs_immutable();

-- System settings table
CREATE TABLE system_settings (
    key VARCHAR(128) PRIMARY KEY,
    value JSONB NOT NULL,
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed initial security settings
INSERT INTO system_settings (key, value, updated_at) VALUES
('security.sessionTimeout', '30'::jsonb, now()),
('security.maxLoginAttempts', '5'::jsonb, now()),
('security.autoLockMinutes', '15'::jsonb, now()),
('security.passwordMinLength', '8'::jsonb, now()),
('security.passwordRules', '"all_four"'::jsonb, now()),
('security.adminTwoFactor', 'false'::jsonb, now())
ON CONFLICT (key) DO NOTHING;
