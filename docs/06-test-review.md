# Test review against the requirements

**Date:** 2026-11-20 (after US-105, US-104 and US-101 were merged). **Updated:** 2026-11-21 (ingestion observability; US-201; US-205; US-102; US-103; US-106). **Requested in:** PROMPTS.md #26.

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
| US-101.2 | Partial | `TradeTest`: the store sets createdDate and expired | The criterion is about a sender **sending** `createdDate`/`expired`. `TradeSubmission` can't carry them, so the remaining proof is that the REST and Kafka DTOs ignore those JSON fields. Closed in **US-401 / US-301**. |
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
| US-106.1 | Full | `JdbcTradeRepositoryIT`: a deterministic race on real PostgreSQL (v2 waits on v3's row lock, then writes nothing); mutation-verified by removing the version guard | |
| US-106.2 | Full | `TradeIngestionServiceTest`: re-validates after a lost race and retries once | |
| US-201.1 | Full | `MaturityNotInPastValidatorTest`, `TradeIngestionServiceTest` (rejected, not written, counted as `MATURITY_IN_PAST`) | |
| US-201.2 | Full | `MaturityNotInPastValidatorTest`: today and later dates are valid (the boundary) | |
| US-201.3 | Full | `TradeIngestionServiceTest`: v2 with past maturity is rejected and no write is attempted, so v1 stays | |
| US-201.4 | Full | `TradeIngestionServiceTest` (one `LowerVersionException` carrying both reasons), `ValidatorWiringTest` (order in the real context), `TradeRejectedExceptionTest` | |
| US-204.1 | Partial | `JdbcTradeRepositoryIT`: the upsert clears `expired` | Service-level flow (expired v1 → v2 with maturity 2027-01-01 is stored with expired = N). Closed in **US-204**. |
| US-205.1 | Full | `ClockConfigTest` | |
| US-205.2 | Full | `TradeIngestionServiceTest`: at 01:30 on 19 Nov in Asia/Kolkata (still 18 Nov in UTC) a trade maturing on 18 Nov is rejected; mutation-verified against a UTC "today" | |
| US-302.2 | Partial | `AuditEventFactoryTest`: the payload is capped at the configured size | The POISON entry itself. Closed in **US-302**. |
| US-404.1 | Partial | `MongoStoresIT`: ordered by version, then received time | `GET /trades/{id}/history`. Closed in **US-404**. |
| US-501.1 | Partial | `MongoStoresIT`, `AuditEventFactoryTest`, `JdbcOutboxRepositoryIT`, `OutboxRelayServiceTest`, `StoreTradeIT` (all the fields) | Proven for the REST channel value only. Closed by **US-301** (the STREAM channel). |
| US-501.3 | Partial | `OutboxRelayServiceTest`, `OutboxRelaySchedulerTest`: a failed relay keeps the events and is retried | Retry is proven. The ERROR log with trade IDs after N failed polls (reworded by D8) is still to build, test-first, in **US-501**. |
| US-603.1 | Full | `@IntegrationTest` + `TestcontainersConfiguration`; every `*IT` runs on real Kafka, PostgreSQL and MongoDB | |
| US-604.3 | Full | `TradeIngestionServiceTest`: one INFO line per trade with outcome, tradeId, version, channel and reasons; a payload marker never appears in the log | MDC context (`tradeId`, `channel`) is added by the channel adapters (US-401/US-301). |
| US-604.1 | Partial | `WalkingSkeletonIT`: health is UP with db and mongo | `/actuator/health/readiness` including Kafka. Closed in **US-604**. |
| US-604.2 | Partial | `TradeMetricsTest` (counter per outcome and per rejection reason; latency timer), `TradeIngestionServiceTest` (recorded for stored and rejected trades), `StoreTradeIT` (visible at `/actuator/prometheus`) | The dead-letter counter (US-302/303) and the outbox backlog gauges (US-501). |
| A-09 | Full | `LowerVersionValidatorTest`: an expired trade still rejects a lower version | |
| A-50 | Full | `TradeStorePropertiesTest`: defaults and overrides | |
| A-52 | Partial | `MongoStoresIT`: report and history indexes exist | The `trades` partial index is created by Flyway but not asserted. Closed in **US-202** (expiry). |

## Gaps in stories already worked on

These criteria belong to stories already started, but no test covers them yet. They are shown as "⚠️ no test" in the matrix.

| AC | Gap | Plan |
|----|-----|------|
| US-501.2 | No operation updates or deletes audit entries | US-501: architecture test on the `AuditEventStore` and `TradeHistoryStore` interfaces |

## Test quality findings

| # | Finding | Status |
|---|---------|--------|
| Q1 | Some tests were written after their code (StoreTradeIT; the four backfills) | Backfills verified by temporarily breaking the code (commit messages record it). StoreTradeIT is recorded in PROMPTS.md #25. Strict TDD applies from now on. |
| Q2 | Integration tests used field `@Autowired` (AGENTS.md §8: constructor injection only) | Fixed: `junit-platform.properties` plus constructors |
| Q3 | `ClockConfigTest` checked two inputs in one test (AGENTS.md §5: one behaviour per test) | Fixed: parameterized test |
| Q4 | Inline fully-qualified class names in three tests | Fixed: imports |
| Q5 | The end-to-end tests use the real system clock, which is fine for now. US-107 and US-202 need to move "today" (to 2027-05-21). | Open: add a test-only adjustable `Clock` bean with US-107 / US-202. |
| Q6 | The traceability parser missed tests with multi-line annotations | Fixed test-first (`MultiLineAnnotationTest`) |

## Decision D8 (accepted: option A, 2026-11-20)

**D8 (US-501.3 wording versus ADR-005).** The criterion was written for A-37 ("SQL first, then audit with retry"), which ADR-005 superseded. With the outbox, a failed audit write is never lost: it is retried on every poll until MongoDB is back. The open question is only how to signal a long outage.

| Option | Meaning |
|--------|---------|
| **A (chosen)** | Reword US-501.3: *"the audit write is retried until it succeeds; an outage longer than N polls is logged at ERROR with the affected trade IDs, and the outbox backlog gauges (US-604) show it"*. Implement it test-first in US-501. |
| B | Keep the wording and add an ERROR log with trade IDs on every failed poll (noisy during an outage). |
