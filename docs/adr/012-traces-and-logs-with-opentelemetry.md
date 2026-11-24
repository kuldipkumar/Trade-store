# ADR-012: Traces and logs with OpenTelemetry, Tempo and Loki

- **Status:** Accepted (2026-11-24)
- **Context:** Metrics alone (ADR-010) show that something is wrong, not where or why. A trade crosses Kafka, the ingestion use case, PostgreSQL, an asynchronous hand-off (the transactional outbox, ADR-005) and MongoDB. Operators need to follow one trade across all of these and see its log lines.

## Decision
- **Tracing API:** Micrometer Tracing with the OpenTelemetry bridge, through Spring Boot 4's `spring-boot-starter-opentelemetry`. Spans are exported over OTLP to **Tempo**. Sampling is 1.0 in this PoC and configurable.
- **Spans:**
  - **HTTP and Kafka:** Spring's own observations. On Kafka, the W3C `traceparent` travels in the message headers, so producer and consumer share a trace.
  - **Ingestion:** a `trade.ingest` span tagged `trade.id`, `trade.version`, `trade.channel` and `trade.outcome`.
  - **Databases:** OpenTelemetry instrumentation libraries give one span per PostgreSQL statement (JDBC `DataSource` wrapper) and one per MongoDB command (command listener).
- **Crossing the outbox:** the ingestion transaction stores the current `traceparent` in `outbox_events.traceparent` (V3 migration). The relay continues that trace for each event, so the MongoDB writes join the trade's trace about a second later. Without this, the relay would start unrelated traces.
- **The tracing port:** the use cases depend on a small `TraceContext` port (current `traceparent`, run work in a span), not on a tracing library. Unit tests use a recording fake.
- **Logs:** console lines carry `[traceId-spanId]`. The OpenTelemetry Logback appender exports every record over OTLP to **Loki**; Loki 3 ingests OTLP natively and keeps the trace and span IDs as structured metadata, so no log agent is needed.
- **Grafana correlation:**
  - **Metrics → traces:** exemplars on the latency histograms.
  - **Logs → traces:** derived field on `trace_id`.
  - **Traces → logs:** `tracesToLogsV2`.
  - **Dashboard:** gains a logs panel and a traces panel.
- **Compose:** Prometheus, Tempo, Loki and Grafana are part of the default `docker compose up`, because the app always exports. ADR-010's opt-in profile is retired.
- **Metrics stay on Prometheus:** the starter's OTLP metrics push is disabled.

## Alternatives
- **Zipkin or Jaeger** for traces: Tempo integrates with Grafana and Loki without another UI, and stores traces cheaply.
- **Promtail or Grafana Alloy** to ship logs: an extra agent container. Loki 3's native OTLP endpoint makes it unnecessary.
- **An OpenTelemetry Collector** between the app and the stores: useful for routing and tail sampling at scale, but one more component than a PoC needs. It is the natural next step for production.
- **Child spans or span links for the outbox relay:** links suit batch processing in general. Here each relayed event belongs to exactly one trade, so a child span gives the most readable trace.

## Consequences
- **Each trade's whole path is visible with timings:** for example, a slow SQL statement or the relay delay.
- **New dependencies:** the OpenTelemetry instrumentation libraries are alpha-versioned (2.28.1-alpha, matching OTel API 1.62) and need watching on upgrades.
- **Costs:** a small overhead per trade (spans) and more data stored. In production, sampling would be below 1.0, with tail sampling in a collector.
- **More containers:** `docker compose up` starts eight of them.
