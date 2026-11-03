-- V9__dashboard_reports_audit.sql
-- Expand task activities for deadline reminder with uniqueness constraint per task + deadline

ALTER TABLE task_activities DROP CONSTRAINT IF EXISTS task_activities_type_check;
ALTER TABLE task_activities ADD CONSTRAINT task_activities_type_check 
    CHECK (type IN ('CREATED', 'STARTED', 'PROGRESS', 'SUBMITTED', 'APPROVED', 'REJECTED', 'EDITED', 'REASSIGNED', 'CANCELLED', 'COMMENT_ADDED', 'ATTACHMENT_ADDED', 'DEADLINE_REMINDER'));

-- Ensure at most one DEADLINE_REMINDER per task and deadline (stored in to_value)
CREATE UNIQUE INDEX IF NOT EXISTS uq_task_activities_deadline_reminder 
    ON task_activities (task_id, to_value) 
    WHERE type = 'DEADLINE_REMINDER';

CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
