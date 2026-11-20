# Phase 2: Epics and User Stories

Derived from [Requirements.md](../Requirements.md) and the agreed [ASSUMPTIONS.md](../ASSUMPTIONS.md). Every acceptance criterion (AC) is a **test specification** for Phase 6. AC IDs (for example `US-104.2`) are referenced in test names and commit messages.

**Conventions used in the acceptance criteria**
- "Today" is fixed by an injected `Clock`. Examples use **today = 2026-11-19** in the configured local time zone.
- "Stored" means the latest-version record in the SQL store. "History" and "audit" live in the NoSQL store.
- Trade fields: `tradeId`, `version`, `counterPartyId`, `bookId`, `maturityDate`. The store sets `createdDate` and `expired`.
- Outcomes: `ACCEPTED`, `REPLACED`, `REJECTED`, `EXPIRED`, `POISON`, `FAILED`.

**Priority (MoSCoW):** **M** = Must (assignment or agreed core), **S** = Should, **C** = Could.

## Epic overview

| Epic | Name | Stories | Main sources |
|------|------|---------|--------------|
| E1 | Trade versioning and storage | US-101 to US-107 | R-02, R-03, A-03, A-05 to A-12, A-23, A-24 |
| E2 | Maturity validation and expiry | US-201 to US-205 | R-04, R-05, A-13 to A-20 |
| E3 | Stream ingestion and failure handling | US-301 to US-304 | R-01, R-11, A-21, A-22, A-26 to A-28, A-43 |
| E4 | REST API | US-401 to US-404 | A-21, A-26, A-38 |
| E5 | Audit trail and reporting | US-501 to US-503 | R-10, A-29 to A-31, A-45, A-53 |
| E6 | Delivery pipeline and non-functional | US-601 to US-609 | R-06 to R-09, A-41 to A-54 |

---

## E1: Trade versioning and storage

### US-101: Store a new trade (M)
*As a trade operations user, I want a trade with an unseen `tradeId` stored as its latest version, so that the store reflects every live trade.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-101.1 | No trade `T1` is stored | `T1` v1, maturity 2027-05-20, is submitted | `T1` v1 is stored with `createdDate` = 2026-11-19 and `expired` = N, and the outcome is `ACCEPTED` |
| US-101.2 | No trade `T1` is stored | `T1` v1 is submitted with `createdDate` = 2015-03-14 and `expired` = Y set by the sender | The values from the sender are ignored: `createdDate` = 2026-11-19 and `expired` = N (A-05) |
| US-101.3 | No trade `T1` is stored | `T1` v1 is accepted | A history entry for `T1` v1 exists in the NoSQL store |

### US-102: Higher version replaces the stored trade (M)
*As a trade operations user, I want a higher version to replace the stored trade, so that the store always holds the latest version.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-102.1 | `T2` v1 is stored with counterparty `CP-1` | `T2` v2 with counterparty `CP-2` is submitted | `T2` v2 with `CP-2` is stored and the outcome is `REPLACED` (A-06, A-10) |
| US-102.2 | `T2` v1 is stored | `T2` v3 is submitted (a version gap) | `T2` v3 is stored (A-07) |
| US-102.3 | `T2` v1 is stored | `T2` v2 is accepted | The history for `T2` contains both v1 and v2, ordered by version |

### US-103: Same version replaces the stored trade (M)
*As a trade operations user, I want a trade with the same version to replace the stored record, so that corrections can be re-sent without bumping the version.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-103.1 | `T2` v2 is stored with book `B1` and `createdDate` 2026-11-01 | `T2` v2 with book `B2` is submitted | `T2` v2 with `B2` is stored, `createdDate` is reset to 2026-11-19, and the outcome is `REPLACED` (A-08) |
| US-103.2 | `T2` v2 is stored | An identical `T2` v2 is redelivered | The stored record is unchanged except `createdDate`, the outcome is `REPLACED`, and no error is raised (A-24) |

### US-104: Reject a lower version (M)
*As a trade operations user, I want a trade with a lower version than the stored one rejected with an exception, so that stale data never overwrites newer data.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-104.1 | `T2` v2 is stored | `T2` v1 is submitted | A `LowerVersionException` is raised, and the message names the incoming and stored versions |
| US-104.4 | Any state | A trade breaks one or more rules | **Exactly one** rejection exception is raised, carrying **all** violated reasons. If `LOWER_VERSION` is among them, the exception is identifiable as a `LowerVersionException` (CR-2; class design in Phase 5) |
| US-104.2 | `T2` v2 is stored | `T2` v1 is rejected | The stored `T2` is still v2, unchanged |
| US-104.3 | `T2` v2 is stored | `T2` v1 is rejected | An audit entry `REJECTED` with reason `LOWER_VERSION` is recorded (A-12) |

### US-105: Reject structurally invalid trades (M)
*As a trade operations user, I want trades with missing or invalid fields rejected, so that only well-formed trades are stored.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-105.1 | Any state | A trade with a blank `tradeId`, `counterPartyId` or `bookId` is submitted | It is rejected with reason `INVALID_FIELD`, naming the field (A-03) |
| US-105.2 | Any state | A trade with `version` 0 or negative is submitted | It is rejected with reason `INVALID_FIELD` |
| US-105.3 | Any state | A trade with a missing `maturityDate`, or one not in `yyyy-MM-dd` format, is submitted | It is rejected with reason `INVALID_FIELD` (A-04) |

### US-106: Concurrent updates to the same trade are safe (M)
*Priority raised from S to M by CR-1 (PO review).*
*As a platform owner, I want concurrent submissions for one trade to be handled safely, so that a lower version can never win a race.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-106.1 | `T5` v1 is stored | `T5` v3 and `T5` v2 are processed concurrently | The final stored version is v3. v2 is either rejected as `LOWER_VERSION` or replaced by v3 (A-23) |
| US-106.2 | A conditional write fails because of a concurrent update | The write is retried once | The incoming trade is re-validated against the newly stored version before writing |

### US-107: Assignment example scenario (M)
*Added by CR-3 (PO review).* *As an assessor, I want the assignment's sample trades replayed through the store, so that I can see exactly how the rules treat them.*

Dates are relative to the fixed "today" of 2026-11-19, keeping the shape of the sample table (A-01).

| AC | Given | When | Then |
|----|-------|------|------|
| US-107.1 | An empty store | `T1` v1 (`CP-1`, `B1`, maturity 2027-05-20) is submitted | `ACCEPTED`: stored with `createdDate` 2026-11-19 and `expired` = N |
| US-107.2 | `T1` is stored | `T2` v2 (`CP-2`, `B1`, maturity 2027-05-20) is submitted | `ACCEPTED`: `T2` v2 is stored |
| US-107.3 | `T2` v2 is stored | `T2` v1 (`CP-1`, `B1`, maturity 2027-05-20) is submitted | `REJECTED` with `LOWER_VERSION`, and `T2` stays at v2 |
| US-107.4 | Today is 2026-11-19 | `T3` v3 (`CP-3`, `B2`, maturity 2026-05-20) is submitted | `REJECTED` with `MATURITY_IN_PAST`, and `T3` is not stored |
| US-107.5 | The state after US-107.1 to US-107.4 | The clock moves to 2027-05-21 and the expiry job runs | `T1` and `T2` are `expired` = Y, and the audit trail holds 2 `ACCEPTED`, 2 `REJECTED` and 2 `EXPIRED` entries |

---

## E2: Maturity validation and expiry

### US-201: Reject trades with a past maturity date (M)
*As a trade operations user, I want trades whose maturity date is before today rejected, so that matured trades never enter the store.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-201.1 | Today is 2026-11-19 | A trade with maturity 2026-11-18 is submitted | It is rejected with reason `MATURITY_IN_PAST` and is not stored |
| US-201.2 | Today is 2026-11-19 | A trade with maturity 2026-11-19 (today) is submitted | It is accepted (A-13, boundary case) |
| US-201.3 | `T3` v1 is stored | `T3` v2 with a past maturity is submitted | It is rejected, and the stored `T3` v1 is unchanged (A-14) |
| US-201.4 | `T3` v2 is stored | `T3` v1 with a past maturity is submitted | One rejection exception is raised, identifiable as a `LowerVersionException`, and it carries **both** `LOWER_VERSION` and `MATURITY_IN_PAST` reasons (A-15, US-104.4) |

### US-202: Mark matured trades as expired automatically (M)
*As a trade operations user, I want trades whose maturity date has passed marked as expired automatically, so that the store shows which trades are no longer live.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-202.1 | Today is 2026-11-19, and stored trades mature on 2026-11-18, 2026-11-19 and 2026-11-20 with `expired` = N | The expiry job runs | Only the 2026-11-18 trade becomes `expired` = Y (A-17, boundary case) |
| US-202.2 | A trade has already been marked `expired` = Y | The expiry job runs again | Nothing changes and no duplicate `EXPIRED` audit entry is written (idempotent, A-42) |
| US-202.3 | The job marks `T1` as expired | The job completes | An `EXPIRED` audit entry for `T1` is recorded (A-20) |
| US-202.4 | The schedule is configured by a property | The application starts | The job uses the configured cron (default: daily just after local midnight) (A-18, A-50) |

### US-203: Catch up on expiry at startup (S)
*As a platform owner, I want expiry to run once when the service starts, so that trades are not left stale after downtime.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-203.1 | The service was down over midnight, and `T1` matured yesterday with `expired` = N | The service starts | `T1` is marked `expired` = Y without waiting for the next scheduled run (A-18, A-51) |

### US-204: Replace an expired trade with a valid version (S)
*As a trade operations user, I want an expired trade to accept a valid same or higher version, so that trades can be extended.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-204.1 | `T1` v1 is stored with `expired` = Y | `T1` v2 with maturity 2027-01-01 is submitted | `T1` v2 is stored with `expired` = N (A-09, A-19) |
| US-204.2 | `T1` v1 is stored with `expired` = Y | The expiry job runs | `T1` stays `expired` = Y. The job never sets `expired` back to N |

### US-205: "Today" follows the configured time zone (M)
*As a platform owner, I want "today" calculated in a configurable local time zone, so that the date rules are correct and deterministic.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-205.1 | No time-zone property is set | The application starts | The server's default zone is used (A-16) |
| US-205.2 | The zone is `Asia/Kolkata` and the instant is 2026-11-18T20:00Z (01:30 on 2026-11-19 local) | A trade maturing on 2026-11-18 is validated | It is rejected, because local today is 2026-11-19 |

---

## E3: Stream ingestion and failure handling

### US-301: Consume trades from the stream (M)
*As an upstream trading system, I want to publish trades to a topic and have them stored, so that thousands of trades can flow in asynchronously.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-301.1 | The service is running | A valid trade message is published to the trades topic | It is stored and audited, using the same rules as REST (A-21). The test waits with Awaitility up to a configurable timeout (default 5 s). This is a **test timeout, not an SLA**; performance targets are only in A-41 and US-605 (CR-4) |
| US-301.2 | `T7` v1, v2 and v3 are published in order, keyed by `T7` | They are consumed | They are processed in order, and `T7` v3 is stored with no false `LOWER_VERSION` rejections (A-22) |
| US-301.3 | A message is being processed | The service stops before processing completes | After restart the message is processed again. Its offset is committed only after processing (A-43) |

### US-302: Isolate poison-pill messages (M)
*As a platform owner, I want unreadable messages moved aside, so that one bad message never blocks the stream.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-302.1 | The service is running | A message that is not valid JSON is published, followed by a valid trade | The bad message goes to the dead-letter topic **without retries**, and the valid trade is still stored (A-27) |
| US-302.2 | A poison pill is dead-lettered | The audit trail is queried | A `POISON` entry exists, with the error reason and the raw payload capped at the configured size |
| US-302.3 | A poison pill is dead-lettered | The dead-letter record is inspected | Its headers contain the original topic, partition, offset and exception class |

### US-303: Retry transient failures, then dead-letter (M)
*As a platform owner, I want temporary failures retried with backoff, so that brief outages don't lose trades.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-303.1 | The SQL store fails twice, then recovers | A valid trade is consumed | It is stored after retries, and no dead-letter record is created (A-28) |
| US-303.2 | The SQL store keeps failing | A valid trade is consumed | After the configured attempts (default 3, exponential backoff) the message is dead-lettered and audited as `FAILED` |
| US-303.3 | Retry attempts and backoff are configured by properties | The application starts | The configured values are used (A-50) |

### US-304: Business rejections are final on the stream (M)
*As a platform owner, I want rule violations on the stream recorded but not retried, so that retries are reserved for recoverable errors.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-304.1 | `T2` v2 is stored | `T2` v1 is consumed | It is audited as `REJECTED` and **not** retried or dead-lettered, and the offset is committed (A-26) |

---

## E4: REST API

### US-401: Submit a trade over REST (M)
*As an operations user or tester, I want to submit a single trade over HTTP, so that I can load or correct trades without the stream.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-401.1 | No `T1` is stored | `POST /trades` with valid `T1` v1 | `201 Created` with the stored trade in the body |
| US-401.2 | `T1` v1 is stored | `POST /trades` with `T1` v1 or v2 | `200 OK` with the stored trade (replacement) |
| US-401.3 | `T1` v2 is stored | `POST /trades` with `T1` v1 | `409 Conflict` with the error shape `{timestamp, status, error, reasons[], path}` |
| US-401.4 | Today is 2026-11-19 | `POST /trades` with maturity 2026-11-18 | `422 Unprocessable Entity` with reason `MATURITY_IN_PAST` |
| US-401.5 | Any state | `POST /trades` with malformed JSON or invalid fields | `400 Bad Request` listing the field errors |
| US-401.6 | Any state | `POST /trades` with a body larger than 1 MB | `413 Payload Too Large` (A-47) |

### US-402: Get a trade by ID (M)
*As an operations user, I want to look up the latest version of a trade, so that I can check its current state.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-402.1 | `T1` v2 is stored | `GET /trades/T1` | `200` with v2 and all fields, including `createdDate` and `expired` |
| US-402.2 | No `T9` is stored | `GET /trades/T9` | `404 Not Found` with the standard error shape |

### US-403: List trades (S)
*As an operations user, I want a paged, filterable list of trades, so that I can review live and expired trades.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-403.1 | 25 trades are stored | `GET /trades?page=0&size=10` | 10 trades plus paging metadata (total 25) |
| US-403.2 | Both expired and live trades are stored | `GET /trades?expired=true` | Only expired trades are returned |

### US-404: View a trade's version history (S)
*As an operations user, I want to see every accepted version of a trade, so that I can trace how it changed.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-404.1 | `T2` v1 and v2 were accepted | `GET /trades/T2/history` | Both versions are returned, ordered by version and then by received time |
| US-404.2 | No `T9` exists | `GET /trades/T9/history` | `404 Not Found` |

---

## E5: Audit trail and reporting

### US-501: Record every outcome in an append-only audit trail (M)
*As a compliance user, I want every trade outcome recorded permanently, so that I can prove what happened to every trade.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-501.1 | Any trade is processed through either channel | Processing finishes | One audit entry exists with `tradeId`, `version`, `outcome`, `reasons`, `channel` (STREAM or REST), `eventTimestamp` (UTC) and `payload` (A-29, A-53) |
| US-501.2 | The audit trail exists | Any API is examined | No operation updates or deletes audit entries (A-45) |
| US-501.3 | The SQL write succeeds but the audit write to MongoDB fails | The outbox relay runs again | The audit write is retried until it succeeds, nothing is lost, and an outage longer than the configured number of polls is logged at ERROR with the affected `tradeId`s (ADR-005; reworded by D8, 2026-11-20) |

### US-502: Generate an audit report on demand (M)
*As a compliance user, I want to generate an audit report whenever I need it, so that I can review rejections, poison pills and expiries.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-502.1 | Audit entries exist on several dates | `GET /reports/audit?from=2026-11-01&to=2026-11-19` | Only entries in that local-date range are returned, as JSON (A-30, A-53) |
| US-502.2 | Entries with several outcomes exist | `GET /reports/audit?outcome=REJECTED&tradeId=T2` | Only matching entries are returned |
| US-502.3 | Any filter is applied | The report is generated | The response includes summary counts per outcome for the filtered set |
| US-502.4 | More than one page of entries matches | The report is requested | Results are paged or streamed, never loaded into memory all at once (A-52) |

### US-503: Export the audit report as CSV (C)
*As a compliance user, I want the report as CSV, so that I can open it in a spreadsheet.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-503.1 | Audit entries exist | `GET /reports/audit` with `Accept: text/csv` | A CSV file with a header row and one row per entry is returned |

---

## E6: Delivery pipeline and non-functional

### US-601: CI pipeline with quality gates (M)
*As a developer, I want every push and pull request built and tested automatically, so that broken code never reaches `main`.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-601.1 | A pull request to `main`, or a push to `main` | The workflow runs | The stages in AGENTS.md §6 run in order |
| US-601.2 | A unit test fails | The workflow runs | The build fails |
| US-601.3 | Line coverage is below 80% | The workflow runs | The build fails (A-48) |
| US-601.4 | A `.puml` file has a syntax error | The workflow runs | The build fails. Rendered SVGs are uploaded as artifacts on success |
| US-601.5 | Any run | The workflow finishes | The test, coverage and scan reports are uploaded as artifacts |

### US-602: Open-source vulnerability gate (M)
*As a security owner, I want dependencies scanned on every build, so that critical vulnerabilities never ship.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-602.1 | A dependency has a vulnerability with CVSS ≥ 9 | The pipeline runs | The build fails and the report names the dependency and CVE (A-47) |
| US-602.2 | Only vulnerabilities with CVSS < 9 exist | The pipeline runs | The build passes and the findings appear in the uploaded report |

### US-603: Automated regression suite on real infrastructure (M)
*As a developer, I want end-to-end tests against real containers on every pull request, so that regressions are caught before merging.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-603.1 | The CI runner has Docker | The integration stage runs | Tests run against Testcontainers instances of the broker, SQL and NoSQL stores (no in-memory fakes) |
| US-603.2 | The regression suite | It runs | It covers at least: stream → stored → queryable, a lower-version rejection, a past-maturity rejection, a poison pill → dead-letter topic, an expiry run, and the assignment example scenario (US-107) |

### US-604: Observability (S)
*As a platform owner, I want health checks, metrics and structured logs, so that I can see whether the store is healthy and what it's doing.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-604.1 | The service is running | `GET /actuator/health/readiness` | `UP` only when SQL, NoSQL and the broker are reachable (A-46) |
| US-604.2 | Trades with every outcome have been processed | `GET /actuator/prometheus` | Counters per outcome (rejections by reason), a processing-latency timer and a dead-letter counter are present |
| US-604.3 | A trade is processed | The log is inspected | The log line includes `tradeId`, `version`, `outcome` and `channel`, and no full payload (A-32) |

### US-605: Performance baseline (S)
*As a platform owner, I want a repeatable performance test, so that the throughput target is verified, not assumed.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-605.1 | The local stack runs on a single instance | A burst of 10,000 valid trades is published | All 10,000 are stored or audited within 60 s, with zero loss (A-41) |
| US-605.2 | The local stack runs | REST submissions run at 50 requests per second for 60 s | p95 latency is under 200 ms (A-41) |
| US-605.3 | The test completes | Results are produced | A summary is saved as a build artifact. It runs on demand, not as a gate on every pull request (it would be too slow and unreliable on shared runners) |

### US-606: One-command local run and container image (M)
*As a new developer, I want to start the whole system with one command, so that I can run and evaluate it within minutes.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-606.1 | Docker is installed | `docker compose up` | The app, broker, SQL and NoSQL stores start, and the app becomes healthy (A-49) |
| US-606.2 | The image is built | The container runs | The process runs as a non-root user (A-47) |
| US-606.3 | The repo | A secret scan or review is done | No credentials are committed. All connections come from environment variables or profiles (A-47, A-50) |

### US-607: Input hardening on the stream (S)
*As a security owner, I want oversized messages refused, so that the service cannot be overwhelmed by a single payload.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-607.1 | The maximum message size is 1 MB (configurable) | A larger message arrives | It is handled as a poison pill, audited with a truncated payload (A-47) |
| US-607.2 | A valid trade has extra, unknown fields | It is consumed | The unknown fields are ignored and the trade is processed |

### US-608: Prompt log and design documentation (M)
*As an assessor, I want every AI prompt and design decision documented, so that I can evaluate how the solution was built.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-608.1 | Any phase ends | The repo is inspected | PROMPTS.md has every prompt, with the human decision column filled in (R-09) |
| US-608.2 | Phases 3 to 5 are done | The docs are inspected | Every required PlantUML diagram (AGENTS.md §12) exists and renders in CI (R-08) |

### US-609: README for a 10-minute onboarding (M)
*As a new developer, I want a README that explains the system, so that I can clone, run and understand it in 10 minutes.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-609.1 | A fresh clone | The README is followed | It covers the overview, architecture diagrams, run, test, pipeline stages, assumptions, limitations and future improvements (AGENTS.md Phase 8) |
| US-609.2 | A message has been dead-lettered | An operator follows the README or runbook | They can inspect the dead-letter topic, find the matching `POISON` or `FAILED` audit entry, fix the cause and replay the message manually (A-51, CR-5) |

---

## Traceability: requirements to stories

| Requirement | Stories |
|-------------|---------|
| R-01 Stream ingestion, high volume | US-301, US-605 |
| R-02 SQL latest + NoSQL history/audit | US-101, US-102, US-404, US-501 |
| R-03 Version rule + exception | US-102, US-103, US-104, US-106, US-107 |
| R-04 Past maturity rejected | US-201, US-205 |
| R-05 Auto-expiry | US-202, US-203, US-204 |
| R-06 JUnit + TDD | All stories (Phase 6 process), US-601 |
| R-07 Pipeline, regression, OSS scan | US-601, US-602, US-603 |
| R-08 PlantUML | US-608 |
| R-09 Prompt log | US-608 |
| R-10 Audit + on-demand report | US-501, US-502, US-503 |
| R-11 Poison pill | US-302, US-607 |

## Suggested delivery order

1. **Phase 4 (walking skeleton):** US-601, US-602, US-606 (skeleton only).
2. **Domain core:** US-101, US-105, US-104, US-102, US-103, US-201, US-205, US-106, US-107.
3. **Audit:** US-501.
4. **Channels:** US-401, US-402, US-301, US-304, US-302, US-303.
5. **Expiry:** US-202, US-203, US-204.
6. **Queries and reports:** US-403, US-404, US-502, US-503.
7. **Hardening:** US-607, US-604, US-603 (Phase 7), US-605.
8. **Docs:** US-608 (ongoing), US-609 (Phase 8).

## Gate check
- [x] Every story has Given/When/Then acceptance criteria.
- [x] Non-functional stories included (security scan, observability, performance, deployability).
- [x] Product Owner review done ([02-po-review.md](02-po-review.md)). CR-1 to CR-5 applied and authorised by the human on 2026-11-20. **Backlog approved.**
