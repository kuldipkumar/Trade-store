# Milestones

One entry per story that is completed, tested, peer-reviewed and merged. The same log, as light pink cards, is on the [milestone page](https://claude.ai/artifact/UJftKaSok4JAN9916q2PfH) (private; share it from the page's Share menu).

| # | Story | Completed | PR | Criteria | Review |
|---|-------|-----------|----|----------|--------|
| 1 | US-601 CI pipeline with quality gates | 2026-11-20 | (Phase 4, direct to main) | verified by the pipeline | before peer review existed |
| 2 | US-105 Reject structurally invalid trades | 2026-11-20 | #39 | 3 of 3 | before peer review existed |
| 3 | US-104 Reject a lower version | 2026-11-20 | #40 | 4 of 4 | before peer review existed |
| 4 | US-101 Store a new trade | 2026-11-20 | #41 | 3 of 3 | before peer review existed |
| 5 | US-201 Reject trades with a past maturity date | 2026-11-21 | #44 | 4 of 4 | before peer review existed |
| 6 | US-205 "Today" follows the configured time zone | 2026-11-21 | #46 | 2 of 2 | Approve with notes |
| 7 | US-102 Higher version replaces the stored trade | 2026-11-21 | #47 | 3 of 3 | Approve with notes |
| 8 | US-103 Same version replaces the stored trade | 2026-11-21 | #48 | 2 of 2 | Approve |
| 9 | US-106 Concurrent updates to the same trade are safe | 2026-11-21 | #50 | 2 of 2 | /code-review: 2 important + 3 nits fixed |
| 10 | US-202 Mark matured trades as expired automatically | 2026-11-21 | #51 | 4 of 4 | /code-review: 2 important + 4 nits fixed |
| 11 | US-203 Catch up on expiry at startup | 2026-11-21 | #52 | 1 of 1 | /code-review: 2 important + 4 nits fixed |
| 12 | US-204 Replace an expired trade with a valid version | 2026-11-21 | #53 | 2 of 2 | /code-review: 3 nits fixed |
| 13 | US-107 Assignment example scenario | 2026-11-21 | #54 | 5 of 5 | /code-review: 1 important + 5 nits fixed |
| 14 | US-401 Submit a trade over REST | 2026-11-21 | #56 | 6 of 6 | /code-review: 7 important + 3 nits fixed |
| 15 | US-402 Get a trade by ID | 2026-11-21 | #57 | 2 of 2 | /code-review: 1 important + 6 nits handled |
| 16 | US-403 List trades | 2026-11-21 | #58 | 2 of 2 | /code-review: 5 important + 2 nits handled |
| 17 | US-404 View a trade's version history | 2026-11-21 | #59 | 2 of 2 | /code-review: 1 important + 2 nits handled |
| 18 | US-501 Record every outcome in an append-only audit trail | 2026-11-21 | #55 (+#56, #60) | 3 of 3 | /code-review: 2 important + 7 nits fixed |
| 19 | US-301 Consume trades from the stream | 2026-11-21 | #60 | 3 of 3 | /code-review: 3 important + 3 nits fixed |
| 20 | US-304 Business rejections are final on the stream | 2026-11-21 | #60 | 1 of 1 | found and fixed by /code-review of #60 |
| 21 | US-302 Isolate poison-pill messages | 2026-11-21 | #61 | 3 of 3 | /code-review: 2 important + 5 nits fixed |
| 22 | US-303 Retry transient failures, then dead-letter | 2026-11-21 | #62 | 3 of 3 | /code-review: 2 important + 5 nits; fixed, retry window flagged as D9 |
| 23 | US-502 Generate an audit report on demand | 2026-11-21 | #63 | 4 of 4 | /code-review: 2 important + 5 nits fixed |
| 24 | US-503 Export the audit report as CSV | 2026-11-21 | #64 | 1 of 1 | /code-review: 3 important + 4 nits fixed |
| 25 | D9 Outages never dead-letter a trade (option C) | 2026-11-21 | #65 | US-303.1, US-303.2 | /code-review: 1 important + 5 nits fixed |
| 26 | US-607 Input hardening on the stream | 2026-11-22 | #66 | 2 of 2 | /code-review: 2 important + 6 nits fixed |
| 27 | US-604 Observability | 2026-11-22 | #66 | 3 of 3 | /code-review of #66 |
| 28 | US-603 Automated regression suite on real infrastructure | 2026-11-22 | #66 | 2 of 2 | /code-review of #66 |
