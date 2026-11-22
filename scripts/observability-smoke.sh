#!/usr/bin/env bash
# US-604: the observability profile works: Prometheus scrapes the app and Grafana has the Trade Store dashboard.
# Run after the stack is up (CI: after `KEEP_UP=1 scripts/compose-smoke.sh`).
set -euo pipefail
cd "$(dirname "$0")/.."
TIMEOUT="${TIMEOUT:-180}"

docker compose --profile observability up -d prometheus grafana

wait_for() {  # description, command
  local deadline=$((SECONDS + TIMEOUT))
  until eval "$2" > /dev/null 2>&1; do
    if (( SECONDS > deadline )); then echo "Timed out waiting for: $1" >&2; docker compose --profile observability logs --tail 50 prometheus grafana >&2; exit 1; fi
    sleep 3
  done
  echo "OK: $1"
}

wait_for "Prometheus scrapes the app" \
  "curl -fsS 'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22trade-store%22%7D' | grep -q '\"value\":\\[[0-9.]*,\"1\"\\]'"
wait_for "trade metrics are in Prometheus" \
  "curl -fsS 'http://localhost:9090/api/v1/query?query=trade_outcomes_total' | grep -q 'trade_outcomes_total'"
wait_for "Grafana serves the Trade Store dashboard" \
  "curl -fsS 'http://localhost:3000/api/dashboards/uid/trade-store' | grep -q '\"title\":\"Trade Store\"'"
