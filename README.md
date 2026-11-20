# Trade Store

A trade store that receives trades, enforces version and maturity-date rules, and automatically marks matured trades as expired.

> Work in progress. The full README (architecture, run and test instructions, pipeline, assumptions, limitations) is written in Phase 8.

- Assignment: [Requirements.md](Requirements.md)
- Engineering playbook: [AGENTS.md](AGENTS.md)
- Documentation index: [docs/README.md](docs/README.md)
- Assumptions: [ASSUMPTIONS.md](ASSUMPTIONS.md)
- AI prompt log: [PROMPTS.md](PROMPTS.md)

## Build and run (walking skeleton)

Requires JDK 21 and a running Docker daemon (integration tests use Testcontainers).

```bash
./mvnw verify                 # unit + integration tests, 80% coverage gate
docker compose up --build     # app + Kafka + PostgreSQL + MongoDB
curl localhost:8080/actuator/health
scripts/render-diagrams.sh    # render PlantUML to docs/diagrams/out
```
