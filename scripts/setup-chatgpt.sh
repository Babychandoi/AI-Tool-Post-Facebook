#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import base64, secrets, os
p=Path('.env')
text=p.read_text() if p.exists() else Path('.env.example').read_text()
lines=text.splitlines()
values={line.split('=',1)[0]:line.split('=',1)[1] for line in lines if line and not line.startswith('#') and '=' in line}
key=values.get('TOKEN_ENCRYPTION_KEY','')
if key and len(base64.b64decode(key,validate=True)) != 32:
    raise SystemExit('Existing encryption key is invalid. It was not overwritten.')
if not key:
    key=base64.b64encode(secrets.token_bytes(32)).decode()
    lines=[line for line in lines if not line.startswith('TOKEN_ENCRYPTION_KEY=')]
    lines.append('TOKEN_ENCRYPTION_KEY='+key)
lines=[line for line in lines if not line.startswith('CHATGPT_ENABLED=')]
lines.append('CHATGPT_ENABLED=true')
if 'CHATGPT_REDIRECT_URI' not in values:
    lines.append('CHATGPT_REDIRECT_URI=http://127.0.0.1:5173/v1/ai/chatgpt/callback')
p.write_text('\n'.join(lines)+'\n')
os.chmod(p,0o600)
print('Local ChatGPT connection configured. Existing keys and credentials preserved.')
PY
