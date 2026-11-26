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
then become `Designed`. Backend Code/Tests filled on `slice/02-customer-be` (B1–B21, errata E1–E6). Section refs are to `02-customer/backend.md`; `FE:` refs are to
`02-customer/frontend.md` (**Approved** 2026-11-25); FE code/tests fill in on `slice/02-customer-fe`.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| CUS-01 | Dashboard: identity, plan, promotions, recent requests/payments; own data only | §1, §4, R1, R2 (no aggregate endpoint; cards use E1, E5, E8, E11, E13, E14) · FE: frontend.md §3 Dashboard | `customer/{CustomerProfileController,SubscriptionController,PromotionController}`, `servicecase/CustomerCaseController`, `payment/CustomerPaymentController`, `common/security/CustomerScope`, `identity/CustomerScopeImpl`, `customer/AccountLookup`; e2e seed `db/seed/R__02_customer_e2e.sql` (E5) | CustomerScopeIT, CustomerProfileIT, SubscriptionPlanIT, PromotionIT, CustomerCaseIT, PaymentHistoryIT, CustomerIdorIT, E2eProfileIT | Built · FE Designed |
| CUS-02 | Profile: alias edit, verified contact change, read-only billing address, audit | R2, R3, §3.2, §7, E1–E4 · FE: frontend.md §3 Profile, §6 | `customer/{CustomerRepository,CustomerService,CustomerProfileController,ContactVerificationRepository,ContactVerificationService,ContactVerificationController,OtpProperties,VerificationCodeSender,MockVerificationCodeSender}` | CustomerProfileIT, ContactVerificationIT, CustomerPortMocksTest | Built · FE Designed |
| CUS-03 | Plan summary, benefits, eligible upgrades, confirmed plan change | R4–R6, §8 `PlanProvisioning`, E5–E7 (charge rule = OQ 10, user decision) · FE: frontend.md §3 My plan | `catalog/{PlanRepository,CatalogService}`, `customer/{SubscriptionRepository,SubscriptionService,PlanQuoteService,PlanChangeRepository,PlanChangeService,PlanChangeApplyJob,SubscriptionController,PlanProvisioning,MockPlanProvisioning}` | SubscriptionPlanIT, CatalogServiceIT, CustomerPortMocksTest | Built · FE Designed |
| CUS-04 | Eligible active promotions, confirmed claim | R7, R8, E8, E9 · FE: frontend.md §3 Promotions, §6 | `catalog/PromotionRepository`, `customer/{PromotionClaimRepository,PromotionEligibilityService,PromotionController}` (E6 `claimedSubscriptionIds`) | PromotionIT, CatalogServiceIT | Built · FE Designed |
| CUS-05 | Request list: 3-month default, date filter, paging, reference search | R9, E11 · FE: frontend.md §3 Service requests, §4 | `servicecase/{CaseRepository,CustomerCaseService,CustomerCaseController}` | CustomerCaseIT | Built · FE Designed |
| CUS-06 | Create request: categories, auto contact, unique ref, no duplicates | R10, E10, E12 — **partial: CUS-06.3 attachment deferred to 03/04 (OQ 4)** · FE: frontend.md §3 Create request, §6 (no attachment field) | `servicecase/{CategoryRepository,SlaPolicyRepository,CaseRepository,CaseActivityRepository,CustomerCaseService,CaseCategoryController,CustomerCaseController}` | CaseCategoryIT, CustomerCaseIT | Built (partial: CUS-06.3 deferred) · FE Designed |
| CUS-06.3 | Optional attachment | not designed (OQ 4) | | | Deferred |
| CUS-07 | Payment history: 3-month default, paging, receipt, reconciled | R11, R14, R15, §3.1, E13, E14, E17 · FE: frontend.md §3 Payment history, Receipt | `payment/{InvoiceRepository,PaymentRepository,PaymentQueryService,PaymentTransitionService,PaymentReconciliationJob,CustomerPaymentController}` | PaymentHistoryIT, PaymentTransitionIT, PaymentReconciliationJobIT | Built · FE Designed |
| CUS-08 | Make payment: invoice shown, hosted gateway, server-side verification, all outcomes | R12–R14, §3.1, §8, E15, E16, E18 · FE: frontend.md §2 (return path, backend E4), §3 Pay bill, Payment status, Receipt, §10 D1 | `payment/{PaymentService,PaymentGateway,MockPaymentGateway,PaymentGatewayProperties,MockCheckoutController,PaymentWebhookController,GatewayEventRepository,WebhookRateLimiter,PaymentTransitionService}` | PaymentInitiateIT, PaymentWebhookIT, MockCheckoutIT, MockPaymentGatewayTest, PaymentReconciliationJobIT | Built · FE Designed |
| BR-01 | Unique, non-reusable reference IDs (SR, PAY) | R10, §3.1 (01 §6.2 generator) | `servicecase/CustomerCaseService`, `payment/PaymentService` (via `common/reference/ReferenceGenerator`) | CustomerCaseIT, PaymentInitiateIT | Built |
| BR-02 | Case has account association and channel | §2 (cases), R10 | `V5__servicecase.sql`, `servicecase/CustomerCaseService` | SchemaV3toV6IT, CustomerCaseIT | Built |
| BR-04 | Customer vs internal notes separate | §2 (`case_activity.visibility`) | `V5__servicecase.sql` (`case_activity.visibility`, append-only triggers) | SchemaV3toV6IT | Built |
| BR-05/06 | 3-month default views; bounded filters, server paging | R9, R11 · FE: frontend.md §3 lists, §4 `DateRangeFilter` | `servicecase/CustomerCaseService`, `payment/PaymentQueryService` | CustomerCaseIT, PaymentHistoryIT | Built · FE Designed |
| BR-07 | SLA configurable by category | §2 (`sla_policies`), R10 | `V5__servicecase.sql` (`sla_policies`), `servicecase/SlaPolicyRepository` | SchemaV3toV6IT, CaseCategoryIT, CustomerCaseIT | Built |
| BR-08 | Payment/provisioning confirmed by provider | R6, R13, §3.1, §8 | `customer/PlanChangeApplyJob`, `payment/{PaymentTransitionService,PaymentWebhookController,PaymentReconciliationJob}` | SubscriptionPlanIT, PaymentTransitionIT, PaymentWebhookIT, PaymentReconciliationJobIT | Built |
| BR-10 | Server-side authorization, own-account scope | §4, R1, §9 `CustomerPortalAuthzIT`, `CustomerIdorIT` · FE: frontend.md §2 (child routes inherit the customer gate) | `@PreAuthorize` + `@customerScope.isCustomer()` on E1–E17, `common/security/CustomerScope`, `identity/CustomerScopeImpl`, `customer/AccountLookup` | CustomerPortalAuthzIT (E1–E17 × anonymous, 6 staff users, unlinked customer), CustomerIdorIT, CustomerScopeIT | Built · FE Designed |
| XC-04 | Server-side pagination, filters kept across pages | R9, R11, E11, E14 · FE: frontend.md §3 lists, §4 `useListParams` (URL params) | `servicecase/CustomerCaseService`, `payment/PaymentQueryService` (sort whitelist, paging) | CustomerCaseIT, PaymentHistoryIT | Built · FE Designed |
| XC-05 | Validated date filters, documented timezone | R9, R11 (00-overview §3) · FE: frontend.md §4 `dateWindow`, §6 date range | `servicecase/CustomerCaseService`, `payment/PaymentQueryService` (Asia/Kolkata windows) | CustomerCaseIT, PaymentHistoryIT | Built · FE Designed |
| XC-06 | High-risk actions confirmed and audited | R6, R8, §7 · FE: frontend.md §3 plan change/claim confirm dialogs | `audit/AuditService` from `customer/{CustomerService,ContactVerificationService,PlanChangeService,PlanChangeApplyJob,PromotionEligibilityService}`, `servicecase/CustomerCaseService`, `payment/{PaymentService,PaymentTransitionService,PaymentWebhookController}` | CustomerProfileIT, ContactVerificationIT, SubscriptionPlanIT, PromotionIT, CustomerCaseIT, PaymentWebhookIT, CustomerPortalAuthzIT (ACCESS_DENIED) | Built · FE Designed |
| XC-07 | Success only when the backend confirms | R6, R13, §3.1 · FE: frontend.md §3 Payment status, §5 (no optimistic updates) | `customer/PlanChangeApplyJob`, `payment/PaymentTransitionService` | SubscriptionPlanIT, PaymentWebhookIT, PaymentReconciliationJobIT | Built · FE Designed |
| NFR-AVAIL | Degrade gracefully when payment/provisioning is down | §3.1, §8 | `payment/{PaymentService,PaymentReconciliationJob}`, `customer/PlanChangeApplyJob` | PaymentInitiateIT, PaymentReconciliationJobIT, SubscriptionPlanIT | Built |
| NFR-INTEG | Ownership and payment results validated server-side | R1, R13, §3.1, `CustomerIdorIT` | `customer/AccountLookup`, `payment/PaymentTransitionService`, V6 guarded keys | CustomerIdorIT, PaymentTransitionIT, PaymentWebhookIT, SchemaV3toV6IT | Built |
| NFR-PRIV | Masked PII, no card data | §2, §7 · FE: frontend.md §3 Profile, §5 (nothing in localStorage) | audit details masking in `customer/{CustomerService,ContactVerificationService}`; no card data stored (V6) | ContactVerificationIT, CustomerProfileIT | Built · FE Designed |
| NFR-REL | Idempotent create/payment; path-aware request hash | §5, R10, R12, §3.1 · FE: frontend.md §4 `useIdempotencyKey` (path-scoped; E15 key in sessionStorage), `useSubmitGuard` | `common/idempotency/IdempotencyService` (path-aware hash) in E7, E9, E12, E15 | SubscriptionPlanIT, PromotionIT, CustomerCaseIT, PaymentInitiateIT | Built · FE Designed |
| NFR-SEC | OTP brute-force limits, signed webhook, rate limit | R3, §3.2, §4, §8 · FE: frontend.md §3 Profile (OTP), §6, OQ 12 | `customer/ContactVerificationService`, `payment/{PaymentWebhookController,WebhookRateLimiter}`, `common/ProfileGuard` (prod refuses mock, fixed OTP, blank secrets, e2e, Flyway clean) | ContactVerificationIT, PaymentWebhookIT, MockCheckoutIT, ProfileGuardIT, ProfileGuardTest | Built · FE Designed |
| NFR-MAINT | One-way module dependencies (customer → catalog; servicecase, payment → customer) | §1, §9 | package-by-feature `catalog`, `customer`, `servicecase`, `payment` | ArchitectureTest (catalog ↛ customer/servicecase/payment, customer ↛ servicecase/payment, no cycles, module repositories) | Built |
| XC-02 | Restricted routes denied; APIs enforce authz | FE: frontend.md §2 (nested guarded child routes), §8 `RequirePermission.test`, e2e `forbidden.spec` | | | FE Designed |
| XC-03 | Loading, success, empty, validation, failure states | FE: frontend.md §3 (every page, 403 rule), §6 (common error row) | | | FE Designed |
| XC-08 | Keyboard-operable; desktop/tablet/mobile | FE: frontend.md §3, §4 `DataTable`, §8 e2e at 3 viewports | | | FE Designed |
| NFR-A11Y | Accessible controls and validation | FE: frontend.md §3 (dialogs, disclosures, live regions), §6 | | | FE Designed |
| NFR-RESP | Responsive customer experience | FE: frontend.md §3 Dashboard grid, §4 `DataTable` cards < md | | | FE Designed |
| NFR-PERF | Lazy-load modules, server paging | FE: frontend.md §2 (`route.lazy`), §3 independent dashboard cards | | | FE Designed |

## Slice 03 — Inbound assistant workspace

Design: `03-inbound/backend.md` rev 2 (+ `openapi.yaml`), status **Approved** (2026-11-26, review D1–D22 addressed, branch `design/03-inbound`). Rows stay `Proposed`
until the user approves, then become `Designed`. Section refs are to `03-inbound/backend.md`; endpoints I1–I15.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| INB-01.1 | Search by mobile, scoped to authorized records | R1, R2 (lookup token), R5, I1, §4 | | | Designed |
| INB-01.2 | Verification before protected actions | R3–R5 (race-safe limits, central interceptor), I2–I4 | | | Designed |
| INB-01.3 | Identity, account status, plan, contacts, case/payment context | R7, I5–I9, I15 | | | Designed |
| INB-01.4 | Log customer-record access | R1, R6, §7 `CUSTOMER_LOOKUP`, `CUSTOMER_RECORD_ACCESSED` | | | Designed |
| INB-02.1 | Case list: ref, category, severity, status, owner, dates | R8, I9 | | | Designed |
| INB-02.2 | Description + activity timeline | R9, I10 | | | Designed |
| INB-02.3 | Related/duplicate cases | R10, I10 (OQ 11) | | | Designed |
| INB-02.4 | Internal notes never shown to customers | R9, BR-04 | | | Designed |
| INB-03.1 | Mobile/account populated from the selected customer | R12 | | | Designed |
| INB-03.2 | Internal categories permission-controlled | R11, R12, I11, V8 categories | | | Designed |
| INB-03.3 | Summary, statement, agent notes, severity, attachments | R12 (attachments deferred, OQ 5) | | | Designed (partial) |
| INB-03.4 | Created-by agent and channel inbound | R12, §7 `CASE_CREATED` | | | Designed |
| INB-03.5 | Reference number returned | R12, I12 | | | Designed |
| INB-04.1 | Check suspension reason and eligibility first | R13, I13, R14 | | | Designed |
| INB-04.2 | Prohibited reasons never overridable | R13, §3.1 early end, §2 CHECK on `reactivation_policies` | | | Designed |
| INB-04.3 | Reason, agent, time, approval evidence | R14, §2 `reactivation_requests`, §7 (OQ 7) | | | Designed |
| INB-04.4 | Provisioning via backend integration | R15, §3.1, §8 `ProvisioningClient` | | | Designed |
| INB-04.5 | Backend-enforced expiry ≤ 24 h | §3.1 (unlimited suspend retries with backoff, late restore → EXPIRING, overdue alert), §2 24 h CHECK | | | Designed |
| INB-04.6 | Prevent repeated extensions / abuse | R13, R14 (agent lock, OQ 13) | | | Designed |
| INB-04.7 | Notify customer + immutable audit | R16 (claimed outbox), §7, §8 `NotificationSender` | | | Designed |
| BR-01/02/04/07 | Reference, channel, separate notes, SLA by category | R12, V8 SLA rows | | | Designed |
| BR-03 | Reactivation transitions record actor, time, old/new | §3.1, §7 (from/to, source) |  |  | Designed |
| §14 #12 | In-app + email notifications | R16, §8 — mock SMS/EMAIL only (OQ 9) | | | Designed (deviation) |
| BR-08 | Provisioning confirmed by provider | §3.1 | | | Designed |
| BR-10 / XC-02 | Server-side authorization | §4, §9 `InboundAuthzIT` | | | Designed |
| XC-06 / XC-07 | Privileged actions audited; success only when confirmed | §3.1, §7 | | | Designed |
| NFR-AVAIL | Degrade when provisioning/notification is down | §3.1, §8 | | | Designed |
| NFR-PRIV | Masked search (POST body), minimised view, masked outbox | R1, R7, R16, §7 | | | Designed |
| NFR-REL | Idempotent case create and reactivation (replay re-checks verification) | §5 I12, I14, R5 | | | Designed |
| NFR-SEC | Race-safe OTP/KBA limits, prod guards for mocks | R3, R4, §6 | | | Designed |
