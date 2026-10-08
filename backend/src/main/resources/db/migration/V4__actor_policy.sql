ALTER TABLE brand ADD COLUMN require_separate_reviewer boolean NOT NULL DEFAULT false;
ALTER TABLE publication ADD COLUMN created_by text;
