-- V4__org_and_settings.sql
-- Office locations, holidays, backup runs, and initial system settings

-- 1. Office locations for geofencing
CREATE TABLE office_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL CHECK (latitude >= -90.0 AND latitude <= 90.0),
    longitude NUMERIC(10, 7) NOT NULL CHECK (longitude >= -180.0 AND longitude <= 180.0),
    radius_m INTEGER NOT NULL DEFAULT 100 CHECK (radius_m > 0),
    allowed_ip_cidrs TEXT[] NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_office_locations_active ON office_locations(active);

-- 2. Holidays
CREATE TABLE holidays (
    holiday_date DATE PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Backup runs
CREATE TABLE backup_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NULL,
    size_bytes BIGINT NULL CHECK (size_bytes IS NULL OR size_bytes >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    location VARCHAR(255) NULL,
    triggered_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_runs_started ON backup_runs(started_at DESC);

-- 4. Seed system settings (ON CONFLICT DO NOTHING)
INSERT INTO system_settings (key, value, updated_at) VALUES
('company.name', '"Octopyder Pvt. Ltd."'::jsonb, now()),
('company.gst', '"19ABCDE1234F1Z5"'::jsonb, now()),
('company.address', '"Sector V, Salt Lake, Kolkata"'::jsonb, now()),
('company.email', '"admin@octopyder.com"'::jsonb, now()),
('company.phone', '"+91 9876543210"'::jsonb, now()),
('company.website', '"https://www.octopyder.com"'::jsonb, now()),
('company.logo', 'null'::jsonb, now()),
('company.timezone', '"Asia/Kolkata"'::jsonb, now()),
('payroll.cycle', '"MONTHLY"'::jsonb, now()),
('payroll.workingDays', '26'::jsonb, now()),
('payroll.dayRateDivisor', '30'::jsonb, now()),
('payroll.generationDay', '28'::jsonb, now()),
('payroll.releaseDay', '30'::jsonb, now()),
('payroll.overtimeEnabled', 'false'::jsonb, now()),
('payroll.noTasksCountsAs100', 'true'::jsonb, now()),
('payroll.allowMidMonthGeneration', 'false'::jsonb, now()),
('payroll.breakupPercent', '{"basic":50,"hra":20,"medical":10,"travel":10,"bonus":10}'::jsonb, now()),
('payroll.includeAdmins', 'false'::jsonb, now()),
('leave.monthlyCredit', '1'::jsonb, now()),
('leave.resetDate', '"04-01"'::jsonb, now()),
('leave.carryForwardOnReset', '0'::jsonb, now()),
('leave.managerApproval', 'true'::jsonb, now()),
('attendance.officeStart', '"09:00"'::jsonb, now()),
('attendance.officeEnd', '"18:00"'::jsonb, now()),
('attendance.graceMinutes', '15'::jsonb, now()),
('attendance.gpsVerification', 'true'::jsonb, now()),
('attendance.remoteAllowed', 'true'::jsonb, now()),
('attendance.managerApproval', 'true'::jsonb, now()),
('attendance.weeklyOffDays', '"SAT,SUN"'::jsonb, now()),
('attendance.halfDayMinutes', '240'::jsonb, now()),
('attendance.lowAccuracyM', '200'::jsonb, now()),
('appearance.theme', '"default"'::jsonb, now()),
('appearance.animations', 'true'::jsonb, now()),
('tasks.employeeSelfAssign', 'false'::jsonb, now()),
('backup.frequency', '"daily"'::jsonb, now()),
('backup.cloudAuto', 'true'::jsonb, now())
ON CONFLICT (key) DO NOTHING;
