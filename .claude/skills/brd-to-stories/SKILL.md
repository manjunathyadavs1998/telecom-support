---
name: brd-to-stories
description: Turn the BRD (docs/brd/brd.md) into implementable user stories and keep docs/requirements/traceability.md in sync. Use when starting a delivery phase, when asked "what is left", when a BRD ID is mentioned, or after finishing a feature to record coverage.
---

# BRD → stories → traceability

`docs/brd/brd.md` is the source of requirements. Acceptance criteria are numbered `<ID>.<n>` (e.g. `CUS-06.6`).
`docs/requirements/traceability.md` maps each requirement to its delivery phase, branch, code and tests.

## When creating or refreshing the matrix
1. Read `docs/brd/brd.md` in full. Collect every ID: CUS-01..08, INB-01..04, OUT-01..06, BUS-01..03, BR-01..10, XC-01..09, NFR-*.
2. For each functional ID, write one row:
   `| ID | Title | Phase (BRD §15) | Branch | Backend | Frontend | Tests | Status |`
   - Phase: Foundation=1, Customer=2, Inbound=3, Outbound=4, Business=5, Production=6. BR/XC/NFR rows use the phase that first implements them.
   - Status is one of `Planned` · `In progress` · `Done` · `Deferred (reason)`.
3. Under the table, list **open decisions** (BRD §14) and the default we adopted.

## When planning a story
For the requested ID, write a short story into the PR description or the task plan:
```
Story <ID>: As a <role> I want <capability> so that <benefit>.
Acceptance: <ID>.1 … <ID>.n (copied verbatim, then add the API + UI behaviour that satisfies each)
Permissions: <permission strings from BRD §8>
Data: <tables touched / new migration>
API: <METHOD /api/v1/...>
UI: <route, components, states>
Tests: <unit / integration incl. forbidden role / e2e>
```

## When a story is finished
Update its row: real file paths (`backend/src/main/java/...`, `frontend/src/features/...`), test class names, and `Done`.
A row is only `Done` when every acceptance criterion has at least one test or an explicit manual-verification note.
Never mark a row Done without checking that the referenced files and tests exist (use Glob).
