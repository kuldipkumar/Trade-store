# Runbook: handling a dead-lettered trade message

For operators. A message lands on the dead-letter topic `trades.inbound.DLT` in two cases:

- **`POISON`:** the message can't be read. It is empty, not JSON, has the wrong JSON types, or is over `kafka.max-message-bytes` (US-302, US-607). It is never retried.
- **`FAILED`:** processing kept failing for a reason other than the database being unreachable (US-303). It was retried `retry.max-attempts` times first.

A database outage never sends a trade here. Those trades wait and are retried until the database is back (decision D9).

Every dead letter is counted in `trade_deadletter_total{type}` and logged at WARN as `outcome=POISON|FAILED position=<topic>-<partition>-<offset>`. It is also audited in MongoDB.

The commands below assume the local stack from `docker compose up`. On other environments, use the same tools against your broker.

## 1. Inspect the dead-letter topic

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic trades.inbound.DLT --from-beginning \
  --property print.key=true --property print.headers=true --property print.partition=true --property print.offset=true \
  --timeout-ms 10000
```

Each record keeps the original key (the `tradeId` when the producer set it) and value. Spring adds these headers:

| Header | Meaning |
|--------|---------|
| `kafka_dlt-original-topic` | Where the message came from (`trades.inbound`) |
| `kafka_dlt-original-partition` | Original partition (4-byte big-endian int) |
| `kafka_dlt-original-offset` | Original offset (8-byte big-endian long) |
| `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-cause-fqcn` | The exception and its root cause, e.g. `MalformedTradeException` |
| `kafka_dlt-exception-message` | Why it failed |
| `trade-store-dlt-truncated: true` | Present only for an oversized message: the value is cut to `audit.max-payload-chars` |

The partition and offset headers are binary. The console prints them as raw bytes, so the audit entry in step 2 is the easier place to read the position.

## 2. Find the audit entry

The audit entry's ID is the original position, `<topic>-<partition>-<offset>`. List recent dead letters from the report:

```bash
curl -s 'localhost:8080/reports/audit?outcome=POISON&from=2026-11-21&to=2026-11-22' | jq '.entries[] | {eventId, eventTimestamp, error, payload}'
curl -s 'localhost:8080/reports/audit?outcome=FAILED&from=2026-11-21&to=2026-11-22' | jq '.entries[] | {eventId, eventTimestamp, error, payload}'
```

`error` gives the exception class and message, and `payload` gives the message text (capped). To get a spreadsheet, add `-H 'Accept: text/csv'`.

To look up one position directly in MongoDB:

```bash
docker compose exec mongo mongosh tradestore --quiet --eval 'db.audit_events.findOne({_id: "trades.inbound-3-42"})'
```

## 3. Fix the cause

| Outcome and error | Typical cause | Fix |
|-------------------|---------------|-----|
| `POISON`, `MalformedTradeException: The body is not valid JSON` / wrong type | The producer sent bad data | Correct the message at the source (the producer team) |
| `POISON`, `larger than N bytes` | The message is oversized (A-47) | Reduce the message. If it is legitimate, raise `TRADE_STORE_KAFKA_MAX_MESSAGE_BYTES` and the broker and producer limits together |
| `FAILED`, an SQL error that isn't a connection error | Schema or data problem, or a defect | Fix the data or deploy the fix, then replay |

A trade that is valid JSON but breaks a business rule (version, maturity) is **not** here. It is audited as `REJECTED` and is final (US-304).

## 4. Replay the message

Replay only after the cause is fixed, and only the corrected message. Publish it to `trades.inbound` with the `tradeId` as its key, so its versions stay ordered (A-22):

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic trades.inbound \
  --property parse.key=true --property key.separator='|' <<'MSG'
T1|{"tradeId":"T1","version":2,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2027-05-20"}
MSG
```

Then confirm the outcome:

```bash
curl -s localhost:8080/trades/T1
curl -s 'localhost:8080/reports/audit?tradeId=T1' | jq '.summary'
```

Things to know before replaying:

- **Replays are processed like any other message.** If a newer version of the trade has been stored since, an older replayed version is rejected as `LOWER_VERSION`, which is the intended behaviour.
- **A truncated dead letter (`trade-store-dlt-truncated`) can't be replayed from the DLT.** Ask the producer for the original.
- **The DLT is never consumed automatically,** so replaying is always a deliberate operator action (A-51, CR-5).
