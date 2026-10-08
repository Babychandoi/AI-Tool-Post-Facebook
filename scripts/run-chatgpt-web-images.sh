#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../tools/chatgpt-web-images"
if [ "${CHATGPT_WEB_IMAGE_MODE:-EXTENSION}" = DEDICATED_BROWSER ]; then
  if [ ! -d node_modules ]; then npm ci; fi
  exec node server.mjs
fi
exec node connector-server.mjs
