-- V6__attendance.sql
-- Attendance records with geofencing, punctuality, and manager approval

CREATE TABLE attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_date DATE NOT NULL,
    check_in_at TIMESTAMPTZ NOT NULL,
    check_out_at TIMESTAMPTZ NULL,
    worked_minutes INTEGER NOT NULL DEFAULT 0,
    overtime_minutes INTEGER NOT NULL DEFAULT 0,
    work_mode VARCHAR(16) NOT NULL CHECK (work_mode IN ('OFFICE', 'REMOTE')),
    day_type VARCHAR(16) NOT NULL DEFAULT 'PRESENT' CHECK (day_type IN ('PRESENT', 'HALF_DAY')),
    is_late BOOLEAN NOT NULL DEFAULT false,
    approval_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_by UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ NULL,
    rejection_note TEXT NULL,
    missed_checkout BOOLEAN NOT NULL DEFAULT false,
    latitude NUMERIC(10,7) NULL CHECK (latitude IS NULL OR (latitude >= -90.0 AND latitude <= 90.0)),
    longitude NUMERIC(10,7) NULL CHECK (longitude IS NULL OR (longitude >= -180.0 AND longitude <= 180.0)),
    accuracy_m NUMERIC(8,2) NULL,
    low_accuracy BOOLEAN NOT NULL DEFAULT false,
    device_platform VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    ip_address VARCHAR(45) NULL,
    office_location_id UUID NULL REFERENCES office_locations(id) ON DELETE RESTRICT,
    distance_m NUMERIC(10,2) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_user_date UNIQUE (user_id, work_date)
);

CREATE INDEX idx_attendance_user_date ON attendance_records(user_id, work_date);
CREATE INDEX idx_attendance_approval_date ON attendance_records(approval_status, work_date);
CREATE INDEX idx_attendance_work_date ON attendance_records(work_date);
CREATE INDEX idx_attendance_office ON attendance_records(office_location_id);
