ALTER TABLE autopost_config DROP CONSTRAINT autopost_config_image_mode_check;
ALTER TABLE autopost_config ADD CONSTRAINT autopost_config_image_mode_check
  CHECK (image_mode IN ('TEMPLATE','OPENAI','GOOGLE','CHATGPT_WEB'));
