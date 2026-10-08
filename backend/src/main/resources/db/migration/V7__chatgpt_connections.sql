CREATE TABLE chatgpt_host (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  host_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO chatgpt_host(host_id) VALUES ('urn:uuid:' || gen_random_uuid());

CREATE TABLE chatgpt_connection (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspace(id),
  owner_email text NOT NULL REFERENCES app_user(email),
  subject text NOT NULL,
  client_id text NOT NULL CHECK(client_id <> 'dynamic_agent_client'),
  account_email text NOT NULL,
  credentials_cipher text,
  scopes jsonb NOT NULL DEFAULT '[]',
  expires_at timestamptz,
  earliest_refresh_at timestamptz,
  status text NOT NULL CHECK(status IN ('CONNECTED','PLAN_DISABLED','REAUTH_REQUIRED','DISCONNECTED')),
  active boolean NOT NULL DEFAULT false,
  model text,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(workspace_id,owner_email,client_id,subject),
  UNIQUE(id,workspace_id,owner_email)
);
CREATE UNIQUE INDEX one_active_chatgpt_account ON chatgpt_connection(workspace_id,owner_email) WHERE active;
ALTER TABLE workflow_run ADD COLUMN ai_provider text;
ALTER TABLE workflow_run ADD COLUMN ai_model text;
ALTER TABLE workflow_run ADD COLUMN ai_connection_id uuid;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_chatgpt_owner_fk
  FOREIGN KEY(ai_connection_id,workspace_id,created_by) REFERENCES chatgpt_connection(id,workspace_id,owner_email);
