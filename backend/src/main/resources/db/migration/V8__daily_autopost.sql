CREATE TABLE autopost_config (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL,
  brand_id uuid NOT NULL UNIQUE,
  campaign_id uuid NOT NULL,
  account_id uuid,
  mode text NOT NULL DEFAULT 'REVIEW' CHECK(mode IN ('REVIEW','DEMO','AUTO')),
  enabled boolean NOT NULL DEFAULT false,
  daily_post_time time NOT NULL DEFAULT '09:00',
  image_mode text NOT NULL DEFAULT 'TEMPLATE' CHECK(image_mode IN ('TEMPLATE','OPENAI')),
  image_prompt text NOT NULL DEFAULT '',
  image_key_ciphertext text,
  image_model text NOT NULL DEFAULT 'gpt-image-2.5-sunburst',
  image_reserve_micros bigint NOT NULL DEFAULT 100000 CHECK(image_reserve_micros BETWEEN 10000 AND 10000000),
  live_consent boolean NOT NULL DEFAULT false,
  created_by text NOT NULL,
  version int NOT NULL DEFAULT 1,
  activated_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(id,brand_id,workspace_id),
  FOREIGN KEY(brand_id,workspace_id) REFERENCES brand(id,workspace_id),
  FOREIGN KEY(campaign_id,brand_id,workspace_id) REFERENCES campaign(id,brand_id,workspace_id),
  FOREIGN KEY(account_id,brand_id,workspace_id) REFERENCES social_account(id,brand_id,workspace_id)
);
ALTER TABLE workflow_run ADD COLUMN autopost_config_id uuid;
ALTER TABLE workflow_run ADD COLUMN autopost_version int;
ALTER TABLE workflow_run ADD COLUMN autopost_due_at timestamptz;
ALTER TABLE workflow_run ADD COLUMN autopost_preview boolean NOT NULL DEFAULT false;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_autopost_fk FOREIGN KEY(autopost_config_id,brand_id,workspace_id) REFERENCES autopost_config(id,brand_id,workspace_id);
ALTER TABLE publication ADD COLUMN autopost_config_id uuid;
ALTER TABLE publication ADD COLUMN autopost_version int;
ALTER TABLE publication ADD CONSTRAINT publication_autopost_fk FOREIGN KEY(autopost_config_id,brand_id,workspace_id) REFERENCES autopost_config(id,brand_id,workspace_id);
