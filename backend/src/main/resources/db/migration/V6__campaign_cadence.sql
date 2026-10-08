ALTER TABLE campaign ADD COLUMN generation_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE campaign ADD COLUMN daily_generation_time time NOT NULL DEFAULT '06:00';
ALTER TABLE campaign ADD COLUMN created_by text;
ALTER TABLE campaign ADD COLUMN version int NOT NULL DEFAULT 1;
ALTER TABLE workflow_run ADD COLUMN trigger_key text;
CREATE UNIQUE INDEX workflow_trigger ON workflow_run(workspace_id,brand_id,trigger_key) WHERE trigger_key IS NOT NULL;
