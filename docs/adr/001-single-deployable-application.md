# ADR-001: Single deployable Spring Boot application

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect

## Context
The Trade Store has two ingestion channels (Kafka and REST), a scheduled expiry job, an outbox relay and an audit report. All of them share the same domain rules. The requirements set no independent scaling, release or ownership boundaries. AGENTS.md §4 (KISS/YAGNI) says: "Start with one deployable unit unless the requirements demand more."

## Decision
Build **one Spring Boot application** containing the REST API, the Kafka consumer, the ingestion service, the validators, the expiry job, the outbox relay and the report service. Separation inside the application comes from packages (`api`, `messaging`, `service`, `domain`, `persistence`, `scheduler`, `config`) and from interfaces between them, not from separate services.

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| Microservices (ingestion, expiry, audit/reporting as separate services) | Distributed transactions and more pipelines and deployments, with no requirement that justifies them. This would be premature optimisation (the human's own words). |
| A modular monolith with enforced module boundaries (for example, Spring Modulith) | Adds framework and ceremony for a small codebase. Can be added later if the codebase grows. |

## Consequences
- One build, one image, one pipeline, and transactions stay local.
- The app scales horizontally as one unit. Kafka consumer parallelism is capped by the partition count (A-42).
- The expiry job and the outbox relay run in every instance, so both must be safe to run concurrently: the expiry update is idempotent, and the relay claims rows with `FOR UPDATE SKIP LOCKED` (ADR-005).
- If one part later needs to scale independently (for example, reporting), it can be extracted along its existing package boundary.
