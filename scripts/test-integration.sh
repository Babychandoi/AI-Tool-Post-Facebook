#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d postgres redis
docker compose exec -T postgres psql -U marketing -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='marketing_test'" | grep -q 1 || docker compose exec -T postgres psql -U marketing -d postgres -c "CREATE DATABASE marketing_test"
docker run --rm --network ai-marketing_default -e RUN_DB_TESTS=true -e DATABASE_URL=jdbc:postgresql://postgres:5432/marketing_test -e DATABASE_PASSWORD="${DATABASE_PASSWORD:-marketing-local}" -e REDIS_HOST=redis -e REDIS_PORT=6379 -v "$PWD/backend:/app" -v marketing-maven-cache:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
