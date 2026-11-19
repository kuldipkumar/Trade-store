# Trade Store

A trade store that receives trades, enforces version and maturity-date rules, and automatically marks matured trades as expired.

> Work in progress. The full README (architecture, run and test instructions, pipeline, assumptions, limitations) is written in Phase 8.

- Assignment: [Requirements.md](Requirements.md)
- Engineering playbook: [Agent.md](Agent.md)
- AI prompt log: [Prompt.md](Prompt.md)

## Build (current skeleton)

Requires JDK 21.

```bash
./mvnw verify
```
