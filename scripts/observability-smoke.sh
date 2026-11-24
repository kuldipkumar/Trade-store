#!/usr/bin/env bash
# US-604, ADR-012: the observability stack works: Prometheus scrapes the app, Tempo receives traces, Loki receives
# logs, and Grafana has the Trade Store dashboard.
# Run after the stack is up (CI: after `KEEP_UP=1 scripts/compose-smoke.sh`).
set -euo pipefail
cd "$(dirname "$0")/.."
TIMEOUT="${TIMEOUT:-180}"

# Already started by `docker compose up`; --no-deps leaves the running app alone (recreating it resets counters)
docker compose up -d --no-deps prometheus tempo loki grafana

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
# Produce an outcome now, so the check does not depend on what ran before
curl -fsS -o /dev/null -X POST localhost:8080/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"OBS-1","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-01-01"}' || true
wait_for "trade metrics are in Prometheus" \
  "curl -fsS 'http://localhost:9090/api/v1/query?query=trade_outcomes_total' | grep -q 'trade_outcomes_total'"
wait_for "Grafana serves the Trade Store dashboard" \
  "curl -fsS 'http://localhost:3000/api/dashboards/uid/trade-store' | grep -q '\"title\":\"Trade Store\"'"
wait_for "Tempo has a trade's full trace (consume, ingest, PostgreSQL, outbox relay, MongoDB)" \
  "curl -fsS -G 'http://localhost:3200/api/search' --data-urlencode 'q={name=\"outbox.relay\"}' | grep -q traceID"
wait_for "Loki has the outcome log lines" \
  "curl -fsS -G 'http://localhost:3100/loki/api/v1/query_range' --data-urlencode 'query={service_name=\"trade-store\"} |= \"outcome=\"' | grep -q tradeId"
