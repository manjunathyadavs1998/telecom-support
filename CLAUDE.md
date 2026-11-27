# Telecom Customer Support — project rules for Claude

Requirements: `docs/brd/brd.md`. Every change traces to a BRD ID (CUS/INB/OUT/BUS-xx, BR-xx, XC-xx, NFR-xx).
Design (the contract): `docs/design/`. Coverage: `docs/requirements/traceability.md`. Decisions: `docs/adr/`.

## Gated workflow (non-negotiable)
1. **No code without an approved design doc.** A design is approved when its header says `Status: Approved`
   and that change is merged to `main`.
2. One BRD slice at a time: backend design → approve → backend build → frontend design → approve → frontend build.
   The frontend design may be written in parallel with the backend build (docs only, own git worktree); it is still
   approved before any frontend code. The reviewer also covers test gaps (no separate test-engineer pass).
3. One branch per step, cut from fresh `main`: `design/NN-*`, `slice/NN-<name>-be`, `slice/NN-<name>-fe`.
4. Build only what the design lists. If the design is wrong or incomplete, stop and update the design (and get it
   re-approved). Don't improvise in code.
5. A person approves every merge. Never run `gh pr merge` without an explicit "merge" from the user.
6. Keep context small: read only the BRD sections and design files for the current task, and summarize long
   tool output instead of pasting it.

## Agents and skills (`.claude/`)
| Need | Use |
|---|---|
| Design a slice layer, plan its tasks, find design gaps | `ba-planner` agent + `design-doc` skill |
| Write and run tests from the design | `test-engineer` agent |
| Independent review before a PR | `reviewer` agent → `docs/slices/<slice>/review.md` |
| Gate check + open PR (merge only on the user's "merge") | `merge-steward` agent |
| Where a slice stands / what to do next | `slice-workflow` skill → `docs/slices/<slice>/status.md` |
| BRD text | `brd-section` skill (never read `brd.md` whole) |
| Is it green? | `run-checks` skill (`scripts/check-{backend,frontend,docs}.sh`) |

## Stack (ADR 0001–0003)
- Modular monolith: one Spring Boot 3.5 JAR (Java 21) serves `/api/v1/**` and the built React SPA.
- MySQL 8 + Flyway. Spring JDBC (`NamedParameterJdbcTemplate`) only.
- React + TypeScript + Vite + Tailwind + TanStack Query/Table + Zustand + RHF/Zod + Recharts.

## Backend rules
1. No JPA/Hibernate/Spring Data. SQL is hand-written with named parameters in text blocks; rows map to `record`s.
   Never concatenate values into SQL. Dynamic filters are built from fixed fragments, and sort keys go through a whitelist.
2. Schema changes only via new `db/migration/V<n>__<desc>.sql`. Never edit a merged migration.
   Dev data goes in `db/seed` (dev profile only).
3. Authorization is server-side (BR-10): every endpoint has `@PreAuthorize` with a BRD §8 permission.
   Services enforce record scope. A customer's account id always comes from the principal, never from the request.
4. Errors are RFC 7807 `ProblemDetail` (`docs/design/00-overview.md#errors`). Every response carries `X-Correlation-Id`.
5. High-risk actions write an append-only `audit_events` row (XC-06).
6. Create/payment/reactivation endpoints honour `Idempotency-Key` (NFR-REL).
7. Lists are server-side paginated with bounded date filters. The default window is 3 months (BR-05/06).
8. Times are stored as UTC `DATETIME(6)` and displayed in `Asia/Kolkata`.
9. Never log secrets, passwords, OTPs or card data. Mask PII where it isn't needed.

## Frontend rules
- Server state lives only in TanStack Query; Zustand holds only session/UI state.
- Every screen has loading, empty, error and validation states (XC-03).
- API types are generated from the OpenAPI contract. Never hand-write DTO types.

## Definition of done (per task)
Code matches the design, tests pass (including a forbidden-role case for every protected endpoint), and the
traceability row is updated.
Temporary (user decision 2026-11-27): tests are deferred — tasks are verified by compile/typecheck/lint only; deferred tests are tracked in docs/testing/test-plan.md.

## Windows host note
Application Control blocks unsigned native npm binaries (`rolldown`, `oxlint`). Run frontend tooling in a Linux
container. Never "fix" this by downgrading tools or deleting lockfiles.

## Git
Conventional commits (`feat(case): …`, `docs(design): …`). PR into `main`.
