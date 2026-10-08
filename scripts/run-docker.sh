#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/setup-image-connector.sh
docker compose up -d --build
echo 'AI Marketing: http://127.0.0.1:5173 — cầu nối ảnh khởi động cùng ứng dụng.'
