# Phase 1: Requirement Analysis and Clarifications

Source: [Requirements.md](../Requirements.md). The resulting assumptions are kept in [ASSUMPTIONS.md](../ASSUMPTIONS.md) (same ID numbers: `C-07` here corresponds to `A-07` there).

**Status key**
- **Agreed:** decided by the product owner.
- **Delegated:** the product owner asked the AI to choose; please review.
- **Proposed:** raised by the AI; needs approval at the Phase 1 gate.

---

## 1. Requirements restated

| ID | Requirement (in our own words) |
|----|--------------------------------|
| R-01 | Build a Java Spring Boot service that receives a high volume of trades (thousands) through a streaming platform. |
| R-02 | Keep the **latest version of each trade** in a SQL database, and keep **trade history and audit** in a NoSQL database. |
| R-03 | **Version rule:** reject a trade whose version is lower than the stored version, signalling it with an exception. A trade with the same version replaces the stored one. |
| R-04 | **Maturity rule:** reject a trade whose maturity date is before today. |
| R-05 | **Expiry rule:** mark a stored trade as expired automatically once its maturity date has passed. |
| R-06 | Cover all behaviour with JUnit tests written test-first (TDD). |
| R-07 | Run a CI/CD pipeline (GitHub Actions) with automated regression tests and an open-source vulnerability scan that **fails the build on critical or blocker** findings. |
| R-08 | Document the design with PlantUML (sequence, class and design diagrams). |
| R-09 | Log every AI prompt in a separate document ([PROMPTS.md](../PROMPTS.md)). |
| R-10 | *(Added by product owner)* Keep an **audit trail** of every trade outcome and let users **generate an audit report on demand**. |
| R-11 | *(Added by product owner)* Handle **poison-pill** messages safely without blocking the stream. |

---

## 2. Clarifications

### A. Sample data and trade fields

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-01 | The sample table contradicts the rules: T1 matured on 20/05/2020 but shows Expired = N, and T3 matured in 2014 (so rule R-04 would reject it) yet is listed as stored. | The sample data is **historical and illustrative only**. It shows the shape of a trade, not data points to reproduce. Tests are built from the rules, not from the table. | Low. If the table were meant as test data, rows T1 and T3 would contradict the rules. | Agreed |
| C-02 | T2 version 1 is listed after T2 version 2. Should a lower version be kept? | Same as C-01: the table is illustrative. On ingestion, a lower version is **rejected** (R-03). | Low. | Agreed |
| C-03 | What are the fields, types and mandatory rules? | `tradeId` (non-blank string), `version` (integer ≥ 1), `counterPartyId` (non-blank string), `bookId` (non-blank string) and `maturityDate` (date) are mandatory on input. `createdDate` and `expired` are set by the store. A missing or invalid field rejects the trade as **invalid**. | Medium. Different mandatory fields would change validation and schemas. | Agreed |
| C-04 | What date format does the input use? The sample uses `dd/MM/yyyy`. | Messages and the API use **ISO-8601 `yyyy-MM-dd`**. `dd/MM/yyyy` appears only in the sample display. | Medium. Producers sending `dd/MM/yyyy` would fail to parse. | Agreed |
| C-05 | Does the sender or the store set `createdDate` and `expired`? | The **store** sets both. `createdDate` is the local date the stored version was received. `expired` is derived from `maturityDate`. Any values the sender provides are ignored. | Low. | Agreed |

### B. Version rule and ordering

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-06 | What happens when a **higher** version arrives? | It is accepted and **replaces** the stored record. The previous version stays in history. | Low. The requirement implies this. | Agreed |
| C-07 | Are version gaps allowed (v1 then v3)? | **Yes.** Only "lower than stored" is rejected. | Low. | Agreed |
| C-08 | When the **same version** replaces a record, what happens to `createdDate`? | All business fields are overwritten. `createdDate` is **reset to the date of the replacement**, because it means "the date this version was received", and `expired` is recalculated. The replaced record is kept in history. | Medium. Reports by created date would differ. | Agreed (delegated) |
| C-09 | Can an **expired** trade be replaced by a same or higher version? | **Yes**, if the incoming trade passes the maturity rule. `expired` is then recalculated (normally back to N). | Medium. | Agreed |
| C-10 | Can `counterPartyId` or `bookId` change between versions? | **Yes.** Only `tradeId` identifies a trade. | Low. | Agreed |
| C-11 | What does "store each trade in a specific sequence" mean? | The sequence is the **version number** of each trade, not the arrival order. The SQL store holds the highest accepted version, and history is ordered by version and then by receipt time. | Medium. Arrival-order semantics would change the version check. | Agreed |
| C-12 | What should "reject and generate an exception" produce for a lower version? | The domain throws a specific exception (`LowerVersionException`). The trade is **not** stored in SQL, and a `REJECTED` entry with the reason is written to the audit trail (see C-26). | Medium. | Agreed |

### C. Maturity rule and time

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-13 | Is a maturity date **equal to today** valid? | **Yes.** Only a date strictly before today is rejected. | Medium. This is a boundary-test case. | Agreed (delegated) |
| C-14 | Does the maturity rule apply to replacements (same or higher version)? | **Yes.** Every incoming version must pass every rule. | Low. | Agreed |
| C-15 | If a trade breaks both rules, which reason is reported? | **All** validators run, and **every** violation is recorded on the rejection. | Low. | Agreed |
| C-16 | Which time zone defines "today"? | **Local time zone.** This means the server's default zone, overridable by a config property (for example `trade-store.timezone`). An injected `Clock` keeps tests deterministic. | Medium. Around midnight, servers in different zones would disagree. | Agreed |

### D. Expiry

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-17 | When is a maturity date "surpassed"? | When `maturityDate < today` (local). A trade maturing today expires at the start of the following day. | Medium. This is a boundary-test case and must match C-13. | Agreed (delegated) |
| C-18 | How is "automatically" achieved? | A **scheduled job** runs daily just after local midnight (configurable cron). It also runs **once at startup** to catch up after downtime. The job is idempotent. | Medium. Between runs, the flag can be stale for at most the schedule interval. | Agreed |
| C-19 | Can `expired` go back from Y to N? | Only when a new valid version replaces the trade (C-09). The job never un-expires a trade. | Low. | Agreed |
| C-20 | Is expiry recorded in history or audit? | **Yes.** An `EXPIRED` event is written for each trade the job marks. | Low. | Agreed |

### E. Ingestion and throughput

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-21 | Which channels send trades in: streaming, REST or both? | **Both.** The streaming platform is the main high-volume channel. A REST endpoint accepts single trades for manual or test use and serves queries. Both channels use the **same** validation and storage logic. | Medium. REST-only or stream-only would change the design scope. | Agreed |
| C-22 | How is ordering guaranteed for versions of the same trade? | Messages are **keyed by `tradeId`**, so every version of a trade lands on the same partition and is processed in order. | High. Without this, out-of-order processing causes false lower-version rejections. | Agreed |
| C-23 | What if the stream and REST update the same trade at the same moment? | The database protects against it with a conditional or optimistic-locking write. The losing write is retried once, then re-validated. | Medium. | Agreed |
| C-24 | Streaming delivery is at-least-once. What about duplicates? | A redelivered duplicate has the same version, so it **replaces** the record with identical data. The outcome is harmless and is audited as `REPLACED`. | Low. | Agreed |
| C-25 | "Thousands of trades": is there a throughput or latency target? | No hard SLA. The target is to sustain **thousands of trades per minute on a single instance**, checked by a non-functional performance story. Measurable targets are in **C-41**. | Medium. A strict SLA would need partition and concurrency tuning. | Agreed |

### F. Errors, poison pills and audit

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-26 | How are business rejections (lower version, past maturity, invalid fields) handled? | **Stream:** not retried and not sent to the dead-letter topic. They are audited as `REJECTED` with the reasons, and the offset is committed. **REST:** `409 Conflict` for a lower version, `422 Unprocessable Entity` for a past maturity date, `400 Bad Request` for malformed input. All REST errors use one consistent error shape. | Medium. | Agreed |
| C-27 | How is a **poison pill** handled, meaning a message that cannot be deserialised or is structurally invalid? | It is **never retried**. It goes to a **dead-letter topic** with error details in its headers, and is audited as `POISON` with the raw payload (size-capped). The consumer then moves on, so the partition never stalls. | High. A poison pill without handling blocks the partition forever. | Agreed |
| C-28 | How are **transient** failures (for example, the database being unavailable) handled? | **Retry with exponential backoff** (configurable, default 3 attempts). If retries run out, the message goes to the dead-letter topic and is audited as `FAILED`. Transient failures are kept separate from poison pills, so only transient ones are retried. | High. Retrying a poison pill, or dropping a transient failure, loses data or blocks processing. | Agreed |
| C-29 | What does the audit trail record? | **Every** inbound trade and lifecycle event: `ACCEPTED`, `REPLACED`, `REJECTED` (with reasons), `EXPIRED`, `POISON` and `FAILED`. Each entry records the trade ID, version, source channel (stream or REST), timestamp and payload. It is stored in the NoSQL database. | Medium. | Agreed |
| C-30 | What is the **audit report**? | It is generated **on demand** through a REST endpoint, filtered by date range, trade ID and outcome. It returns JSON or CSV, plus summary counts per outcome. | Medium. | Agreed |
| C-31 | How long is audit and history retained? | **Indefinitely** for this assignment. A retention or TTL policy is a future improvement. | Low. | Agreed |
| C-32 | Is trade data sensitive (PII)? | **No.** Trade fields are business identifiers, so storing the payload in the audit trail is acceptable. Application logs still record only IDs and outcomes, never full payloads. | Low. | Agreed |

### G. Scope and technology

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-33 | Java or Python? | **Java 21** with Spring Boot 4.1 (was 3.x; see ADR-007). | None. | Agreed |
| C-34 | GitHub Actions or Jenkins? | **GitHub Actions.** | None. | Agreed |
| C-35 | Which streaming tool? | To be **decided in Phase 3** with an ADR. | Medium. | Agreed (deferred) |
| C-36 | SQL and NoSQL: which is the system of record? | **SQL** holds the latest version of each trade and is the system of record. **NoSQL** holds the version history and the audit trail. | Medium. | Agreed (from AGENTS.md) |
| C-37 | How do SQL and NoSQL stay consistent if one write fails? | SQL is authoritative and commits first. The history and audit write follows, with retry. A failed audit write is logged and alerted on, not silently dropped. **Phase 3** will weigh this against a transactional outbox in an ADR. | Medium. A crash between the two writes could leave an audit gap. | Agreed |
| C-38 | Are read APIs needed? | **Yes, minimal:** `GET /trades/{tradeId}`, `GET /trades` (paged, filterable by `expired`), `GET /trades/{tradeId}/history`, plus the audit report (C-30). | Low. | Agreed |
| C-39 | Are authentication and authorisation needed? | **Out of scope** for the assignment. This will be listed as a known limitation. | Low. | Agreed |
| C-40 | Can trades be cancelled or deleted? | **Out of scope.** Trades only change by new versions and by expiry. | Low. | Agreed |

### H. Non-functional requirements (NFRs)

The assignment states no NFRs except "thousands of trades", the OSS vulnerability gate and test coverage. The targets below are sized for an assignment running on a laptop or CI runner, and each must be verifiable by a test or a pipeline check.

| ID | Question | Proposed Assumption | Impact if wrong | Status |
|----|----------|---------------------|-----------------|--------|
| C-41 | **Performance:** what measurable target makes "thousands of trades" testable? (This refines C-25.) | On a single instance with the local container stack, a **burst of 10,000 trades** on the stream is fully processed (stored or rejected, and audited) **within 60 seconds** with **zero loss**. Through REST, a single trade submission has **p95 latency under 200 ms** at 50 requests per second. Both are checked by a performance test story. These are baselines, not production SLAs. | Medium. A real production SLA would need load testing on production-like hardware. | Agreed |
| C-42 | **Scalability:** can the service scale beyond one instance? | **Yes, horizontally.** The app is stateless. Stream consumers share one consumer group, so parallelism is bounded by the partition count (configurable, default 6). Ordering per trade still holds (C-22). The expiry job is **idempotent** (a single conditional update), so concurrent runs on several instances are harmless. Only one instance is deployed for the assignment. | Medium. Non-idempotent jobs or in-memory state would break when scaled out. | Agreed |
| C-43 | **Availability and resilience:** what uptime is expected, and what happens on a crash or restart? | **No HA SLA** for the assignment (single instance). The service must **recover without data loss**: stream offsets are committed only **after** a trade is processed (at-least-once delivery), it shuts down gracefully, and it resumes from the last committed offset. A broker or database outage triggers the retry policy in C-28, not a crash loop. | High. Committing offsets before processing could silently lose trades. | Agreed |
| C-44 | **Durability and consistency:** can an accepted trade ever be lost or partly written? | An accepted trade is never lost once acknowledged. The SQL write is **transactional**, and the version check plus write is atomic per trade (C-23). History and audit are **eventually consistent** with SQL, as covered by the ADR in C-37. | High. | Agreed |
| C-45 | **Audit integrity:** can audit entries be changed? | **No.** The audit trail is **append-only**. No API updates or deletes audit entries, and each entry stores both an event timestamp and a received timestamp. | Medium. A mutable audit trail is not trustworthy for reporting. | Agreed |
| C-46 | **Observability:** how do operators know the service is healthy and what it's doing? | **Logs:** structured logs that include `tradeId`, `version`, `outcome` and `channel`, with no full payloads (C-32). **Health:** liveness and readiness endpoints (Spring Boot Actuator, the framework's built-in monitoring endpoints) that check the SQL database, NoSQL database and broker. **Metrics** (Micrometer), exposed in Prometheus format: counts of accepted, replaced, rejected (by reason), poison, failed and expired trades; processing latency; consumer lag; and dead-letter count. Dashboards, alerting and distributed tracing are **future improvements**. | Medium. Without metrics, poison pills and lag go unnoticed. | Agreed |
| C-47 | **Security**, beyond authentication (C-39): what is required? | The **OSS scan fails the build on CVSS ≥ 9**, meaning critical or blocker findings (assignment requirement). **No secrets** in the repo; credentials come from environment variables, and local defaults exist only for the dev stack. **Input hardening:** a maximum message and request size (default 1 MB), and strict schema validation with unknown fields ignored. The container runs as a **non-root user**. Optionally, automated dependency-update pull requests (Dependabot). | Medium. | Agreed |
| C-48 | **Maintainability:** which quality gates apply? | Line coverage of at least **80%** (JaCoCo; the build fails below that), the conventions in AGENTS.md, an ADR for each significant decision, and optional CodeQL static analysis. | Low. | Agreed |
| C-49 | **Deployability and portability:** how is it run? | **One command** (`docker compose up`) starts the app and all of its infrastructure locally. The app is shipped as a Docker image. Configuration comes from Spring profiles and environment variables, with the same image in every environment. | Medium. | Agreed |
| C-50 | **Configurability:** what must be externalised? | Topic names, partition count, retry attempts and backoff, the expiry cron, the time zone (C-16), size limits, and database and broker connections. Nothing environment-specific is hard-coded. | Low. | Agreed |
| C-51 | **Recoverability:** how are dead-lettered messages and missed expiries recovered? | Missed expiries are caught up by the startup run (C-18). Dead-lettered messages are kept and visible in the audit report. **Replaying them is a manual, documented step** for now; a replay tool is a future improvement. | Medium. Teams may expect automatic reprocessing. | Agreed |
| C-52 | **Capacity:** what data volumes must the design handle? | Up to **1 million trades** in SQL and **10 million audit or history entries** in NoSQL without a design change. Indexes on `tradeId` (unique), `maturityDate` and `expired` (for the expiry job), and on audit `timestamp`, `outcome` and `tradeId` (for reports). The audit report **streams or pages** its results rather than loading them all into memory. | Medium. Unindexed expiry or report queries degrade as data grows. | Agreed |
| C-53 | **Timestamps:** how is time stored? | Business dates (`maturityDate`, `createdDate`) are local dates (C-16). Technical timestamps (audit and history) are stored as **UTC instants**, with the zone recorded in configuration. Report filters accept local dates and convert them. | Medium. Mixed zones make audit ordering ambiguous. | Agreed |
| C-54 | **Backup and disaster recovery:** are they required? | **Out of scope.** They're an infrastructure concern, and the database volumes are local in the dev stack. This will be listed as a known limitation. | Low. | Agreed |

---

## 3. Gate check

- [x] Every ambiguity has a question **and** a stated assumption.
- [x] Product owner has reviewed and approved every **Delegated** and **Proposed** item (2026-11-19). All assumptions are now **Agreed**.
