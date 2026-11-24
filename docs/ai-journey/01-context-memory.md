# 01 — Context & project memory (`CLAUDE.md`, ADRs, BRD → stories)

## Problem observed (before)
We gave Claude Code a plain prompt in an **empty folder with no project memory**:

> "Build the backend for a telecom customer support application using Spring Boot and MySQL. Implement: (1) customer profile view and update API, (2) service request creation API for customers and support agents, (3) support agents claiming a service request."

The output was well-engineered generic code (an atomic claim `UPDATE`, ProblemDetail errors, an ownership check). It still **contradicted almost every project decision**, because Claude could not know them:

| Check (scan of 32 generated files) | Result | Project expectation |
|---|---|---|
| `jakarta.persistence` imports | **45** | 0 (ADR 0002: JDBC only) |
| `JpaRepository` usages / `@Query` | **8 / 1** | 0 |
| `spring-boot-starter-data-jpa` | present | absent |
| Spring Boot / Java | 3.3.5 / 17 | 3.5 / 21 |
| Auth | HTTP Basic | Server session + CSRF (ADR 0003) |
| Roles | `CUSTOMER`, `AGENT` | 4 BRD roles + 12 permission strings (BRD §8) |
| `hasAuthority('case.create')`-style checks | **0** | every endpoint |
| Agents can edit any customer's contact fields | yes | **No** (BRD §8: only the customer edits) |
| Idempotency on create (CUS-06.6) | none | `Idempotency-Key` |
| 3-month default window (CUS-05.1) | none | yes |
| Package layout | by layer (`web/`, `service/`, `repository/`) | by feature |
| Tests | none | unit + Testcontainers + forbidden-role |
| BRD traceability | none | every change maps to a BRD ID |

Root cause: **the model had no access to the BRD, our architecture decisions or the product owner's constraints.**

## Capability added
| Artifact | Purpose |
|---|---|
| `CLAUDE.md` (root) | Stack, non-negotiable rules (JDBC-only, Flyway, BR-10 server-side auth, idempotency, 3-month window, UTC/IST), commands, definition of done |
| `backend/CLAUDE.md`, `frontend/CLAUDE.md` | Scoped rules that load when Claude works in that folder: JDBC conventions, package layout, error model; BRD §10 frontend stack |
| `docs/adr/0001..0003` | Durable *why* for monolith, Spring JDBC without ORM, session auth + RBAC |
| `docs/brd/brd.md` | BRD converted from `.docx` with stable IDs per acceptance criterion (e.g. `CUS-06.6`) |
| `.claude/skills/brd-to-stories` | Turns BRD IDs into stories and maintains `docs/requirements/traceability.md` |

## Result (after)
With this memory loaded, the skeleton came out right the first time:
- `backend/pom.xml`: Boot **3.5.16**, Java **21**, `spring-boot-starter-jdbc`, Flyway (pinned 11.20 for MySQL 8.4), **no JPA**. A `frontend` Maven profile packages the SPA into the single JAR.
- `V1__baseline.sql` (reference sequences for BR-01, ShedLock), `SpaForwardController` for deep links.
- `docker-compose.yml`: MySQL 8.4, Mailpit and a Linux `web` toolchain container.
- React + TS + Vite + Tailwind v4 skeleton, with design tokens matching the BRD mockups and a health widget covering loading, success and error states.
- `traceability.md`: 21 functional IDs + 24 rule/NFR rows, each assigned a phase and branch.

**Verification**
- `backend/mvnw verify` → `ApplicationSmokeIT` 2/2 passed (Testcontainers MySQL 8.4, Flyway V1 applied, `/actuator/health` UP).
- Frontend (Linux container): `typecheck` ✔, `oxlint` 0 warnings, Vitest 2/2 passed.

## Side finding solved during this step
This workstation's **Windows Application Control policy blocks unsigned native npm binaries** (`rolldown`, used by Vite 8, and `oxlint`). Claude diagnosed it by loading the `.node` file directly ("An Application Control policy has blocked this file"). The WASM fallback isn't published for these versions, so the frontend toolchain now runs in a `node:24` Linux container. That is the same environment CI and the production image use, and it is documented in `CLAUDE.md` so future sessions don't retry the broken path.

## BRD IDs delivered
Foundations for NFR-MAINT (feature structure, design tokens), BR-01 (sequence table) and the traceability baseline.
