# ADR-004: MongoDB for audit trail and trade history

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect

## Context
The assignment requires a NoSQL database. It holds the append-only audit trail of every outcome (A-29, A-45) and the version history of each trade (US-404). It also serves the on-demand audit report: filters, paging, counts per outcome and CSV (US-502, US-503). Audit records vary in shape: a poison pill has a raw, possibly invalid payload and no parsed trade fields.

## Decision
Use **MongoDB** through **Spring Data MongoDB**.
- **Collection `audit_events`:**
  - `_id` = the event ID from the outbox, or a deterministic ID for `POISON`/`FAILED` events. Writes are idempotent inserts.
  - Fields: `tradeId`, `version`, `outcome`, `reasons[]`, `channel`, `eventTimestamp` (UTC), `receivedTimestamp`, and `payload`, capped at the configured size.
  - Indexes: `eventTimestamp`, `outcome` and `tradeId` (A-52).
- **Collection `trade_history`:** one document per accepted or replaced version, ordered by `version` and then `receivedTimestamp`.
- **Append-only:** the application has no update or delete paths for these collections (A-45).
- **Report counts** come from an aggregation pipeline; report pages use index-backed queries.

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| Cassandra | Excellent write throughput, but range filtering by date, outcome and trade ID needs one table per query pattern. Too much for this volume (A-52). |
| Elasticsearch | Strong search, but it's heavier to run and is a search index rather than a durable store. |
| Storing audit in PostgreSQL | Would not satisfy the assignment's NoSQL requirement. |

## Consequences
- Flexible documents suit poison-pill payloads and rejection reasons.
- No cross-database transaction with PostgreSQL. Consistency comes from the outbox (ADR-005).
- Testcontainers MongoDB is used in the integration suite.
