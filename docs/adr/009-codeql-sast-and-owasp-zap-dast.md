# ADR-009: CodeQL for SAST and OWASP ZAP for DAST

- **Status:** Accepted (2026-11-22)
- **Context:** ADR-006 covers open-source dependencies and the image (Trivy). The audience review asked for static (SAST) and dynamic (DAST) application scans as well. A-48 lists CodeQL as optional; A-47 asks for security gates in the pipeline.

## Decision
- **SAST: GitHub CodeQL** in its own workflow (`codeql.yml`), `security-extended` queries, `build-mode: none` for Java. It runs on pull requests, pushes to `main` and weekly. Findings go to the repository's Security tab, where they are triaged.
- **DAST: OWASP ZAP API scan** (`zap-api-scan.py`) in the CI compose job, against the running stack. It is driven by an OpenAPI contract (`docs/api/openapi.yaml`). The build fails on any **High** risk alert (`scripts/zap_gate.py`); Medium and lower are reported and uploaded.

## Alternatives
- **SonarQube / Semgrep** for SAST: both need a server or an account and a token. CodeQL is native to GitHub and free for public repositories.
- **ZAP baseline scan:** it only spiders, and an API with no HTML pages gives it almost nothing to find. The API scan exercises every operation in the contract.
- **Burp Suite:** commercial, and not suited to an unattended CI run.

## Consequences
- **New OpenAPI contract.** The contract is a maintained artifact. A conformance test checks that it lists every endpoint.
- **Longer compose job.** The ZAP API scan adds a few minutes, which is acceptable because it runs in parallel with the main build.
- **SAST doesn't fail the build.** CodeQL findings are reviewed in the Security tab. Making them a merge gate needs branch protection, a repository setting outside this codebase.
