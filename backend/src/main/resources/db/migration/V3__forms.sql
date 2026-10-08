ALTER TABLE brand ADD COLUMN public_form_key uuid NOT NULL UNIQUE DEFAULT gen_random_uuid();
ALTER TABLE publication ADD COLUMN tracking_key uuid NOT NULL UNIQUE DEFAULT gen_random_uuid();
