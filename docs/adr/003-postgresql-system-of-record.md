# ADR-003: PostgreSQL as the SQL system of record

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect

## Context
The assignment requires a SQL database. SQL holds the latest version of each trade and is the system of record (A-36). The version rule must hold even when REST and Kafka update the same trade at once (US-106, CR-1). The expiry job needs an efficient "matured and not yet expired" query (A-52). The transactional outbox (ADR-005) needs the outbox insert in the same transaction as the trade write.

## Decision
Use **PostgreSQL**, with schema migrations managed by **Flyway**.
- **Table `trades`:** primary key `trade_id`; columns `version`, `counter_party_id`, `book_id`, `maturity_date`, `created_date`, `expired`. A partial index on `maturity_date` where `expired = false` serves the expiry job.
- **Version guard in one statement:**
  `INSERT … ON CONFLICT (trade_id) DO UPDATE SET … WHERE trades.version <= EXCLUDED.version`.
  A result of **0 rows** means a higher version was stored concurrently. The trade is then re-validated and rejected as `LOWER_VERSION` (US-106.2). This gives an atomic check-and-write without application locks.
- **Table `outbox_events`:** written in the same transaction (ADR-005).
- **Expiry:** `UPDATE trades SET expired = true WHERE maturity_date < :today AND expired = false RETURNING trade_id, version`. The returned rows become `EXPIRED` outbox events in the same transaction, and the statement is idempotent by design.

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| MySQL | Viable, but `INSERT … ON DUPLICATE KEY UPDATE` has no `WHERE` clause, so the version guard would need `SELECT … FOR UPDATE` and explicit locking. There is no `RETURNING`, so expiry would need a second query. |
| H2 / in-memory | Ruled out by AGENTS.md: integration tests run against real infrastructure. |

## Consequences
- Concurrency correctness (US-106) comes from one SQL statement. It is easy to test with Testcontainers.
- Flyway migrations version the schema alongside the code.
- Mapping the guarded upsert requires native SQL (for example `JdbcClient` or a `@Query`) rather than a plain `save()`. The choice between Spring Data JDBC and JPA is made in Phase 5.
