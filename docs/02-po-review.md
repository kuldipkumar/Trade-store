# Phase 2: Product Owner Review of the Backlog

| | |
|---|---|
| **Reviewer role** | Product Owner, the owner of [Requirements.md](../Requirements.md) (role played by the AI at the human's request) |
| **Reviewed** | [02-backlog.md](02-backlog.md) (6 epics, 31 stories) and [ASSUMPTIONS.md](../ASSUMPTIONS.md) (A-01 to A-54) |
| **Date** | 2026-11-20 |
| **Verdict** | **Approved** on 2026-11-20, after CR-1 to CR-5 were applied. CR-6 is a constraint on Phase 3. |

> **Independence note:** the same AI wrote the backlog and is reviewing it. This review is a structured self-check against the assignment, not an independent review. The human remains the final approver and can overrule any item below.

---

## 1. Coverage of Requirements.md

Each statement in the assignment is checked against the backlog.

| # | Requirement statement | Covered by | PO verdict |
|---|-----------------------|-----------|------------|
| 1 | Java application using Spring Boot | A-33, US-601 | Covered |
| 2 | A streaming tool | US-301 to US-304 (the tool is chosen in Phase 3) | Covered |
| 3 | Both SQL and NoSQL databases | A-36, US-101.3, US-404, US-501 | Covered |
| 4 | All functionality covered by JUnit tests, using TDD | Every story's acceptance criteria are test specs; US-601.3 (coverage gate) | Covered |
| 5 | Deployment pipeline (GitHub Actions) | US-601 | Covered |
| 6 | Automated regression testing | US-603 | Covered |
| 7 | OSS vulnerability scan; build fails on critical or blocker findings | US-602 | Covered |
| 8 | PlantUML sequence, class and design diagrams | US-608.2 | Covered |
| 9 | Thousands of trades sent to a single store, by any method of transmission | US-301 (stream), US-401 (REST), US-605 (volume) | Covered |
| 10 | Organises and stores each trade in a specific sequence | A-11, US-102.3, US-404.1 | Covered |
| 11 | Lower version rejected **and an exception generated** | US-104 | Covered, but conflicts with US-201.4 (see **CR-2**) |
| 12 | Same version replaces the current record | US-103 | Covered |
| 13 | Reject maturity date earlier than today | US-201 | Covered, including the "equal to today" boundary |
| 14 | Automatically mark a trade expired once its maturity date has passed | US-202, US-203 | Covered |
| 15 | Sample trade table | A-01, A-02 (illustrative only) | **Gap:** no scenario shows how the store treats the assignment's own example (see **CR-3**) |
| 16 | Share all AI prompts in a separate document | US-608.1, PROMPTS.md | Covered |

**Result:** every requirement is covered. Two need work: the exception semantics (#11) and the example scenario (#15).

---

## 2. Change requests (must be applied before sign-off)

| ID | Item | Finding | Requested change |
|----|------|---------|------------------|
| **CR-1** | US-106 priority | Trades arrive through **both** REST and the stream (A-21, agreed). A race between the two channels could let a lower version overwrite a higher one, which breaks requirement rule 1. That is a core-rule integrity issue, not a nice-to-have. | Raise **US-106 from Should to Must**. |
| **CR-2** | US-104.1 vs US-201.4 | US-104.1 says a lower version raises `LowerVersionException`. US-201.4 (from A-15) says a trade breaking both rules is rejected with **both** reasons. The backlog doesn't say which exception is raised when several rules fail, so the two tests could contradict each other. | Restate the ACs so that **every rejection raises one exception carrying all the violated reasons**, and a rejection that includes `LOWER_VERSION` is identifiable as a lower-version exception (US-104.1 still holds). The exact class design is left to Phase 5 (LLD). |
| **CR-3** | Sample data | The assessor will look for the assignment's own table. A-01 rightly treats it as illustrative, but nothing shows how the store **would** handle those rows. | Add **US-107: Assignment example scenario (Must)**. Replay the sample rows with dates relative to the fixed "today": T1 v1 (future maturity) → `ACCEPTED`; T2 v2 → `ACCEPTED`; T2 v1 → `REJECTED` (`LOWER_VERSION`); T3 v3 (past maturity) → `REJECTED` (`MATURITY_IN_PAST`). Then move the clock past T1's maturity and run expiry → T1 `expired` = Y. This also becomes part of the regression suite (US-603.2). |
| **CR-4** | US-301.1 "within 5 s" | This is a new timing promise that isn't backed by any agreed assumption. | Reword it as a **test timeout** (Awaitility, configurable, default 5 s), not a service-level promise. Performance targets stay solely in A-41 and US-605. |
| **CR-5** | A-51 (manual dead-letter replay) | This assumption is agreed, but no story makes it a deliverable, so an operator would have no instructions. | Add **US-609.2**: the README (or a runbook) explains how to inspect and replay dead-lettered messages. |

---

## 3. Constraint passed to Phase 3 (design)

| ID | Finding | Constraint |
|----|---------|------------|
| **CR-6** | A-37 (SQL first, then audit, with retry) leaves a small window where a crash loses an audit entry. That conflicts with two agreed assumptions: A-29 ("every inbound trade and lifecycle event is audited") and A-45 (audit integrity). | The Phase 3 consistency ADR must show that **no outcome of an accepted or rejected trade can be lost from the audit trail**, including after a crash. The architect still chooses the mechanism (for example an outbox table, or relying on stream redelivery). The PO sets the guarantee, not the solution. |

---

## 4. Assumptions: agree or disagree

| Assumptions | PO position | Comment |
|-------------|-------------|---------|
| A-01, A-02 (sample data illustrative) | Agree | Conditional on CR-3, so the example is still demonstrated. |
| A-03 to A-05 (fields, ISO dates, store-set dates) | Agree | |
| A-06, A-07, A-10, A-11 (versioning and sequence) | Agree | |
| A-08 (same version resets `createdDate`) | Agree, with an observation | A stream **redelivery** of an identical message (A-24) will also reset `createdDate`. This is consistent with A-08 and acceptable. It must be stated under known limitations so it isn't mistaken for a bug. |
| A-09, A-19 (expired trade can be revived by a valid version) | Agree | |
| A-12, A-15 (exception and all reasons) | Agree | Subject to CR-2. |
| A-13, A-14, A-16, A-17 (boundaries and time zone) | Agree | The boundary cases (equal to today accepted; expires only once the date is before today) are exactly what the assessor will test. |
| A-18, A-20 (daily job and startup catch-up) | Agree | Between midnight and the job run, a trade can briefly show `expired` = N. That is acceptable. |
| A-21 to A-24 (channels, ordering, concurrency, duplicates) | Agree | Subject to CR-1. |
| A-25, A-41 (performance) | Agree | As baselines, not production SLAs. |
| A-26 to A-28 (rejections, poison pill, retries) | Agree | The poison-pill handling the human requested is well covered by US-302 and US-607. |
| A-29 to A-31, A-45 (audit and report) | Agree | |
| A-37 (SQL first, then audit) | **Agree only with CR-6** | The guarantee matters more than the mechanism. |
| A-32 to A-36, A-38, A-40 (scope and technology) | Agree | |
| A-39 (no authentication) | Agree, with an observation | The audit report exposes all trade data without authentication. Acceptable for the assignment; it must be the first item under known limitations. |
| A-42 to A-44, A-46 to A-54 (NFRs) | Agree | A-52 (indexes) has no acceptance criterion. It should be verified in the Phase 5 schema design rather than by a story. |

**Disagreements: none outright.** A-37 is accepted only with the CR-6 condition.

---

## 5. Open Phase 2 questions: PO answers

| Question | PO answer |
|----------|-----------|
| Are the MoSCoW priorities right? | **Yes, except CR-1** (US-106 becomes Must). Keeping US-503 (CSV) as Could and US-604/605 as Should is right. **Delivery rule:** every Must story is finished before any Should or Could story starts. |
| Should US-605 (performance) run on demand rather than on every pull request? | **Agree.** It runs on demand and its results are stored as a build artifact. |
| Is a 413 response for REST bodies over 1 MB acceptable? | **Agree.** It follows from the agreed A-47. |

---

## 6. Sign-off

- [x] All requirements in Requirements.md are traced to stories.
- [x] All assumptions reviewed (agree, or agree with a condition).
- [x] CR-1 to CR-5 applied to 02-backlog.md (2026-11-20).
- [x] Human confirmed this review and authorised the changes (2026-11-20).

**Product Owner sign-off: APPROVED** (2026-11-20). Phase 3 may start under constraint CR-6.
