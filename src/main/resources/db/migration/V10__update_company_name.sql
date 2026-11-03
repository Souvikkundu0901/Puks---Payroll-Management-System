-- V10: Update default company name from Octopyder to Puk's
UPDATE system_settings
SET value = '"Puk''s"'::jsonb, updated_at = now()
WHERE key = 'company.name';

UPDATE system_settings
SET value = '"Puk''s Pvt. Ltd."'::jsonb, updated_at = now()
WHERE key = 'company.legal_name';
