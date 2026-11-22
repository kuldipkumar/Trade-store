# ADR-011: Project portal on GitHub Pages

- **Status:** Accepted (2026-11-22, the human's choice)
- **Context:** Developers, architects and senior leaders need one view of the whole journey: requirements, tests, design, the live pipeline, code, security and observability, with working links. A claude.ai page can't call the GitHub API, so it can't show live status by itself.

## Decision
- **The portal** is one static page (`portal/index.html`) built from data that `scripts/portal_data.py` generates from the repository's documents and the CI run's reports.
- **Publishing:** `.github/workflows/portal.yml` publishes it to **GitHub Pages** after every green CI run of a push to `main`, together with that run's coverage, Trivy and ZAP reports.
- **Live status:** the page reads the GitHub API for pipeline status, step by step, and falls back to a snapshot (`scripts/portal_status.py`).
- **Presenting:** a claude.ai copy of the same page is used to present, with a status snapshot and links to the live site.

## Alternatives
- **claude.ai page only:** private and simple, but its status is a snapshot until it is republished.
- **A wiki or README only:** no live status and no single view.
- **Backstage or a similar developer portal:** far more setup than one service needs.

## Consequences
- **Public site.** The portal is public, like the repository.
- **Fork protection.** The deploy job alone holds Pages write rights. It runs only for green pushes to this repository's `main`, so a fork's pull request cannot publish.
- **Rate limit.** Unauthenticated GitHub API calls are limited to 60 per hour per viewer; the page caches the status for a minute.
