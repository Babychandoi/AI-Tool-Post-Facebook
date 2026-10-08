CREATE TABLE event_receipt (event_id uuid PRIMARY KEY REFERENCES outbox(id),consumer text NOT NULL,envelope jsonb NOT NULL,processed_at timestamptz NOT NULL DEFAULT now());
