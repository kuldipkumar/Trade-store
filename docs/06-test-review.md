# Test review against the requirements

**Date:** 2026-11-20 (after US-105, US-104 and US-101 were merged). **Updated:** 2026-11-21 (ingestion observability; US-201; US-205; US-102; US-103; US-106; US-202; US-203; US-204; US-107; US-501; US-401; US-402; US-403; US-404; US-301; US-304; US-302; US-303). **Requested in:** PROMPTS.md #26.

## How completeness and correctness are tracked

| Question | How it is answered | Where |
|----------|--------------------|-------|
| Which requirement does a story serve? | The backlog's "requirements to stories" table | [02-backlog.md](02-backlog.md) |
| Which tests verify an acceptance criterion? | Each test carries `@Tag("US-xxx.y")` (or `@Tag("A-nn")` for an assumption) | test sources |
| Which code does a test exercise? | Production classes referenced by the test | [traceability.md](traceability.md) |
| Does every test trace to a real criterion? Is the matrix current? Do tagged tests pass? | `scripts/traceability.py --check` in CI fails the build otherwise | CI step "Traceability matrix and tag check" (results in the run summary) |
| Does a tagged test **fully** prove its criterion? | Human judgement: this review, repeated whenever a story is completed | this document |

So the chain **Requirements.md → R-xx → Epic → Story → Acceptance criterion → Test → Code** can be read in [traceability.md](traceability.md). Automation guarantees that it is complete and consistent; this review covers whether each test is correct.

The tags were chosen by reading each test against the wording of its criterion. A tag means "this test contributes evidence for that criterion". The verdicts below say whether the evidence is sufficient yet.

## Verdicts for every criterion that has tests

**Full:** the tests prove every clause of the criterion. **Partial:** some clauses are proven, and the rest is listed with the story that will finish it.

| AC | Verdict | Evidence | What is missing, and where it will be closed |
|----|---------|----------|----------------------------------------------|
| US-101.1 | Full | `TradeTest`, `TradeIngestionServiceTest`, `JdbcTradeRepositoryIT`, `StoreTradeIT` (createdDate = today from the injected Clock, expired = N, ACCEPTED) | |
| US-101.2 | Full | `TradeTest`; `TradeApiIT` (a REST caller sending `createdDate` 2015-03-14 and `expired` Y still gets today and N) | The Kafka DTO uses the same record shape (US-301). |
| US-101.3 | Full | `StoreTradeIT`: the history entry arrives in MongoDB through the real relay | |
| US-102.1 | Full | `JdbcTradeRepositoryIT` (CP-2 stored), `TradeIngestionServiceTest` (REPLACED), `LowerVersionValidatorTest` | |
| US-102.2 | Full | `StoreTradeIT`: v1 then v3 stores v3, outcome REPLACED (mutation-verified) | |
| US-102.3 | Full | `StoreTradeIT`: v1 then v2 accepted, history holds both in version order through the real relay (mutation-verified); `MongoStoresIT`, `OutboxRelayServiceTest` | |
| US-103.2 | Full | `StoreTradeIT`: the same v2 delivered twice gives REPLACED, no error, and an unchanged record (mutation-verified) | |
| US-103.1 | Full | `JdbcTradeRepositoryIT` (same version replaces, createdDate reset), `TradeIngestionServiceTest` (REPLACED) | The test changes the counterparty where the criterion changes the book. Both are overwritten by the same statement, so this is equivalent. |
| US-104.1 | Full | `TradeRejectedExceptionTest`, `LowerVersionValidatorTest`, `TradeIngestionServiceTest`, `StoreTradeIT` (message names both versions) | |
| US-104.2 | Full | `TradeIngestionServiceTest` (no write attempted), `JdbcTradeRepositoryIT` (the guard holds), `StoreTradeIT` (still v2) | |
| US-104.3 | Full | `StoreTradeIT`: a REJECTED audit entry with reason LOWER_VERSION in MongoDB | |
| US-104.4 | Full | `TradeRejectedExceptionTest`, `CompositeTradeValidatorTest`, `ValidatorWiringTest` | |
| US-105.1 | Full | `MandatoryFieldsValidatorTest` (each field named; 64-character limit), `TradeIngestionServiceTest` (rejected and audited) | |
| US-105.2 | Full | `PositiveVersionValidatorTest`. The rejection path is the same one proven for US-105.1. | |
| US-105.3 | Full | `MaturityDateFormatValidatorTest`, `MaturityDatesTest` (strict format, impossible dates) | |
| US-106.1 | Full | `JdbcTradeRepositoryIT`: deterministic races on real PostgreSQL in both orderings, the waiter identified with `pg_blocking_pids`: v2 waiting on v3 writes nothing (STALE → LOWER_VERSION in the service, US-106.2); v3 waiting on v2 replaces it. Each ordering is mutation-verified. | |
| US-106.2 | Full | `TradeIngestionServiceTest`: re-validates after a lost race and retries once | |
| US-107.1 | Full | `AssignmentScenarioIT` step 1: T1 v1 ACCEPTED, stored with createdDate 2026-11-19, expired N | |
| US-107.2 | Full | `AssignmentScenarioIT` step 2: T2 v2 ACCEPTED | |
| US-107.3 | Full | `AssignmentScenarioIT` step 3: T2 v1 → LowerVersionException; T2 stays v2 | |
| US-107.4 | Full | `AssignmentScenarioIT` step 4: T3 v3 (maturity 2026-05-20) → MATURITY_IN_PAST; not stored | |
| US-107.5 | Full | `AssignmentScenarioIT` steps 5–6: clock to 2027-05-21, expiry → T1 and T2 expired; audit 2 ACCEPTED, 2 REJECTED (T2 LOWER_VERSION, T3 MATURITY_IN_PAST), 2 EXPIRED, each for the right trade (mutation-verified) | A replay through Kafka follows with US-301. |
| US-201.1 | Full | `MaturityNotInPastValidatorTest`, `TradeIngestionServiceTest` (rejected, not written, counted as `MATURITY_IN_PAST`) | |
| US-201.2 | Full | `MaturityNotInPastValidatorTest`: today and later dates are valid (the boundary) | |
| US-201.3 | Full | `TradeIngestionServiceTest`: v2 with past maturity is rejected and no write is attempted, so v1 stays | |
| US-201.4 | Full | `TradeIngestionServiceTest` (one `LowerVersionException` carrying both reasons), `ValidatorWiringTest` (order in the real context), `TradeRejectedExceptionTest` | |
| US-202.1 | Full | `ExpiryIT` (real databases, today pinned to 2026-11-19: only the trade maturing on the 18th expires), `JdbcTradeRepositoryIT`, `ExpirySchedulerTest` | |
| US-202.2 | Full | `ExpiryIT` (a second run leaves no outbox rows and exactly one EXPIRED audit entry), `JdbcTradeRepositoryIT` | |
| US-202.3 | Full | `ExpiryIT` (EXPIRED entry in MongoDB through the relay), `ExpiryServiceTest`, `AuditEventFactoryTest` | |
| US-202.4 | Full | `ExpiryScheduleConfigTest`: default `0 5 0 * * *`, an overridden cron, and the configured zone (00:05 in Kolkata = 18:35 UTC) | |
| US-203.1 | Full | `ExpiryIT` (real context: the startup hook expires a trade that matured "during downtime", off the startup thread, and writes the EXPIRED entry), `ExpirySchedulerTest` (async hand-off, retry after the interval, give-up after max attempts, on/off), `ExpiryScheduleConfigTest` (ApplicationReadyEvent wiring; off switch via real property binding, mutation-verified) | |
| US-204.1 | Full | `ExpiryIT`: expired v1 then v2 (maturity 2027-01-01) through the ingestion service → REPLACED and expired = N (mutation-verified); `JdbcTradeRepositoryIT` | |
| US-204.2 | Full | `ExpiryIT`: an expired trade stays expired after the job runs, even if its maturity is not yet past (mutation-verified) | |
| US-205.1 | Full | `ClockConfigTest` | |
| US-205.2 | Full | `TradeIngestionServiceTest`: at 01:30 on 19 Nov in Asia/Kolkata (still 18 Nov in UTC) a trade maturing on 18 Nov is rejected; mutation-verified against a UTC "today" | |
| US-301.1 | Full | `StreamIngestionIT` (published → stored and audited as STREAM, real Kafka), `TradeConsumerTest` | |
| US-301.2 | Full | `StreamIngestionIT`: v1, v2, v3 keyed by tradeId → v3 stored, no REJECTED | |
| US-301.3 | Full | `StreamIngestionIT`: no auto-commit; the container commits after the listener returns (mutation-verified) | |
| US-302.1 | Full | `PoisonPillIT` (non-JSON then a valid trade on the same key, real Kafka: the trade is stored and the pill is on `trades.inbound.DLT`), `KafkaErrorHandlingTest` (malformed → recovered at once, no retries), `DeadLetterAuditRecovererTest` (published first; a failed publish propagates and nothing is audited, mutation-verified; counted and logged at WARN without the payload), `TradeConsumerTest` (empty message is malformed) | Known limitation: the DLT holds the value as the listener read it (UTF-8 text); bytes that aren't valid UTF-8 are not preserved exactly. |
| US-303.1 | Full | `RetryThenDeadLetterIT` (real Kafka; the SQL store is a spy failing twice: stored on the third delivery, nothing on the DLT), `KafkaErrorHandlingTest` (a transient failure is retried, not recovered) | |
| US-303.2 | Full | `RetryThenDeadLetterIT` (store keeps failing: exactly 3 upsert attempts, one DLT record with the cause header, FAILED audit with the error, nothing stored), `KafkaErrorHandlingTest` (recovered on the third failure), `DeadLetterAuditRecovererTest` (FAILED audit, `trade.deadletter{type=FAILED}`, WARN line) | |
| US-303.3 | Full | `TradeStorePropertiesTest` (`retry.*` defaults 3 / 1 s / 2.0 / 10 s, override, max-attempts and multiplier below 1 refused), `KafkaErrorHandlingTest` (the handler uses the configured attempts), `RetryThenDeadLetterIT` (runs with a 100 ms backoff from properties) | |
| US-304.1 | Full | `StreamIngestionIT` (a lower version consumed → exactly one REJECTED, stays one; v2 still stored), `TradeConsumerTest` | |
| US-302.2 | Full | `PoisonPillIT` (POISON entry in MongoDB with the payload, the exception and channel STREAM), `DeadLetterAuditRecovererTest` (keyed `topic-partition-offset`, the unwrapped cause; a MongoDB failure is logged at ERROR and counted, not thrown), `AuditEventFactoryTest` (payload and error capped), `MongoStoresIT` (the `error` field is stored) | |
| US-302.3 | Full | `PoisonPillIT`: the DLT record's `kafka_dlt-original-topic`, `-partition` and `-offset` equal the send's metadata, `-exception-cause-fqcn` is `MalformedTradeException`, the key is kept; `KafkaErrorHandlingTest` (partition chosen by key); `TradeStorePropertiesTest` (`kafka.dlt-topic` default `<topic>.DLT`, override) | |
| US-401.1 | Full | `TradeApiIT` (201 with the stored trade); `GlobalExceptionHandlerTest` (503 when the database is down, decision D7) | |
| US-401.2 | Full | `TradeApiIT`: same and higher version → 200 with the stored trade | |
| US-401.3 | Full | `TradeApiIT`: 409 with `{timestamp, status, error, reasons[], path}` naming both versions | |
| US-401.4 | Full | `TradeApiIT`: 422 with MATURITY_IN_PAST | |
| US-401.5 | Full | `TradeApiIT`: invalid fields → 400 listing them; malformed JSON or a wrong JSON type (version 1.9) → 400 MALFORMED_REQUEST, nothing stored; invalid fields plus a lower version → 400 with both reasons (D6); 415 and 405 in the standard shape. `TradeJsonParserTest` (strict types) | |
| US-401.6 | Full | `TradeApiIT` (1.1 MB body → 413), `RequestSizeLimitFilterTest` (chunked bodies counted while read, through `getReader()` and repeated `getInputStream()` too), `GlobalExceptionHandlerTest` (413 also when wrapped), `TradeStorePropertiesTest` (limit must be positive) | |
| US-402.1 | Full | `TradeApiIT`: after v1 and v2, GET returns v2 with every field incl. createdDate and expired; `TradeQueryServiceTest` | |
| US-402.2 | Full | `TradeApiIT`: unknown trade → 404 standard shape (ID not echoed); unknown path, GET or POST → 404, not 500; `TradeQueryServiceTest` | `expired` is the stored flag: after midnight it changes at the 00:05 run (or at startup), the agreed design (A-18). Known limitation, not a defect. |
| US-403.1 | Full | `ListTradesIT` (10 per page; total grew by at least the 25 stored and totalPages matches it; bad or wrong-type page/size → 400), `JdbcTradeRepositoryIT` (code-point order independent of locale; total), `TradeQueryServiceTest` (size capped at max-page-size) | Known limitation: deep pages use OFFSET, which costs more as page numbers grow; keyset pagination is future work. |
| US-403.2 | Full | `ListTradesIT` (`?expired=true` returns only expired trades, ours included, our live ones excluded), `JdbcTradeRepositoryIT` | |
| US-404.1 | Full | `TradeApiIT` (v1 then v2 → [1, 2] with outcome, channel and received time; v1, v1 again, v2 → ordered by version then received time; through the real relay), `TradeQueryServiceTest`, `MongoStoresIT` | Eventually consistent: a version appears once relayed (ADR-005); a stored trade not yet relayed returns `[]`. Known limitation: history is not paged (it grows only with replacements). |
| US-404.2 | Full | `TradeApiIT` (no history → 404 standard shape), `TradeQueryServiceTest` | |
| US-501.1 | Full | `MongoStoresIT`, `AuditEventFactoryTest`, `JdbcOutboxRepositoryIT`, `OutboxRelayServiceTest`, `StoreTradeIT` (all the fields); `TradeApiIT` (REST: the payload is exactly the body sent); `StreamIngestionIT` (STREAM channel) | |
| US-501.2 | Full | `AuditTrailImmutabilityTest`: the audit and history ports and their MongoDB implementations offer only insert and read operations (mutation-verified) | |
| US-501.3 | Full | `OutboxRelayServiceTest` (failed batch kept), `OutboxRelaySchedulerTest` (each failure counted; ERROR with waiting trade IDs every N consecutive failures; reset on success), `JdbcOutboxRepositoryIT` (`pendingTradeIds`) | |
| US-603.1 | Full | `@IntegrationTest` + `TestcontainersConfiguration`; every `*IT` runs on real Kafka, PostgreSQL and MongoDB | |
| US-604.3 | Full | `TradeIngestionServiceTest`: one INFO line per trade with outcome, tradeId, version, channel and reasons; a payload marker never appears in the log | MDC context (`tradeId`, `channel`) is added by the channel adapters (US-401/US-301). |
| US-604.1 | Partial | `WalkingSkeletonIT`: health is UP with db and mongo | `/actuator/health/readiness` including Kafka. Closed in **US-604**. |
| US-604.2 | Partial | `TradeMetricsTest` (counter per outcome and per rejection reason; latency timer), `TradeIngestionServiceTest` (recorded for stored and rejected trades), `StoreTradeIT` (visible at `/actuator/prometheus`), `DeadLetterAuditRecovererTest` (`trade.deadletter{type=POISON\|FAILED}` recorded) | The dead-letter counter is not yet asserted at `/actuator/prometheus` (US-604). |
| A-09 | Full | `LowerVersionValidatorTest`: an expired trade still rejects a lower version | |
| A-46 (outbox backlog) | Full | `OutboxMetricsTest` (size, oldest age, NaN when the database is down), `JdbcOutboxRepositoryIT` (`backlog`), `StoreTradeIT` (gauges and job-failure counters at `/actuator/prometheus`, mutation-verified) | |
| A-50 | Full | `TradeStorePropertiesTest`: defaults and overrides | |
| A-52 | Partial | `MongoStoresIT`: report and history indexes exist | The `trades` partial index is created by Flyway but not asserted. Closed in **US-202** (expiry). |

## Gaps in stories already worked on

These criteria belong to stories already started, but no test covers them yet. They are shown as "⚠️ no test" in the matrix.

| AC | Gap | Plan |
|----|-----|------|

## Test quality findings

| # | Finding | Status |
|---|---------|--------|
| Q1 | Some tests were written after their code (StoreTradeIT; the four backfills) | Backfills verified by temporarily breaking the code (commit messages record it). StoreTradeIT is recorded in PROMPTS.md #25. Strict TDD applies from now on. |
| Q2 | Integration tests used field `@Autowired` (AGENTS.md §8: constructor injection only) | Fixed: `junit-platform.properties` plus constructors |
| Q3 | `ClockConfigTest` checked two inputs in one test (AGENTS.md §5: one behaviour per test) | Fixed: parameterized test |
| Q4 | Inline fully-qualified class names in three tests | Fixed: imports |
| Q5 | The end-to-end tests used the real system clock; US-107 and US-202 need to move "today". | Fixed in US-202: `MutableClock`, a `@Primary` test bean that tests pin and reset (used by `ExpiryIT` and `AssignmentScenarioIT`). |
| Q6 | The traceability parser missed tests with multi-line annotations | Fixed test-first (`MultiLineAnnotationTest`) |
| Q7 | `AssignmentScenarioIT` is a deliberately stateful, ordered scenario (AGENTS.md §5 prefers independent tests). Its steps cannot run alone. | Accepted deviation: the assignment scenario is a sequence by nature. Each step still checks one behaviour. |

## Decision D8 (accepted: option A, 2026-11-20)

**D8 (US-501.3 wording versus ADR-005).** The criterion was written for A-37 ("SQL first, then audit with retry"), which ADR-005 superseded. With the outbox, a failed audit write is never lost: it is retried on every poll until MongoDB is back. The open question is only how to signal a long outage.

| Option | Meaning |
|--------|---------|
| **A (chosen)** | Reword US-501.3: *"the audit write is retried until it succeeds; an outage longer than N polls is logged at ERROR with the affected trade IDs, and the outbox backlog gauges (US-604) show it"*. Implement it test-first in US-501. |
| B | Keep the wording and add an ERROR log with trade IDs on every failed poll (noisy during an outage). |
