ALTER TABLE autopost_config DROP CONSTRAINT IF EXISTS autopost_config_image_mode_check;
ALTER TABLE autopost_config ADD CONSTRAINT autopost_config_image_mode_check
  CHECK (image_mode IN ('TEMPLATE','OPENAI','GOOGLE'));
ALTER TABLE autopost_config ADD COLUMN image_key_provider text NOT NULL DEFAULT 'OPENAI';
