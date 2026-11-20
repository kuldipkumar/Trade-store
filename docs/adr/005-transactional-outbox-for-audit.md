# ADR-005: Transactional outbox for audit and history events

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect
- **Satisfies:** PO constraint **CR-6**: no outcome of an accepted or rejected trade may be lost from the audit trail, even after a crash. It replaces assumption A-37 ("SQL first, then audit with retry").

## Context
Outcomes are produced in three places: Kafka ingestion, REST ingestion and the expiry job. The trade state lives in PostgreSQL; audit and history live in MongoDB. No transaction spans both databases. Writing the audit entry to MongoDB after the PostgreSQL commit leaves a crash window in which the audit entry is lost. Kafka redelivery could close that window for the stream only, not for REST or expiry.

## Decision
Use a **transactional outbox** in PostgreSQL.
1. Every outcome (`ACCEPTED`, `REPLACED`, `REJECTED`, `EXPIRED`) is written to `outbox_events`, with a UUID event ID, type, payload and created time, **in the same transaction** as any change to `trades`. For a rejection, the outbox row is the only write.
2. An **Outbox Relay** (`@Scheduled`, poll interval configurable, default 1 s):
   - claims a batch with `SELECT … ORDER BY id FOR UPDATE SKIP LOCKED`, so several instances can run safely;
   - inserts each event into MongoDB `audit_events` (and `trade_history` for `ACCEPTED`/`REPLACED`), with `_id` = event ID;
   - deletes the relayed rows.
   A duplicate-key error on re-delivery counts as success, which makes the relay idempotent.
3. **`POISON` and `FAILED` events** have no trade transaction to join. When `FAILED` is caused by PostgreSQL being down, an outbox row can't be written either. These are therefore written **directly to MongoDB** by the Kafka error recoverer, with `_id` = a hash of topic, partition and offset. The message's **offset is committed only after the dead-letter publish succeeds**, so the dead-letter record is the durable fallback if the MongoDB write also fails.

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| SQL first, then audit with retry (A-37) | Loses an audit entry if the app crashes between the two writes. Violates CR-6. |
| Rely on Kafka redelivery (audit first, idempotent) | Covers only the stream. REST and expiry have no redelivery. |
| Change Data Capture (Debezium) | Robust, but adds Kafka Connect and Debezium: more infrastructure than a polling relay for this scale. |
| Publish audit events through Kafka | Same dual-write problem between PostgreSQL and Kafka, unless an outbox is used anyway. |

## Consequences
- **Guarantee:** an outcome is recorded if, and only if, its trade transaction commits. Audit and history become **eventually consistent**, normally within the poll interval (A-44).
- The audit report may lag by about the poll interval. Tests use Awaitility.
- An outbox backlog becomes a health signal: a Micrometer gauge for the outbox row count and age (A-46).
- Outbox rows are deleted after relay, so the table stays small. MongoDB holds the permanent record (A-31).
- After a crash between the PostgreSQL commit and the Kafka offset commit, a redelivered message is processed again. That produces a second, accurate `REPLACED` event. This is acceptable under A-24 and will be documented.
