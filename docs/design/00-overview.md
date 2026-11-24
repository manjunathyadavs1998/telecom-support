# 00 — System overview and cross-cutting conventions

- Status: **Approved** · Date: 2026-11-24
- Covers: BRD §2, §7, §8, §9, §11, §13, §15 · ADRs 0001–0003

This document fixes the conventions that every slice design reuses. Slice designs reference it instead of repeating it.

## 1. Module map

One Spring Boot application with feature packages under `com.telecom.support`. A module may call another module's
`*Service` (public API) but never its repository. External systems sit behind ports with mock adapters first (ADR 0001).

| Module | Package | Owns (tables) | BRD | Slice |
|---|---|---|---|---|
| Common | `common` | — (errors, paging, correlation id, clock) | XC-03..05, NFR-OBS | 01 |
| Identity & access | `identity` | users, teams, roles, permissions, role_permissions, user_roles, SPRING_SESSION* | §2, §8, BR-10 | 01 |
| Audit | `audit` | audit_events | BR-03, XC-06 | 01 |
| Idempotency | `common.idempotency` | idempotency_keys | NFR-REL | 01 |
| References | `common.reference` | ref_sequences | BR-01 | 01 |
| Customer | `customer` | customers, accounts, subscriptions, contact verification | CUS-01/02, INB-01 | 02, 03 |
| Plans & promotions | `catalog` | plans, plan_benefits, promotions | CUS-03/04 | 02 |
| Cases | `servicecase` | cases, case_activity, categories, sla_policies, bookmarks | CUS-05/06, INB-02/03, OUT-01..06 | 02–04 |
| Payments | `payment` | invoices, payments (`PaymentGateway` port) | CUS-07/08 | 02 |
| Reactivation | `reactivation` | reactivation_requests (`ProvisioningClient` port) | INB-04 | 03 |
| Notifications | `notification` | notifications (`NotificationSender` port) | OUT-06, §14 #12 | 03–04 |
| Analytics & reports | `analytics` | read-only queries, export log via audit | BUS-01..03 | 05 |

## 2. Slice roadmap (BRD §15)

| Slice | Backend design | Frontend design | Exit criteria (BRD §15) |
|---|---|---|---|
| 01 Foundation | `01-foundation/backend.md` | `01-foundation/frontend.md` | All roles authenticate and see the correct navigation; route/API permission tests pass |
| 02 Customer portal | `02-customer/…` | … | Self-service flows work |
| 03 Inbound | `03-inbound/…` | … | Agent workflows and privileged-action audit verified |
| 04 Outbound | `04-outbound/…` | … | Lifecycle, concurrency and assignment rules tested |
| 05 Business | `05-business/…` | … | Metric definitions and drill-down permissions validated |
| 06 Production readiness | `06-prod/…` | — | Security, performance and a11y checks pass |

## 3. API conventions

- Base path `/api/v1`. JSON only. Resource names are plural nouns (`/cases`, `/payments`).
- Contract-first: each slice adds its endpoints to `docs/design/<slice>/openapi.yaml`, and the frontend generates its types from it.
- Paging: `?page=0&size=20&sort=createdAt,desc`. `size` ≤ 100, and sort keys are whitelisted per endpoint. The response is:
  `{ "items": [...], "page": 0, "size": 20, "totalItems": 123, "totalPages": 7 }`.
- Date filters: `from`/`to` as ISO dates (`YYYY-MM-DD`), interpreted in `Asia/Kolkata`, inclusive. The range is at most
  366 days and `from ≤ to`. When omitted, lists default to the last 3 months (BR-05/06, XC-05).
- State-changing requests need the CSRF header `X-XSRF-TOKEN` (ADR 0003).

## 4. Errors {#errors}

Every error is an RFC 7807 `ProblemDetail` (`application/problem+json`):

```json
{ "type": "https://telecom.example/problems/validation", "title": "Validation failed", "status": 400,
  "detail": "One or more fields are invalid.", "instance": "/api/v1/cases",
  "correlationId": "5f0c…", "errors": [ { "field": "email", "message": "Enter a valid email" } ] }
```

| Status | `type` suffix | When |
|---|---|---|
| 400 | `validation` / `bad-request` | Bean validation failed (`errors[]` filled) or a malformed request |
| 401 | `unauthenticated` | No session, or bad credentials |
| 403 | `forbidden` | Missing permission or out-of-scope record (also CSRF failure) |
| 404 | `not-found` | Unknown id **or** a record outside the caller's scope when existence must not leak |
| 409 | `conflict` | Concurrency or state conflict (e.g. the case was already claimed, the idempotency key is in progress) |
| 422 | `business-rule` | A valid request rejected by a business rule (e.g. reactivation not eligible) |
| 429 | `too-many-requests` | Rate limit hit |
| 503 | `dependency-unavailable` | A mock or real integration is down (NFR-AVAIL) |

`detail` is user-safe text. Stack traces, SQL and internal ids are never returned.

## 5. Observability

- `X-Correlation-Id`: an incoming value is accepted if it matches `[A-Za-z0-9-]{1,64}`; otherwise a UUID is generated.
  The id goes into the logging MDC, every response header, every `ProblemDetail` and every `audit_events` row (NFR-OBS).
- Logs never contain passwords, session ids, CSRF tokens, OTPs or full card data.

## 6. Audit (BR-03, XC-06)

- The `audit_events` table is append-only; DB triggers reject UPDATE and DELETE.
- Action names are `UPPER_SNAKE` and stable once released (they are exported): `LOGIN_SUCCESS`, `CASE_ASSIGNED`, ….
- Each row records: actor (id + username snapshot), action, entity type/id, outcome (`SUCCESS|FAILURE|DENIED`),
  correlation id, client IP and a JSON `details` (old/new values, reason). `details` never holds secrets.
- A success audit row is written in the same transaction as the change. Failure or denied rows are written in a separate
  transaction so they survive the rollback.

## 7. Idempotency (NFR-REL)

- Endpoints marked *idempotent* in a slice design require the `Idempotency-Key` header (1–100 chars).
- Key scope is (user, operation, key). The request body hash is stored with it:
  - same key + same body + completed → replay the stored status and body;
  - same key + different body → `422`;
  - same key while the first request is still running → `409`.
- Keys are kept for 24 h and then purged by a scheduled job.

## 8. Reference IDs (BR-01)

Human-readable, unique and never reused: `<PREFIX>-<yyyyMMdd>-<6-digit seq>` (e.g. `SR-20261124-000042`). The sequence
comes from `ref_sequences` (one row per prefix and day, incremented under a row lock). Prefixes: `SR` service request,
`PAY` payment, `REA` reactivation.

## 9. Time

Stored as UTC `DATETIME(6)`, serialized as ISO-8601 with `Z`. The display zone `Asia/Kolkata` is configured as
`app.display-time-zone` (XC-05). Code reads time only through an injectable `Clock` so tests are deterministic.
