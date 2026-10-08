UPDATE autopost_config
SET image_model='gemini-3.1-flash-image'
WHERE image_mode='GOOGLE' AND image_model='gemini-2.5-flash-image';
