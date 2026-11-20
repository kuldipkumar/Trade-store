# AGENTS.md: Engineering Playbook

This file tells Claude (and any engineer) how to build applications in this repo.
It is reusable across projects. Project-specific details go only in the
"Project Context" section (Section 11).

---

## 1. Working Agreement

- Follow the phases in Section 2 **in order**. Do not write production code before Phase 5 is approved.
- At the end of each phase, **stop and wait for human approval** before moving on.
- Never invent requirements. If something is unclear, add it to `docs/01-clarifications.md` and propose an assumption.
- Prefer small, reviewable changes. One story, one logical change per commit.
- Explain *why* for every significant decision (record it as an ADR).
- When unsure between two approaches, present both with trade-offs. Do not pick silently.
- Use PlantUML for every diagram (see Section 12).

---

## 2. SDLC Phases and Gates

| # | Phase | Output | Gate (must be true to proceed) |
|---|-------|--------|--------------------------------|
| 0 | Setup | Repo structure, `PROMPTS.md`, this file | Repo builds with an empty app |
| 1 | Requirement analysis | `docs/01-clarifications.md` | Every ambiguity has a question **and** a stated assumption |
| 2 | Epics and stories | `docs/02-backlog.md` | Each story has Given/When/Then acceptance criteria |
| 3 | High-level design | `docs/03-hld.md`, `docs/diagrams/*.puml`, `docs/adr/*.md` | Components, tech choices and ADRs agreed; diagrams render |
| 4 | Walking skeleton and CI/CD | `.github/workflows/ci.yml` | Pipeline green: build, diagrams, test, scan, coverage gate all running |
| 5 | Low-level design | `docs/05-lld.md`, class / sequence / state / ER diagrams | Every story maps to classes and a sequence flow |
| 6 | TDD implementation | Code and tests per story | Red → Green → Refactor followed; pipeline green |
| 7 | Integration and regression | Testcontainers suite | End-to-end flows pass in CI |
| 8 | Documentation | `README.md`, final docs | Someone new can clone, run and understand it in 10 minutes |

---

## 3. Phase Instructions

### Phase 0: Setup
- Create the repo structure (Section 7), `PROMPTS.md` (Section 9) and this file.
- Start logging prompts from the very first one.

### Phase 1: Requirement Analysis
- Restate each requirement in your own words.
- List ambiguities, edge cases and contradictions (including contradictions in sample data).
- Format each entry as: **ID | Question | Proposed Assumption | Impact if wrong**.

### Phase 2: Epics and User Stories
- Format: *As a [role], I want [capability], so that [benefit].*
- Every story has acceptance criteria in Given/When/Then format.
- These acceptance criteria are the test specifications for Phase 6.
- Include non-functional stories (security scan, observability, performance) as well.

### Phase 3: High-Level Design
- Draw all diagrams in PlantUML (Section 12): context diagram, component / container diagram and deployment diagram.
- Justify every technology choice in an ADR (`docs/adr/NNN-title.md`: Context, Decision, Alternatives, Consequences).
- Every component must have a clear reason to exist. Do not add a technology just to tick a box.

### Phase 4: Walking Skeleton and CI/CD
- Create a minimal app, one passing test, and the full pipeline (Section 6) **before** building features.
- Add a PlantUML activity diagram of the pipeline flow.
- The pipeline must fail on any of these:
  - test failure
  - coverage below threshold
  - a critical or blocker vulnerability
  - a diagram syntax error

### Phase 5: Low-Level Design
- Draw all diagrams in PlantUML (Section 12):
  - Class diagram for the domain, services and repositories.
  - One sequence diagram per main flow, covering both the happy path and the failure paths.
  - State diagram for any entity lifecycle (for example, trade states).
  - Activity diagram for complex branching logic.
  - ER / data model diagram for the SQL and NoSQL schemas.
- Define interfaces, the API or message contracts, and the error-handling strategy.

### Phase 6: TDD Implementation
For **each story**, in this order:
1. **Red:** write one failing test derived from an acceptance criterion.
2. **Green:** write the minimum code needed to pass it.
3. **Refactor:** clean up while keeping tests green.
4. Commit with the message `test:`, `feat:` or `refactor:` plus the story ID.

Repeat until all of that story's acceptance criteria are covered. Commits should show the TDD rhythm.

### Phase 7: Integration and Regression
- Use Testcontainers for real infrastructure (database, message broker), not in-memory fakes.
- The regression suite runs on every pull request and on every push to `main`.

### Phase 8: Documentation
`README.md` must cover:
- overview
- architecture diagrams (rendered from PlantUML)
- how to run locally
- how to run tests
- the pipeline stages
- assumptions
- known limitations
- future improvements

---

## 4. Design Principles

### SOLID
- **S** (Single Responsibility): one reason to change per class. Each validation rule is its own class.
- **O** (Open/Closed): add behaviour by adding classes (for example, a new `Validator` implementation), not by editing existing ones.
- **L** (Liskov Substitution): any implementation of an interface must be swappable without breaking callers.
- **I** (Interface Segregation): keep interfaces small and focused.
- **D** (Dependency Inversion): services depend on interfaces (repositories, publishers), never on concrete infrastructure.

### KISS / YAGNI
- Use the simplest design that meets the requirements.
- Start with one deployable unit unless the requirements demand more.
- No speculative abstractions, frameworks or patterns without a concrete need.

### Other
- Fail fast, using clear domain exceptions (for example, `LowerVersionException`).
- Validate at the boundary. Keep domain objects valid and immutable where practical.
- Use configuration over hard-coding (cron schedules, thresholds, topic names).
- Inject a `Clock` wherever logic depends on the current date or time, so tests are deterministic.

---

## 5. Testing Standards

### Test pyramid
- **Unit tests** (JUnit 5 + Mockito): the majority. Fast, with no Spring context where avoidable.
- **Slice tests**: `@DataJpaTest`, `@WebMvcTest` and similar, used where needed.
- **Integration tests**: Testcontainers for the database and message broker.
- **End-to-end**: a few critical flows (message in → stored → queryable).

### Rules
- Name tests `should_<expectedBehaviour>_when_<condition>`.
- Structure each test as Arrange / Act / Assert, with one behaviour per test.
- Use AssertJ for assertions.
- Cover the happy path, boundaries (for example, a date equal to today), and failure paths.
- Never use `Thread.sleep` in tests. Use Awaitility for async behaviour.
- Coverage gate: **JaCoCo ≥ 80% line coverage**. The build fails below this.

---

## 6. CI/CD Standards (GitHub Actions)

**Triggers:** pull request to `main`, and push to `main`.

**Stages, in order:**
1. Checkout and set up the JDK (with dependency caching).
2. Render and validate the PlantUML diagrams (fail on syntax error).
3. Build and run unit tests.
4. Integration and regression tests (Testcontainers).
5. Coverage report and gate (JaCoCo).
6. **OSS vulnerability scan**: OWASP Dependency-Check with `failBuildOnCVSS=9`, or Trivy with `--severity CRITICAL --exit-code 1`. Build **fails** on critical or blocker findings.
7. Optional: CodeQL static analysis.
8. Package the artifact or Docker image.
9. Upload test, coverage and scan reports, plus the rendered diagrams, as build artifacts.

---

## 7. Repository Structure

```
/
├── AGENTS.md
├── CLAUDE.md         (imports AGENTS.md for Claude Code)
├── PROMPTS.md
├── ASSUMPTIONS.md
├── README.md
├── docker-compose.yml
├── .github/workflows/ci.yml
├── docs/
│   ├── 01-clarifications.md
│   ├── 02-backlog.md
│   ├── 03-hld.md
│   ├── 05-lld.md
│   ├── adr/
│   └── diagrams/
│       ├── *.puml
│       └── out/          (rendered SVGs)
└── src/
    ├── main/java/<base>/
    │   ├── api/          (controllers, DTOs)
    │   ├── messaging/    (consumers, producers)
    │   ├── domain/       (entities, value objects, exceptions)
    │   ├── service/      (use cases, validators)
    │   ├── persistence/  (repositories: sql/, nosql/)
    │   ├── scheduler/
    │   └── config/
    └── test/java/<base>/ (mirrors main; integration tests in an `it` package)
```

---

## 8. Coding Conventions

- Java 21 LTS, Spring Boot 4.x, Maven.
- Use constructor injection only (no field `@Autowired`).
- Use records for DTOs and immutable value objects.
- Handle errors centrally with `@ControllerAdvice`, using a consistent error response shape.
- Use structured logging (SLF4J). Never log secrets or full payloads containing sensitive data.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `ci:`) plus the story ID.

---

## 9. Prompt Logging (`PROMPTS.md`)

Log **every** prompt given to the AI, in this format:

| # | Phase | Prompt (verbatim) | Purpose | AI Output Summary | Accepted / Modified / Rejected and Why |
|---|-------|-------------------|---------|-------------------|----------------------------------------|

The last column is mandatory. It records human judgment over the AI's output.

---

## 10. Definition of Done (per story)

- [ ] Acceptance criteria covered by tests written before the code
- [ ] All tests green locally and in CI
- [ ] Coverage gate passed
- [ ] No critical or blocker vulnerabilities
- [ ] PlantUML diagrams created or updated in the same commit, and rendering successfully in CI
- [ ] Docs updated if the design changed
- [ ] Prompt(s) logged in `PROMPTS.md`

---

## 11. Project Context (edit per project)

- **Project:** Trade Store
- **Stack:** Java 21, Spring Boot 4.1 (ADR-007), Kafka (KRaft), PostgreSQL + Flyway (system of record: latest trade version, plus transactional outbox), MongoDB (trade history and audit, including rejections), Testcontainers, GitHub Actions, Trivy, PlantUML
- **Core rules:**
  - Reject a trade whose version is lower than the stored version.
  - A trade with the same version replaces the stored record.
  - Reject a trade whose maturity date is earlier than today.
  - Automatically mark a trade as expired once its maturity date has passed.
- **Current phase:** 4 (Walking skeleton and CI/CD), in progress. Spring Boot 4.1.2 upgrade (ADR-007) on branch `wip/spring-boot-4`: pom.xml and tests migrated, docs updated. Paused 2026-11-20 because of slow internet. Next: re-render 03-c4-container.svg, run `./mvnw verify` locally (needs images mongo:8.0.38, postgres:17.11-alpine, apache/kafka:4.3.2, plantuml/plantuml:1.2026.9), merge to main, get CI fully green including both Trivy scans. Gate = first fully green pipeline run.

---

## 12. Diagram Standards (PlantUML)

- **PlantUML is mandatory for every diagram.** No image-only diagrams. Diagrams are code: versioned, diffable and reviewable.
- Store sources in `docs/diagrams/` and name them `<phase>-<type>-<name>.puml` (for example, `05-seq-trade-ingestion.puml`).
- Render the diagrams to SVG in `docs/diagrams/out/` and embed the SVGs in the docs and the README.

### Required diagrams

| Phase | Diagram | PlantUML type |
|-------|---------|---------------|
| 3 | System context | C4 (`C4_Context`) or component |
| 3 | Component / container | C4 (`C4_Container`) or component |
| 3 | Deployment (local / CI) | Deployment |
| 4 | CI/CD pipeline flow | Activity |
| 5 | Class diagram | Class |
| 5 | Sequence diagram per main flow | Sequence |
| 5 | Lifecycle (for example, trade states) | State |
| 5 | Complex branching logic | Activity |
| 5 | Data model | Entity (ER) |

### Conventions
- One diagram per file, each with a `title`.
- Sequence diagrams show the happy path, plus failure paths in `alt` / `else` blocks. Label exceptions explicitly.
- Participant names must match the actual class and component names in the code.
- **Update the diagram in the same commit as the code change it reflects.**

### CI
- A pipeline step renders all `.puml` files to SVG (for example, the `plantuml` Docker image or a GitHub Action). The build fails if a diagram has a syntax error.
- Upload the rendered diagrams as a build artifact.