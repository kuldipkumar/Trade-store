# Assumptions

The list of assumptions the Trade Store is built on. The reasoning and the impact of each one are in [docs/01-clarifications.md](docs/01-clarifications.md); IDs match (`A-07` here = `C-07` there).

**Status:** **Agreed** (decided by product owner) · **Delegated** (product owner asked the AI to choose; reviewed) · **Proposed** (awaiting approval)

## Sample data and fields
| ID | Assumption | Status |
|----|------------|--------|
| A-01 | The sample trade table is historical and illustrative. It shows the shape of a trade, not data points to reproduce. | Agreed |
| A-02 | A lower version arriving after a higher one (like T2 v1 in the sample) is rejected on ingestion. | Agreed |
| A-03 | Mandatory input fields: `tradeId`, `version` (≥ 1), `counterPartyId`, `bookId`, `maturityDate`. A missing or invalid field rejects the trade as invalid. | Proposed |
| A-04 | Input dates use ISO-8601 `yyyy-MM-dd`. | Proposed |
| A-05 | The store sets `createdDate` (the local date the version was received) and `expired`. Values from the sender are ignored. | Proposed |

## Version rule
| ID | Assumption | Status |
|----|------------|--------|
| A-06 | A higher version is accepted and replaces the stored record. | Proposed |
| A-07 | Version gaps are allowed. | Proposed |
| A-08 | A same-version replacement overwrites all fields and resets `createdDate` to the replacement date. | Delegated |
| A-09 | An expired trade can be replaced by a valid same or higher version, and `expired` is recalculated. | Proposed |
| A-10 | `counterPartyId` and `bookId` may change between versions. | Proposed |
| A-11 | "Sequence" means version order, not arrival order. | Agreed |
| A-12 | A lower version raises `LowerVersionException`. It is not stored in SQL but is audited as `REJECTED`. | Proposed |

## Maturity, time and expiry
| ID | Assumption | Status |
|----|------------|--------|
| A-13 | A maturity date equal to today is valid. | Delegated |
| A-14 | The maturity rule applies to every incoming version, including replacements. | Proposed |
| A-15 | All validators run, and every violation is recorded. | Proposed |
| A-16 | "Today" uses the local time zone (the server default, overridable by config) through an injected `Clock`. | Agreed |
| A-17 | A trade expires when `maturityDate < today`. | Delegated |
| A-18 | Expiry runs as a scheduled daily job just after local midnight, plus once at startup. | Proposed |
| A-19 | Only a new valid version can reset `expired` from Y to N. | Proposed |
| A-20 | Each expiry is recorded as an `EXPIRED` event. | Proposed |

## Ingestion
| ID | Assumption | Status |
|----|------------|--------|
| A-21 | Two inbound channels, the stream (primary) and REST, sharing the same validation and storage logic. | Agreed |
| A-22 | Messages are keyed by `tradeId` so that versions of a trade are processed in order. | Agreed |
| A-23 | Concurrent writes to the same trade are guarded at the database level (conditional or optimistic-locking write). | Proposed |
| A-24 | Redelivered duplicates are harmless same-version replacements. | Proposed |
| A-25 | Throughput target: thousands of trades per minute on a single instance, with no hard SLA. | Proposed |

## Errors, poison pills and audit
| ID | Assumption | Status |
|----|------------|--------|
| A-26 | Business rejections are not retried and are audited as `REJECTED`. REST returns 409 for a lower version, 422 for a past maturity date and 400 for malformed input. | Proposed |
| A-27 | Poison pills are never retried. They go to a dead-letter topic and are audited as `POISON`, and the consumer continues. | Agreed |
| A-28 | Transient failures are retried with backoff (default 3 attempts), then sent to the dead-letter topic and audited as `FAILED`. | Proposed |
| A-29 | Every inbound trade and lifecycle event is audited in the NoSQL database. | Agreed |
| A-30 | The audit report is generated on demand through REST, filtered by date, trade and outcome, in JSON or CSV with summary counts. | Agreed |
| A-31 | Audit and history are retained indefinitely. | Proposed |
| A-32 | Trade data is not PII. Logs record IDs and outcomes only. | Proposed |

## Scope and technology
| ID | Assumption | Status |
|----|------------|--------|
| A-33 | Java 21 with Spring Boot 3.x. | Agreed |
| A-34 | GitHub Actions for CI/CD. | Agreed |
| A-35 | The streaming tool is chosen in Phase 3 (ADR). | Agreed |
| A-36 | SQL holds the latest version and is the system of record. NoSQL holds history and audit. | Agreed |
| A-37 | SQL commits first, then the history and audit write with retry. Phase 3 will weigh this against an outbox in an ADR. | Proposed |
| A-38 | Minimal read API: trade by ID, paged list, history and audit report. | Proposed |
| A-39 | Authentication and authorisation are out of scope. | Proposed |
| A-40 | Trade cancellation and deletion are out of scope. | Proposed |
