# ADR-007: Spring Boot 4.1 instead of Spring Boot 3.5

- **Status:** Accepted (2026-11-20)
- **Deciders:** Product owner (human), AI architect

## Context
The project started on Spring Boot 3.5.16 (Spring Framework 6.2.19, Tomcat 10.1.55), in line with A-33 / C-33 ("Spring Boot 3.x"). On its first full run, the CI vulnerability gate (ADR-006) failed the build on **7 CRITICAL CVEs**:

| Library | CVEs | Fixed in |
|---------|------|----------|
| Tomcat 10.1.55 | CVE-2026-55276, CVE-2026-65182, CVE-2026-65637, CVE-2026-65905, CVE-2026-68525 | Tomcat 10.1.59+ / 11.0.25+ |
| Spring Web MVC 6.2.19 | CVE-2026-47884, CVE-2026-47890 | Spring Framework 7.0.9+ only |

Spring Framework 6.2 no longer receives free public releases (6.2.19 is the last one on Maven Central), so the Spring Web MVC findings can't be fixed while staying on Spring Boot 3.5.

## Decision
Upgrade to **Spring Boot 4.1.2** (released 2026-10-22). It manages Spring Framework 7.0.10, Tomcat 11.0.27, Jackson 3.1.7, Testcontainers 2.0.5 and Flyway 12.4.0, which clears all seven findings without suppressions. This supersedes "Spring Boot 3.x" in A-33 / C-33 and AGENTS.md.

The upgrade was done in Phase 4, before any business code existed, so its cost was limited to build and test setup:
- Boot 4's modular starters: `spring-boot-starter-webmvc`, `spring-boot-starter-kafka`, `spring-boot-starter-flyway`.
- Tests: `RestTestClient` (`@AutoConfigureRestTestClient`) instead of `TestRestTemplate`; `@AutoConfigureMetrics` instead of `@AutoConfigureObservability`; Testcontainers 2 artifact and package names.
- Jackson 3 (`tools.jackson.*` packages).

## Alternatives considered
| Option | Why not chosen |
|--------|----------------|
| Stay on Boot 3.5, override Tomcat to 10.1.61 and list the two Spring CVEs in `.trivyignore` (with a justification: no XSLT views, no SSE) | It hides findings instead of fixing them, and it leaves us on a framework line with no free security fixes, so new CRITICAL findings would keep failing the gate. |
| Commercial extended support for Spring Framework 6.2 | Cost and licensing. Not justified for this project. |

## Consequences
- The vulnerability gate passes with no suppressions.
- We stay on a supported framework line that gets regular security fixes.
- Jackson 3 and the Boot 4 module layout apply to all later phases. The LLD (Phase 5) and implementation (Phase 6) use Boot 4 APIs.
- Some third-party Spring Boot 3 tutorials and libraries won't apply as-is; check compatibility before adding a dependency.
