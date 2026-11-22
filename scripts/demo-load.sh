#!/usr/bin/env bash
# Drives every outcome through a running local stack, so the Grafana dashboard moves during a demo.
# Usage: docker compose --profile observability up --build -d && scripts/demo-load.sh [rounds]
# Each round, over both channels: 20 new trades, 5 replacements, 3 lower versions, 2 past maturities, 1 poison pill (30 stream messages).
set -euo pipefail
cd "$(dirname "$0")/.."

ROUNDS="${1:-30}"
API="${API:-http://localhost:8080}"
RUN="DEMO-$(date +%H%M%S)"

produce() { docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic trades.inbound --property parse.key=true --property key.separator='|' > /dev/null; }
trade() { printf '{"tradeId":"%s","version":%s,"counterPartyId":"CP-%s","bookId":"B1","maturityDate":"%s"}' "$1" "$2" "$((RANDOM % 9))" "$3"; }
post() { curl -s -o /dev/null -w '%{http_code} ' -X POST "$API/trades" -H 'Content-Type: application/json' -d "$1"; }

for round in $(seq 1 "$ROUNDS"); do
  {
    for i in $(seq 1 20); do id="$RUN-S$round-$i"; echo "$id|$(trade "$id" 2 2099-01-01)"; done      # ACCEPTED (stream)
    for i in $(seq 1 3); do id="$RUN-S$round-$i"; echo "$id|$(trade "$id" 1 2099-01-01)"; done       # REJECTED lower version
    for i in $(seq 1 5); do id="$RUN-S$round-$i"; echo "$id|$(trade "$id" 2 2099-06-30)"; done       # REPLACED (same version)
    echo "$RUN-S$round-old|$(trade "$RUN-S$round-old" 1 2014-05-20)"                                # REJECTED maturity in the past
    echo "$RUN-S$round-bad|this is not a trade"                                                       # POISON -> dead-letter topic
  } | produce
  codes=""
  for i in 1 2 3; do codes+=$(post "$(trade "$RUN-R$round-$i" 1 2099-01-01)"); done                # 201
  codes+=$(post "$(trade "$RUN-R$round-1" 1 2099-02-01)")                                            # 200 replaced
  codes+=$(post "$(trade "$RUN-R$round-2" 0 2099-02-01)")                                            # 400 invalid version
  codes+=$(post "$(trade "$RUN-R$round-x" 1 2015-01-01)")                                            # 422 past maturity
  curl -s -o /dev/null "$API/trades?size=20"; curl -s -o /dev/null "$API/reports/audit?outcome=REJECTED&size=10"
  echo "round $round/$ROUNDS: stream 30 messages, REST $codes"
  sleep 2
done
echo "Done. Grafana: http://localhost:3000  ·  Audit: $API/reports/audit?tradeId=$RUN-S1-1"
