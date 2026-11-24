# ADR-010: Prometheus and a provisioned Grafana dashboard

- **Status:** Accepted (2026-11-22)
- **Context:** The app already exposes Micrometer metrics at `/actuator/prometheus` (US-604, A-46). A dashboard was needed for operations and for live demos, without changing how the app is deployed.

## Decision
- **Part of the default stack** (since ADR-012; first an opt-in profile). `docker compose up` starts Prometheus (scraping every 5 s) and Grafana.
- **Dashboard as code.** The Grafana datasource and the Trade Store dashboard are provisioned from files in `observability/`.
- **Latency histograms.** `trade.processing` and `http.server.requests` publish percentile histograms, so p95 and p99 are computed with `histogram_quantile` and aggregate correctly across instances.
- **Checked in CI.** `scripts/observability-smoke.sh` checks in CI that Prometheus scrapes the app and that Grafana serves the dashboard.

## Alternatives
- **Client-side percentiles** (`percentiles: 0.95`): cheaper to store, but they can't be aggregated across instances.
- **A hosted service** (Grafana Cloud, Datadog): needs accounts and keys, which is too much for a local assignment build.

## Consequences
- **More time series:** the histogram buckets add series, an acceptable cost at this scale.
- **Local only for now.** Production would add alerting rules, for example on dead letters, retries, outbox backlog and consumer lag. That is listed as future work.
