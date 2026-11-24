# 02 — Project skills (repeatable recipes)

## Problem observed (before)
With `CLAUDE.md` in place (branch 01), we ran Claude headless on a fresh copy of `main`:

> "Implement two read-only features end to end: (a) plan catalogue with benefits, (b) active promotions. Include the migration, backend endpoint(s) and a simple React page for each."

The rules from `CLAUDE.md` held, so the code used no JPA, Flyway and feature packages. But **two features written in the same session already disagreed with each other**, and both invented shared infrastructure on the spot:

| Symptom | Evidence in generated code |
|---|---|
| Repository shape differs between features | `PlanRepository` returns row records that the service maps. `PromotionRepository.findActiveAt()` returns the **API DTO `PromotionResponse` straight from SQL** (layer leak). |
| Time handling differs | One binds `Instant`→`Timestamp`, the other binds `LocalDateTime.ofInstant(now, UTC)` and reads `rs.getObject(.., LocalDateTime.class)`. |
| Endpoints shipped **unauthenticated** | Both controllers carry `TODO(BR-10): add @PreAuthorize once Spring Security lands`. |
| Contract types hand-written | `src/services/api-types.ts` "temporary, copies the Java records" (rule says *generated*). |
| Infra invented per feature | New `http.ts`, `components/feedback`, `TimeConfig`, `test/render.tsx`, each an unreviewed one-off design. |
| Test layout drift | `catalogue/CatalogueIT` alongside `plan/PlanServiceTest` and `promotion/PromotionServiceTest`. |

Root cause: `CLAUDE.md` says *what* the rules are, not *how* to build a feature step by step, and the shared building blocks every feature needs (auth, errors, http client, UI states) didn't exist yet.

## Capability added: project skills (`.claude/skills/`)
| Skill | Triggers on | Gives Claude |
|---|---|---|
| `spring-jdbc-endpoint` | any `/api/v1` endpoint | The repository → service → controller recipe, `SortWhitelist`, optimistic locking, idempotency, a test checklist (happy / 400 / **403** / 401). Copyable templates: `XxxRepository`, `XxxController`, `XxxControllerIT` |
| `flyway-migration` | any schema/seed change | Naming, index rules, append-only triggers, idempotent repeatable seeds, the MySQL 8.4 `AS new` upsert syntax |
| `rbac-permission` | any authorization rule | The 7 touch points that must stay in sync (migration, Java constant, `@PreAuthorize`, TS constant, nav, route guard, matrix test) |
| `react-feature-module` | any screen | Folder layout, query-hook pattern, the UI kit, the states every page must show, the testing helpers |

Skills load **on demand**: Claude sees each description and pulls the full recipe only when the task matches. That keeps every session's context small.

## Result: Phase 1 Foundation generated through the skills
**Backend**
- `V2__identity_rbac_audit.sql`: teams, users, roles, **25 permissions**, grants per BRD §8, append-only `audit_events` (triggers), `idempotency_keys`, Spring Session tables.
- Seeded demo users for every role. Password `Password@123`.
  - Customers: `+919876543210` and 3 more
  - Inbound: `inbound1`, which has the reactivation permission, and `inbound2`, which doesn't
  - Outbound: `outbound1`–`outbound3`, plus `supervisor1`
  - Business: `business1`, and `auditor1` (PII export)
- Session auth (Spring Session JDBC, HttpOnly `SESSION`), SPA CSRF (`XSRF-TOKEN` → `X-XSRF-TOKEN`), BCrypt, lockout after 5 failures, per-IP/user rate limiting, login audit.
- `GlobalExceptionHandler` → RFC 7807 with `code`, `correlationId`, `errors[]`. `CorrelationIdFilter`. `IdempotencyService` (same transaction, concurrent-safe). `SortWhitelist`. `PageResponse`.
- springdoc OpenAPI with a customizer, so response types generate as non-optional and nullable fields become `T | null`.

**Frontend**
- `services/http.ts` (CSRF, correlation id, ProblemDetail → `ApiError`, idempotency key).
- **Generated** `services/api-types.ts` (`npm run gen:api`).
- UI kit (`Button`, `Input`, `FormField` with ARIA wiring, `Card`, `Badge`, `Alert`, `Skeleton`), plus `LoadingState`, `EmptyState` and `ErrorState`.
- Role-aware `AppShell` (navy sidebar per the BRD mockups, mobile drawer, account menu, skip link).
- `navigation.ts` gated by permission, `RequirePermission` route guards, and a `/forbidden` page.
- Login page (RHF + Zod), with each role landing in its own workspace. Later-phase screens are explicit placeholders.

**Verification**
- `mvnw verify`: **33 integration tests green**, including:
  - `PermissionMatrixIT`: the BRD §8 matrix as 18 parameterised cases, covering positive grants and the risky "No" cells
  - `AuthIT` (7): login, session cookie, logout, 401/403, CSRF
  - `IdempotencyServiceIT` (4): replay, payload mismatch, retry after failure, malformed key
  - `AuditTrailIT` (2): UPDATE/DELETE blocked by trigger, failed login audited with correlation id
- Frontend: typecheck ✔, oxlint 0 warnings, Vitest 8/8 (role nav, forbidden redirect, login validation, CSRF header), `vite build` ✔.
- Live run (dev profile, Docker MySQL), checked with curl:
  - `XSRF-TOKEN` is readable by JS and `SESSION` is HttpOnly
  - a POST without the CSRF header gets 403
  - SPA deep links (`/customer/requests`) are served `index.html`, and unknown `/api/**` returns 401, not HTML

## Problems Claude hit and fixed along the way
- Spring Session hides the servlet session from MockMvc, so the tests now follow the real `SESSION` cookie.
- Spring Security Test swaps the CSRF repository, so header names are asserted against the running app instead.
- `openapi-typescript` needs the TypeScript 5 JS API, but the project uses TS 7. `gen:api` runs it through an isolated `npx` install instead of downgrading.
- MySQL 8.4 deprecates `VALUES(col)` in upserts. The seed and the `flyway-migration` skill were updated.

## BRD IDs delivered
Phase 1 exit criterion ("all roles authenticate and see correct navigation; route/API permission tests pass") is met. Done: BR-10, XC-01, XC-02. Foundations: XC-03, XC-06, NFR-SEC, NFR-REL, NFR-OBS.
