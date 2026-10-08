#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo 'Usage: scripts/provision-owner.sh owner@example.com "Full Name" "Workspace Name"' >&2
  exit 1
fi
docker compose exec -T postgres psql -U marketing -d marketing -v ON_ERROR_STOP=1 -v owner_email="$1" -v owner_name="$2" -v workspace_name="$3" <<'SQL'
BEGIN;
INSERT INTO workspace(name) VALUES (:'workspace_name') RETURNING id AS workspace_id \gset
INSERT INTO app_user(email,name) VALUES (:'owner_email',:'owner_name') ON CONFLICT(email) DO UPDATE SET enabled=true RETURNING id AS user_id \gset
INSERT INTO membership(user_id,workspace_id,role) VALUES (:'user_id',:'workspace_id','OWNER');
COMMIT;
SQL
