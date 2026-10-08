#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
npm ci --prefix frontend
npm run build --prefix frontend
if command -v java >/dev/null && java -version 2>&1 | head -1 | grep -q '"21'; then
  mvn -B -f backend/pom.xml verify
else
  docker run --rm -v "$PWD/backend:/app" -v marketing-maven-cache:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -B verify
fi
