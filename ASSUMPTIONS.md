# Assumptions

The list of assumptions the Trade Store is built on. The reasoning and the impact of each one are in [docs/01-clarifications.md](docs/01-clarifications.md); IDs match (`A-07` here = `C-07` there).

**Status:** all assumptions were approved by the product owner on 2026-11-19. **Agreed (delegated)** means the AI proposed the choice at the product owner's request, and the product owner then approved it.

## Sample data and fields
| ID | Assumption | Status |
|----|------------|--------|
| A-01 | The sample trade table is historical and illustrative. It shows the shape of a trade, not data points to reproduce. | Agreed |
| A-02 | A lower version arriving after a higher one (like T2 v1 in the sample) is rejected on ingestion. | Agreed |
| A-03 | Mandatory input fields: `tradeId`, `version` (≥ 1), `counterPartyId`, `bookId`, `maturityDate`. A missing or invalid field rejects the trade as invalid. | Agreed |
| A-04 | Input dates use ISO-8601 `yyyy-MM-dd`. | Agreed |
| A-05 | The store sets `createdDate` (the local date the version was received) and `expired`. Values from the sender are ignored. | Agreed |

## Version rule
| ID | Assumption | Status |
|----|------------|--------|
| A-06 | A higher version is accepted and replaces the stored record. | Agreed |
| A-07 | Version gaps are allowed. | Agreed |
| A-08 | A same-version replacement overwrites all fields and resets `createdDate` to the replacement date. | Agreed (delegated) |
| A-09 | An expired trade can be replaced by a valid same or higher version, and `expired` is recalculated. | Agreed |
| A-10 | `counterPartyId` and `bookId` may change between versions. | Agreed |
| A-11 | "Sequence" means version order, not arrival order. | Agreed |
| A-12 | A lower version raises `LowerVersionException`. It is not stored in SQL but is audited as `REJECTED`. | Agreed |

## Maturity, time and expiry
| ID | Assumption | Status |
|----|------------|--------|
| A-13 | A maturity date equal to today is valid. | Agreed (delegated) |
| A-14 | The maturity rule applies to every incoming version, including replacements. | Agreed |
| A-15 | All validators run, and every violation is recorded. | Agreed |
| A-16 | "Today" uses the local time zone (the server default, overridable by config) through an injected `Clock`. | Agreed |
| A-17 | A trade expires when `maturityDate < today`. | Agreed (delegated) |
| A-18 | Expiry runs as a scheduled daily job just after local midnight, plus once at startup. | Agreed |
| A-19 | Only a new valid version can reset `expired` from Y to N. | Agreed |
| A-20 | Each expiry is recorded as an `EXPIRED` event. | Agreed |

## Ingestion
| ID | Assumption | Status |
|----|------------|--------|
| A-21 | Two inbound channels, the stream (primary) and REST, sharing the same validation and storage logic. | Agreed |
| A-22 | Messages are keyed by `tradeId` so that versions of a trade are processed in order. | Agreed |
| A-23 | Concurrent writes to the same trade are guarded at the database level (conditional or optimistic-locking write). | Agreed |
| A-24 | Redelivered duplicates are harmless same-version replacements. | Agreed |
| A-25 | Throughput target: thousands of trades per minute on a single instance, with no hard SLA (measurable targets in A-41). | Agreed |

## Errors, poison pills and audit
| ID | Assumption | Status |
|----|------------|--------|
| A-26 | Business rejections are not retried and are audited as `REJECTED`. REST returns 409 for a lower version, 422 for a past maturity date and 400 for malformed input. | Agreed |
| A-27 | Poison pills are never retried. They go to a dead-letter topic and are audited as `POISON`, and the consumer continues. | Agreed |
| A-28 | Transient failures are retried with backoff (default 3 attempts), then sent to the dead-letter topic and audited as `FAILED`. | Agreed |
| A-29 | Every inbound trade and lifecycle event is audited in the NoSQL database. | Agreed |
| A-30 | The audit report is generated on demand through REST, filtered by date, trade and outcome, in JSON or CSV with summary counts. | Agreed |
| A-31 | Audit and history are retained indefinitely. | Agreed |
| A-32 | Trade data is not PII. Logs record IDs and outcomes only. | Agreed |

## Scope and technology
| ID | Assumption | Status |
|----|------------|--------|
| A-33 | Java 21 with Spring Boot 4.1 (was 3.x; upgraded to fix CRITICAL CVEs, see ADR-007). | Agreed |
| A-34 | GitHub Actions for CI/CD. | Agreed |
| A-35 | The streaming tool is chosen in Phase 3 (ADR). | Agreed |
| A-36 | SQL holds the latest version and is the system of record. NoSQL holds history and audit. | Agreed |
| A-37 | ~~SQL commits first, then the history and audit write with retry.~~ **Superseded by [ADR-005](docs/adr/005-transactional-outbox-for-audit.md):** a transactional outbox, as required by PO constraint CR-6. | Superseded |
| A-38 | Minimal read API: trade by ID, paged list, history and audit report. | Agreed |
| A-39 | Authentication and authorisation are out of scope. | Agreed |
| A-40 | Trade cancellation and deletion are out of scope. | Agreed |

## Non-functional requirements
| ID | Assumption | Status |
|----|------------|--------|
| A-41 | **Performance:** a burst of 10,000 streamed trades is fully processed and audited within 60 s with zero loss. A REST submission has p95 latency under 200 ms at 50 requests per second. Both are baselines for a single instance on the local stack. | Agreed |
| A-42 | **Scalability:** the app is stateless and scales horizontally through the consumer group, up to the partition count (default 6). The expiry job is idempotent. One instance is deployed for the assignment. | Agreed |
| A-43 | **Resilience:** no HA SLA. Offsets are committed only after processing (at-least-once), shutdown is graceful, and a restart loses no data. | Agreed |
| A-44 | **Durability:** SQL writes are transactional and atomic per trade. History and audit are eventually consistent with SQL. | Agreed |
| A-45 | **Audit integrity:** the audit trail is append-only and immutable. | Agreed |
| A-46 | **Observability:** structured logs (IDs and outcomes only), Actuator health checks (both databases and the broker), and Micrometer/Prometheus metrics for outcomes, latency, consumer lag and the dead-letter topic. Dashboards and tracing are future work. | Agreed |
| A-47 | **Security:** the OSS scan fails the build on CVSS ≥ 9, there are no secrets in the repo, input is capped at 1 MB with schema validation, and the container runs as non-root. | Agreed |
| A-48 | **Maintainability:** JaCoCo line coverage of at least 80%, ADRs, and optional CodeQL. | Agreed |
| A-49 | **Deployability:** `docker compose up` runs everything locally from one Docker image, configured through profiles and environment variables. | Agreed |
| A-50 | **Configurability:** topics, partitions, retries, cron, time zone, size limits and connections are all externalised. | Agreed |
| A-51 | **Recoverability:** expiries are caught up at startup. Replaying dead-lettered messages is a manual, documented step. | Agreed |
| A-52 | **Capacity:** 1M trades and 10M audit entries without a design change. Expiry and report queries are indexed, and reports stream or page their results. | Agreed |
| A-53 | **Timestamps:** business dates are local dates. Audit and history timestamps are UTC instants. | Agreed |
| A-54 | **Backup and DR** are out of scope (a known limitation). | Agreed |
