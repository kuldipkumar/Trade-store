# ADR-008: Spring `JdbcClient` (plain JDBC) for PostgreSQL access

- **Status:** Proposed (2026-11-20). Awaiting the human's decision.
- **Deciders:** Product owner (human), AI architect

## Context
ADR-003 left open how the application talks to PostgreSQL ("the choice between Spring Data JDBC and JPA is made in Phase 5"). Every write the design needs is a PostgreSQL-specific statement that an ORM cannot generate:

| Operation | SQL needed |
|-----------|------------|
| Version-guarded upsert (US-102 to US-104, US-106) | `INSERT … ON CONFLICT (trade_id) DO UPDATE … WHERE trades.version <= EXCLUDED.version RETURNING (xmax = 0)` |
| Expiry (US-202) | `UPDATE … WHERE maturity_date < ? AND NOT expired RETURNING trade_id, version` |
| Outbox claim (ADR-005) | `SELECT … ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED` |
| Outbox insert / delete | Plain `INSERT` / `DELETE … WHERE id = ANY(?)` |
| Reads | `SELECT` by ID, and a paged `SELECT` with an optional `expired` filter |

There are two tables, no object graph, no relationships and no lazy loading.

## Decision (proposed)
Use Spring Framework's **`JdbcClient`** (from `spring-boot-starter-jdbc`) with hand-written SQL, behind the repository interfaces `TradeRepository` and `OutboxRepository` (Dependency Inversion: the services never see JDBC). Rows map to Java records through small `RowMapper`s. Transactions use Spring's `DataSourceTransactionManager`, through `TransactionTemplate` and `@Transactional`.

## Alternatives considered
| Option | Pros | Cons |
|--------|------|------|
| **`JdbcClient` (proposed)** | The SQL in the code is the SQL that runs. No mapping magic. Fastest path. Already on the classpath. Easy to test with Testcontainers. | Mapping code and SQL strings are written by hand (about 10 statements). |
| Spring Data JDBC (`CrudRepository` + `@Query`/`@Modifying`) | Free `findById` and paging. Familiar repository style. | Every write above still needs native `@Query`. `save()` can't be used (its insert-or-update logic is based on IDs, not versions), so it would be a mix of two styles. |
| Spring Data JPA / Hibernate | Most widely known. | Entity lifecycle, dirty checking and a persistence context add nothing here. Native queries are still needed for every write. Upserts that bypass the persistence context can leave stale entities in it. Heavier startup. |

## Consequences
- The persistence layer is small and explicit: two repository classes, each with a few SQL statements.
- Repository behaviour is tested against real PostgreSQL (Testcontainers), not mocked.
- Changing to Spring Data JDBC later only affects the `persistence/sql` package, because services depend on the interfaces.
