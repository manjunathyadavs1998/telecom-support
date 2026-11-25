# Traceability — BRD ID → design → code → tests

Updated with every design and build branch. Columns stay empty until the branch that fills them is merged.

Status: `Designed` (design approved) → `Built` (code + tests merged) → `Verified` (QA evidence).

## Slice 01 — Foundation

Code paths are relative to `backend/src/main/java/com/telecom/support/` unless they start with `db/` (resources). Tests: `backend/src/test/java`.
`FE:` code paths are relative to `frontend/src/` (`scripts/` and `tsconfig*` to `frontend/`); FE unit tests sit next to the code (`*.test.ts(x)`), e2e specs in `frontend/e2e/`.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| §2 | Four roles, role-based experiences | 01-foundation/backend.md §3 | `db/migration/V2__identity_rbac_audit.sql`, `identity/UserRepository` | PermissionMatrixIT, SchemaIT | Built |
| §8 | Permission matrix | 01-foundation/backend.md §3 | `V2__…sql` (matrix seed), `identity/Permissions` | PermissionMatrixIT (25 probes × 7 users) | Built |
| BR-10 | UI controls backed by server-side authorization | 01-foundation/backend.md §4–5; frontend.md §2, §4 | `identity/SecurityConfig` (`@EnableMethodSecurity`), `@PreAuthorize` probes; FE: `permissions/{Can,RequirePermission}.tsx`, `permissions/usePermissions.ts` | PermissionMatrixIT, AuthIT; FE: RequirePermission.test; e2e forbidden.spec | Built (BE + FE) |
| XC-01 | Each role sees only its navigation | 01-foundation/frontend.md §2, §10 (rev 2) | FE: `permissions/navConfig.ts`, `components/layout/{AppShell,Sidebar,NavList}.tsx`, `app/routes.tsx` | FE: navConfig.test, navFilter.test; e2e auth-nav.spec | Built |
| XC-03 | Loading, success, empty, validation, failure states | 01-foundation/frontend.md §3, §4, §6 | FE: `components/states/*`, `features/authentication/LoginForm.tsx`, `pages/RouteErrorPage.tsx` | FE: states.test, LoginForm.test | Built |
| XC-07 | Payment, profile update, case transition, reactivation succeed only when the backend confirms | 01-foundation/frontend.md §5 (no optimistic mutations) | FE: `app/queryClient.ts` (no optimistic mutations, 403 toast), `features/authentication/useLogout.ts` | FE: apiForbidden.test, logout.test | Built |
| XC-08 | Keyboard-operable; tested at desktop/tablet/mobile | 01-foundation/frontend.md §8, §10 | FE: `components/layout/{AppShell,Header,Sidebar,NavList,PageContainer}.tsx`, `components/ui/sheet.tsx`, `stores/uiStore.ts` | FE: AppShell.test; e2e responsive-a11y.spec (3 viewports) | Built |
| NFR-A11Y | Semantic controls, keyboard, visible focus, contrast, accessible validation | 01-foundation/frontend.md §3, §6, §8 | FE: `components/layout/*`, `features/authentication/{LoginForm,LoginPage}.tsx`, `components/states/*` | FE: AppShell.test (vitest-axe), LoginForm.test; e2e responsive-a11y.spec (axe, keyboard-only login) | Built |
| NFR-RESP | Desktop-first agent workspaces, responsive tablet/mobile | 01-foundation/frontend.md §8 | FE: `components/layout/{AppShell,Header,Sidebar}.tsx`, `stores/uiStore.ts` (rail/drawer) | FE: AppShell.test; e2e responsive-a11y.spec (1280×800, 768×1024, 375×812) | Built |
| XC-02 | Restricted routes denied; APIs enforce authz | 01-foundation/backend.md §4; frontend.md §2, §3, §7, §10; backend.md §12 E2.1 | `identity/SecurityConfig`, `common/error/ProblemDetailFactory`, `common/web/SpaForwardController`; FE: `permissions/RequirePermission.tsx`, `pages/{ForbiddenPage,NotFoundPage}.tsx`, `services/api/client.ts` (401 handler), `features/authentication/{meQuery,useLogout}.ts` | PermissionMatrixIT, AuthContractIT, SpaForwardIT; FE: RequirePermission.test, session.test, apiForbidden.test; e2e forbidden.spec, session.spec | Built (BE + FE) |
| BR-03 | Changes record actor, time, old/new, reason | 00-overview §6; 01-foundation/backend.md §7 | `audit/AuditService`, `audit/AuditRepository`, V2 triggers | AuditTrailIT | Built |
| XC-06 | High-risk actions produce audit events | 00-overview §6 | `audit/AuditService`, `audit/DeniedAccessRecorder` | AuditTrailIT | Built |
| BR-01 | Unique, non-reusable reference IDs | 00-overview §8 | `common/reference/ReferenceGenerator` | ReferenceGeneratorIT | Built |
| NFR-SEC | Secure auth, sessions, least privilege | 01-foundation/backend.md §4; ADR 0003; frontend.md §7, §7.1; backend.md §12 E2.3–E2.5 | `identity/AuthService`, `LoginRateLimiter`, `LoginLimitProperties`, `SessionPolicyFilter`, `common/web/TrustedProxyFilter`, `common/ProfileGuard`, `application-prod.yml`; FE: `services/api/{client,csrf}.ts`, `features/authentication/{returnTo,useLogout}.ts`, `LoginForm` | AuthIT, AuthContractIT, AuthServiceTest, LoginRateLimiterTest, ClientIpIT, ProdCookieIT, ProfileGuardIT; FE: returnTo.test, apiClient.test, LoginForm.test, logout.test; e2e session.spec | Built (BE + FE) |
| NFR-REL | Idempotent create/payment/reactivation | 00-overview §7 | `common/idempotency/IdempotencyService`, `IdempotencyPurgeJob` | IdempotencyServiceIT | Built |
| NFR-OBS | Correlation ids, no secrets in logs | 00-overview §5; frontend.md §4, §7, OQ 8, OQ 10 | `common/web/CorrelationIdFilter`, `common/error/GlobalExceptionHandler`; FE: `services/api/{client,errors}.ts` (X-Correlation-Id, ProblemDetail), `components/states/ErrorState.tsx` | ErrorModelIT, ProblemShapeIT, AuthContractIT (no secrets in logs); FE: apiClient.test, states.test | Built (BE + FE) |
| NFR-MAINT | Feature modules, typed contracts | 00-overview §1, §3; ADR 0001; frontend.md §1, §7, §8, §9; backend.md §12 E2.2, E2.6 | package-by-feature, `common.security` ports; FE: `types/api/*` (generated), `scripts/gen-api.mjs`, feature folders under `src/`, `tsconfig.e2e.json` | ArchitectureTest (no JPA, module repos, no cycles); FE: navConfig.test, gen:api:check, typecheck (incl. e2e) | Built (BE + FE) |

## Slice 02 — Customer portal

Design: `02-customer/backend.md` rev 2 (+ `openapi.yaml`), status **Approved** (2026-11-25). Rows use `Proposed` until the user approves the design,
then become `Designed`. Code/Tests fill in on `slice/02-customer-be`. Section refs are to `02-customer/backend.md`; `FE:` refs are to
`02-customer/frontend.md` (**Proposed**, branch `design/02-customer-fe`); FE code/tests fill in on `slice/02-customer-fe`.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| CUS-01 | Dashboard: identity, plan, promotions, recent requests/payments; own data only | §1, §4, R1, R2 (no aggregate endpoint; cards use E1, E5, E8, E11, E13, E14) · FE: frontend.md §3 Dashboard | | | Designed · FE Proposed |
| CUS-02 | Profile: alias edit, verified contact change, read-only billing address, audit | R2, R3, §3.2, §7, E1–E4 · FE: frontend.md §3 Profile, §6 | | | Designed · FE Proposed |
| CUS-03 | Plan summary, benefits, eligible upgrades, confirmed plan change | R4–R6, §8 `PlanProvisioning`, E5–E7 (charge rule = OQ 10, user decision) · FE: frontend.md §3 My plan | | | Designed · FE Proposed |
| CUS-04 | Eligible active promotions, confirmed claim | R7, R8, E8, E9 · FE: frontend.md §3 Promotions, §6 | | | Designed · FE Proposed |
| CUS-05 | Request list: 3-month default, date filter, paging, reference search | R9, E11 · FE: frontend.md §3 Service requests, §4 | | | Designed · FE Proposed |
| CUS-06 | Create request: categories, auto contact, unique ref, no duplicates | R10, E10, E12 — **partial: CUS-06.3 attachment deferred to 03/04 (OQ 4)** · FE: frontend.md §3 Create request, §6 (no attachment field) | | | Designed (partial) · FE Proposed |
| CUS-06.3 | Optional attachment | not designed (OQ 4) | | | Deferred |
| CUS-07 | Payment history: 3-month default, paging, receipt, reconciled | R11, R14, R15, §3.1, E13, E14, E17 · FE: frontend.md §3 Payment history, Receipt | | | Designed · FE Proposed |
| CUS-08 | Make payment: invoice shown, hosted gateway, server-side verification, all outcomes | R12–R14, §3.1, §8, E15, E16, E18 · FE: frontend.md §3 Pay bill, Payment status, Receipt | | | Designed · FE Proposed |
| BR-01 | Unique, non-reusable reference IDs (SR, PAY) | R10, §3.1 (01 §6.2 generator) | | | Designed |
| BR-02 | Case has account association and channel | §2 (cases), R10 | | | Designed |
| BR-04 | Customer vs internal notes separate | §2 (`case_activity.visibility`) | | | Designed |
| BR-05/06 | 3-month default views; bounded filters, server paging | R9, R11 · FE: frontend.md §3 lists, §4 `DateRangeFilter` | | | Designed · FE Proposed |
| BR-07 | SLA configurable by category | §2 (`sla_policies`), R10 | | | Designed |
| BR-08 | Payment/provisioning confirmed by provider | R6, R13, §3.1, §8 | | | Designed |
| BR-10 | Server-side authorization, own-account scope | §4, R1, §9 `CustomerPortalAuthzIT`, `CustomerIdorIT` · FE: frontend.md §2 (child routes inherit the customer gate) | | | Designed · FE Proposed |
| XC-04 | Server-side pagination, filters kept across pages | R9, R11, E11, E14 · FE: frontend.md §3 lists, §4 `useListParams` (URL params) | | | Designed · FE Proposed |
| XC-05 | Validated date filters, documented timezone | R9, R11 (00-overview §3) · FE: frontend.md §4 `dateWindow`, §6 date range | | | Designed · FE Proposed |
| XC-06 | High-risk actions confirmed and audited | R6, R8, §7 · FE: frontend.md §3 plan change/claim confirm dialogs | | | Designed · FE Proposed |
| XC-07 | Success only when the backend confirms | R6, R13, §3.1 · FE: frontend.md §3 Payment status, §5 (no optimistic updates) | | | Designed · FE Proposed |
| NFR-AVAIL | Degrade gracefully when payment/provisioning is down | §3.1, §8 | | | Designed |
| NFR-INTEG | Ownership and payment results validated server-side | R1, R13, §3.1, `CustomerIdorIT` | | | Designed |
| NFR-PRIV | Masked PII, no card data | §2, §7 · FE: frontend.md §3 Profile, §5 (nothing in localStorage) | | | Designed · FE Proposed |
| NFR-REL | Idempotent create/payment; path-aware request hash | §5, R10, R12, §3.1 · FE: frontend.md §4 `useIdempotencyKey` (E7, E9, E12, E15) | | | Designed · FE Proposed |
| NFR-SEC | OTP brute-force limits, signed webhook, rate limit | R3, §3.2, §4, §8 · FE: frontend.md §3 Profile (OTP), §6, OQ 12 | | | Designed · FE Proposed |
| XC-03 | Loading, success, empty, validation, failure states | FE: frontend.md §3 (every page), §6 | | | FE Proposed |
| XC-08 | Keyboard-operable; desktop/tablet/mobile | FE: frontend.md §3, §4 `DataTable`, §8 e2e at 3 viewports | | | FE Proposed |
| NFR-A11Y | Accessible controls and validation | FE: frontend.md §3 (dialogs, disclosures, live regions), §6 | | | FE Proposed |
| NFR-RESP | Responsive customer experience | FE: frontend.md §3 Dashboard grid, §4 `DataTable` cards < md | | | FE Proposed |
| NFR-PERF | Lazy-load modules, server paging | FE: frontend.md §2 (`route.lazy`), §3 independent dashboard cards | | | FE Proposed |
