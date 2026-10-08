#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_java_version=""
if [ -x "${JAVA_HOME:-}/bin/java" ]; then
  task_java_version=$("$JAVA_HOME/bin/java" -version 2>&1 || true)
fi
if [[ "$task_java_version" != *'version "21.'* ]] && [ -x .runtime/amazon-corretto-21.jdk/Contents/Home/bin/java ]; then
  export JAVA_HOME="$PWD/.runtime/amazon-corretto-21.jdk/Contents/Home"
fi
if [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  echo 'Set JAVA_HOME to a JDK 21 installation.' >&2
  exit 1
fi
if [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; then
  echo 'Native pilot is configured for Apple Silicon. Use Docker Compose on other platforms.' >&2
  exit 1
fi
mkdir -p .runtime
npm ci --prefix frontend
mvn -q -f backend/pom.xml -Pportable-macos -DskipTests package
cleanup(){ for pid in "${api_pid:-}" "${worker_pid:-}" "${ui_pid:-}" "${image_pid:-}"; do [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
./scripts/run-chatgpt-web-images.sh > .runtime/image-bridge.log 2>&1 &
image_pid=$!
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -jar backend/target/marketing-0.1.0.jar --spring.profiles.active=portable > .runtime/api.log 2>&1 &
api_pid=$!
for attempt in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:58080/actuator/health >/dev/null 2>&1; then break; fi
  if ! kill -0 "$api_pid" 2>/dev/null; then cat .runtime/api.log; exit 1; fi
  sleep 1
done
curl -fsS http://127.0.0.1:58080/actuator/health >/dev/null
APP_MODE=worker PORTABLE_PORT=58081 "$JAVA_HOME/bin/java" -Djava.awt.headless=true -jar backend/target/marketing-0.1.0.jar --spring.profiles.active=portable > .runtime/worker.log 2>&1 &
worker_pid=$!
npm run dev --prefix frontend > .runtime/frontend.log 2>&1 &
ui_pid=$!
echo 'AI Marketing: http://127.0.0.1:5173 — owner@demo.local / demo-marketing'
wait "$api_pid" "$worker_pid" "$ui_pid"
