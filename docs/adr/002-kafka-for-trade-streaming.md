# ADR-002: Apache Kafka as the streaming platform

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect

## Context
The assignment requires "a streaming tool" for thousands of trades. Agreed assumptions add four needs:
- per-trade ordering (A-22);
- horizontal consumer scaling (A-42);
- at-least-once processing with offsets committed after processing (A-43);
- poison-pill isolation and retries for transient failures, with a dead-letter topic (A-27, A-28).

## Decision
Use **Apache Kafka** in **KRaft mode**, which needs no ZooKeeper, through **Spring Kafka**.
- **Topic `trades.inbound`:** message key = `tradeId`, partitions configurable (default 6), JSON value.
- **Topic `trades.inbound.DLT`:** the dead-letter topic, with the same partition count. Spring's `DeadLetterPublishingRecoverer` adds headers for the original topic, partition, offset and exception.
- **Consumer:** a consumer group with auto-commit disabled; an offset is committed only after its record is processed.
- **`ErrorHandlingDeserializer`:** turns deserialisation failures into poison pills instead of crash loops.
- **`DefaultErrorHandler`:** retries with exponential backoff (default 3 attempts, configurable). Deserialisation, validation and business-rejection exceptions are **not retryable**.
- **Business rejections** (lower version, past maturity) are handled in the listener as a normal outcome. They are never thrown to the error handler, so they are neither retried nor dead-lettered (A-26).

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| RabbitMQ | Ordering per trade across competing consumers needs extra exchange plugins or single-consumer queues. Messages can't be replayed once consumed. |
| Redpanda | Compatible with the Kafka API and lighter to run, but less common in industry and in assessors' expectations. Code written for Kafka would also run on it. |
| ActiveMQ / JMS | Weaker ordering and replay model for high-volume streams. |

## Consequences
- Per-trade ordering holds as long as producers key by `tradeId`. This must be documented as a producer contract.
- At-least-once delivery means redelivered duplicates. These are harmless because the same version replaces the record (A-24), and the event-ID scheme in ADR-005 keeps the audit consistent.
- Testcontainers has first-class Kafka support for the integration suite (US-603).
- One more component in the local stack (the Docker Compose service).
