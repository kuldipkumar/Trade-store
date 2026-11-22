#!/usr/bin/env bash
# US-606.1: `docker compose up` starts the app, broker, SQL and NoSQL stores, and the app becomes ready (A-49).
# Usage: scripts/compose-smoke.sh   (exits non-zero if the app is not ready within TIMEOUT seconds)
set -euo pipefail
cd "$(dirname "$0")/.."

TIMEOUT="${TIMEOUT:-300}"
URL="${URL:-http://localhost:8080/actuator/health/readiness}"

cleanup() { docker compose down -v --remove-orphans > /dev/null 2>&1 || true; }
trap cleanup EXIT

docker compose up -d --build

deadline=$((SECONDS + TIMEOUT))
until curl -fsS "$URL" 2> /dev/null | grep -q '"status":"UP"'; do
  if (( SECONDS > deadline )); then
    echo "Not ready after ${TIMEOUT}s" >&2
    docker compose ps >&2
    docker compose logs --tail 100 app >&2
    exit 1
  fi
  sleep 5
done

echo "Ready: $(curl -fsS "$URL")"
# One trade end to end through REST, to prove the databases are wired.
curl -fsS -X POST localhost:8080/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"SMOKE-1","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-01-01"}' > /dev/null
curl -fsS localhost:8080/trades/SMOKE-1 | grep -q '"tradeId":"SMOKE-1"'
echo "Smoke test passed"
