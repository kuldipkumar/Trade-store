# Trade Store Documentation

The Markdown files in this repository are the **source of truth**. GitHub Issues track delivery, and they are generated from the backlog rather than edited by hand.

## Requirements

| Document | What it holds | Status |
|----------|---------------|--------|
| [Requirements.md](../Requirements.md) | The original assignment | Input |
| [01-clarifications.md](01-clarifications.md) | Requirements restated; every ambiguity with a question, an assumption and its impact | Approved 2026-11-19 |
| [ASSUMPTIONS.md](../ASSUMPTIONS.md) | Register of agreed assumptions (A-01 to A-54), including NFRs | Approved 2026-11-19 |
| [02-backlog.md](02-backlog.md) | 6 epics and 32 user stories with Given/When/Then acceptance criteria | Approved 2026-11-20 |
| [02-po-review.md](02-po-review.md) | Product Owner review: requirement coverage, change requests, sign-off | Approved 2026-11-20 |

## Design, delivery and operations

| Document | What it holds | Status |
|----------|---------------|--------|
| [03-hld.md](03-hld.md), [adr/](adr/), [diagrams/](diagrams/) | High-level design, 8 ADRs, every PlantUML diagram (rendered to `diagrams/out/`) | Approved |
| [05-lld.md](05-lld.md) | Low-level design: components, contracts, error handling, metrics, decisions D1 to D9 | Approved; kept in step with the code |
| [06-test-review.md](06-test-review.md) | A verdict for every acceptance criterion, known limitations, decisions D8 and D9 | Complete |
| [traceability.md](traceability.md) | Requirements → epics → stories → criteria → tests → code (generated, checked in CI) | 89 of 89 criteria tested |
| [milestones.md](milestones.md) | One row per story merged, with its PR and review | 32 of 32 stories |
| [runbook-dead-letter.md](runbook-dead-letter.md) | Inspect, find, fix and replay a dead-lettered message | Operations |

## Tracking

- **Issues:** [all user stories](https://github.com/kuldipkumar/Trade-store/issues?q=label%3Atype%3Astory) and [phase gates](https://github.com/kuldipkumar/Trade-store/issues?q=label%3Atype%3Aphase-gate)
- **Milestones:** [one per phase](https://github.com/kuldipkumar/Trade-store/milestones)
- **Labels:** `epic:E1`–`epic:E6`, `priority:must|should|could`, `type:story|phase-gate`
- **Project board:** [Trade Store](https://github.com/users/kuldipkumar/projects/3) (Backlog → Ready → In Progress → In Review → Done)

### Keeping issues in sync with the backlog

1. Edit [02-backlog.md](02-backlog.md). Never edit generated issues directly.
2. Run `python3 scripts/sync_backlog_issues.py --dry-run` to preview the changes, then run it without the flag to apply them. Add any **new** issue to the project board (`gh project item-add 3 --owner kuldipkumar --url <issue-url>`).
3. The script creates or updates issues, labels and milestones. It never opens or closes issues.
4. Reference the story ID in commits (for example `feat: US-104 reject lower version`). Pull requests close a story with `Closes #<issue>`.

## Process

- [AGENTS.md](../AGENTS.md): engineering playbook (phases, gates, standards)
- [PROMPTS.md](../PROMPTS.md): every AI prompt with the human decision
