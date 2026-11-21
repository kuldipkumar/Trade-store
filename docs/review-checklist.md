# Author checklist

The author's own check before opening a pull request (AGENTS.md section 3, Phase 6). The merge gate itself is `/code-review` plus green CI; the human reviews merged pull requests when they have time.

| # | Area | What is checked |
|---|------|-----------------|
| 1 | **Scope** | One story (or one clearly named slice). The PR lists the acceptance criteria it covers. Nothing unrelated. |
| 2 | **TDD evidence** | For each criterion, a `test:` commit (red) comes before the `feat:` commit (green). Red failed on an assertion, not only a compile error. A test that passed on its first run is explained and verified by mutation. |
| 3 | **Tests prove the criteria** | Every clause of each criterion's *Then* is asserted. Boundaries and failure paths are covered. One behaviour per test, `should_…_when_…` naming, Arrange/Act/Assert, AssertJ, no `Thread.sleep`. Every test has a correct `@Tag`. |
| 4 | **Design** | Matches the LLD and ADRs. SOLID: one reason to change per class, new behaviour by new classes, services depend on interfaces. No speculative code. Constructor injection only. Injected `Clock` for dates. |
| 5 | **Observability** | New behaviour has its metrics, log lines and health signals, test-first (AGENTS.md section 4). Logs carry IDs only, never payloads. |
| 6 | **Security and data** | No secrets. Input limits respected. No new dependency with known critical vulnerabilities (Trivy). SQL is parameterised. |
| 7 | **Documentation** | Diagrams and LLD updated in the same PR when the design changed. `docs/traceability.md` regenerated. `docs/06-test-review.md` verdicts updated. Prompts logged. |
| 8 | **CI** | All stages green, including the traceability check and both vulnerability scans. Coverage gate met. |

## Outcome
- Every item satisfied: open the pull request, run `/code-review`, fix real findings, merge when CI is green.
- An item that needs a product or design decision: flag it to the human and carry on with other stories meanwhile.
