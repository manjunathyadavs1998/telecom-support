# Slice 02-customer — status

| Gate | Backend | Frontend |
|---|---|---|
| Design | ☑ `docs/design/02-customer/backend.md` + `openapi.yaml` — **Approved** rev 2 (review D1–D23 addressed) | ☑ `docs/design/02-customer/frontend.md` — **Approved** rev 2 (review F1–F24 addressed; needs backend errata D1–D3) (branch `design/02-customer-fe`) |
| Approved by user | ☑ 2026-11-25 (also 00-overview §1 rev 3; OQ defaults kept) | ☑ 2026-11-25 (OQ defaults kept; backend errata E4–E6) |
| Tasks planned | ☑ B1–B21 confirmed 2026-11-25 | ☑ F1–F22 confirmed 2026-11-26 |
| Built | ☑ branch `slice/02-customer-be` (B1–B21, errata E1–E6) | ☑ branch `slice/02-customer-fe` (F1–F20; F21/F22 e2e deferred by user 2026-11-27) |
| Checks green | ☑ 419 tests, 0 failures at `a71e2ca` (docs-only merge of `origin/main` since) | ☑ ci, typecheck, lint (0 warnings), 568 unit tests, gen:api:check at `e62c65d` (browser e2e deferred) |
| Review clean (no open High) | ☑ `review.md` parts 1–2: High 0, every finding has a disposition (design review: `review-backend-design.md`) | ☑ `review-frontend-code.md`: High 0, Medium 0 open (FC1, FC2 fixed); every finding has a disposition |
| PR | ☑ #10 | ☑ #12 |
| Merged (by user) | ☑ #10 | ☐ |

## Tasks — backend (refs are `backend.md` sections / rules / endpoints)

Paths: main code under `backend/src/main/java/com/telecom/support/`, migrations/seed under `backend/src/main/resources/db/`, tests under
`backend/src/test/…`. Every task that adds an endpoint also adds its anonymous (401) and forbidden-role (403) case in its IT.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| B1 | Catalog + customer schema | `V3__catalog.sql`, `V4__customer.sql`, test-data `R__test_users_02_customer.sql` (plans, customers, accounts, subs, `users.customer_id`), `SchemaV3toV6IT` | §2 | CUS-01.3, CUS-03, §14 #1 | `SchemaV3toV6IT` V3/V4 cases pass (UKs, FK, linked users) | ☑ |
| B2 | Case + payment schema, reference data, dev seed | `V5__servicecase.sql`, `V6__payment.sql`, test-data `R__test_users_02_customer.sql`, `db/seed/R__02_customer.sql`, `SchemaV3toV6IT` | §2 | BR-04, BR-07, §12, §14 #6 | `SchemaV3toV6IT` passes (triggers, open-payment UK, money CHECKs, every leaf has an SLA row); dev app starts | ☑ |
| B3 | Customer scope + account lookup | `common/security/CustomerScope.java`, `identity/CustomerScopeImpl.java`, `customer/AccountRepository.java`, `customer/AccountLookup.java`, `CustomerScopeIT` | §4, R1 | CUS-01.3, BR-10 | `CustomerScopeIT` passes | ☑ |
| B4 | Profile E1, E2 | `customer/CustomerRepository.java`, `customer/CustomerService.java`, `customer/CustomerProfileController.java`, `CustomerProfileIT` | R2, R16, §7 | CUS-01.1, CUS-02.1/02.3–02.5 | `CustomerProfileIT` passes (INBOUND_AGENT 403, null vs missing alias, stale 409, CLOSED 422) | ☑ |
| B5 | Customer ports + mocks | `customer/VerificationCodeSender.java`, `customer/MockVerificationCodeSender.java`, `customer/PlanProvisioning.java`, `customer/MockPlanProvisioning.java`, `CustomerPortMocksTest` | §8 | CUS-02.2, BR-08 | `CustomerPortMocksTest` passes | ☑ |
| B6 | Contact verification E3, E4 | `customer/ContactVerificationRepository.java`, `customer/ContactVerificationService.java`, `customer/ContactVerificationController.java`, `customer/OtpProperties.java`, `ContactVerificationIT` | R3, §3.2 | CUS-02.2, NFR-SEC | `ContactVerificationIT` passes (committed attempts, parallel wrong/right confirms, lock, 429, code not logged) | ☑ |
| B7 | Catalog read API (no customer dependency) | `catalog/PlanRepository.java`, `catalog/PromotionRepository.java`, `catalog/CatalogService.java`, `CatalogServiceIT` | §1 deps, §6 | CUS-03.2, CUS-04.1 | `CatalogServiceIT` passes; `ArchitectureTest` still green | ☑ |
| B8 | Subscriptions E5 | `customer/SubscriptionRepository.java`, `customer/PlanChangeRepository.java` (read pending), `customer/SubscriptionService.java`, `customer/SubscriptionController.java`, `SubscriptionPlanIT` | R4 | CUS-03.1–03.3 | `SubscriptionPlanIT` E5 cases pass | ☑ |
| B9 | Upgrade quotes E6 | `customer/PlanQuoteService.java`, `customer/SubscriptionController.java`, `SubscriptionPlanIT` | R5 | CUS-03.4 | `SubscriptionPlanIT` E6 cases pass (effective date strictly after today) | ☑ |
| B10 | Plan change E7 + apply job | `customer/PlanChangeService.java`, `customer/PlanChangeApplyJob.java`, `customer/PlanChangeRepository.java`, `customer/SubscriptionController.java`, `SubscriptionPlanIT` | R6, §7 | CUS-03.5, BR-08, XC-06, NFR-REL | `SubscriptionPlanIT` passes (quote mismatch, replay, other-path key 422, CANCELLED guard, provisioning failure) | ☑ |
| B11 | Promotions E8, E9 | `customer/PromotionClaimRepository.java`, `customer/PromotionEligibilityService.java`, `customer/PromotionController.java`, `PromotionIT` | R7, R8 | CUS-04, XC-06 | `PromotionIT` passes (terms hash, other-path key 422) | ☑ |
| B12 | Case categories E10 | `servicecase/CategoryRepository.java`, `servicecase/SlaPolicyRepository.java`, `servicecase/CaseCategoryController.java`, `CaseCategoryIT` | R10 | CUS-06.2, BR-07 | `CaseCategoryIT` passes | ☑ |
| B13 | Own cases E11, E12 | `servicecase/CaseRepository.java`, `servicecase/CaseActivityRepository.java`, `servicecase/CustomerCaseService.java`, `servicecase/CustomerCaseController.java`, `CustomerCaseIT` | R9, R10 | CUS-05, CUS-06, BR-01/02/05/06/07 | `CustomerCaseIT` passes (leaf-first SLA, missing SLA 422, idempotent) | ☑ |
| B14 | PaymentGateway port + mock | `payment/PaymentGateway.java`, `payment/MockPaymentGateway.java`, `payment/PaymentGatewayProperties.java`, `MockPaymentGatewayTest` | §8 | CUS-08.2, NFR-AVAIL | `MockPaymentGatewayTest` passes (modes, NOT_FOUND, signature round-trip) | ☑ |
| B15 | Payment reads E13, E14, E16, E17 | `payment/InvoiceRepository.java`, `payment/PaymentRepository.java`, `payment/PaymentQueryService.java`, `payment/CustomerPaymentController.java`, `PaymentHistoryIT` | R11, R15 | CUS-07.1–07.4, CUS-08.1/08.5 | `PaymentHistoryIT` passes | ☑ |
| B16 | Guarded payment transitions + credit | `payment/PaymentTransitionService.java`, `payment/PaymentRepository.java`, `payment/InvoiceRepository.java`, `PaymentTransitionIT` | §3.1, R13, R14 | CUS-07.5, BR-08, NFR-INTEG | `PaymentTransitionIT` passes (parallel SUCCEEDED → one credit, disallowed pairs IGNORED) | ☑ |
| B17 | Initiate E15 | `payment/PaymentService.java`, `payment/CustomerPaymentController.java`, `PaymentInitiateIT` | R12, §3.1, §8 | CUS-08.1/08.2/08.4, NFR-REL, NFR-AVAIL | `PaymentInitiateIT` passes (tx1/tx2, refused 503 FAILED, timeout PENDING, recent EXPIRED re-check 409) | ☑ |
| B18 | Signed webhook E18 | `payment/PaymentWebhookController.java`, `payment/GatewayEventRepository.java`, `payment/WebhookRateLimiter.java`, `identity/SecurityConfig.java`, `PaymentWebhookIT` | §3.1, §4, §7, §8 | CUS-08.3/08.4, CUS-07.5, BR-08 | `PaymentWebhookIT` passes (401 before 400, 429, sampling, two events one credit, REVIEW) | ☑ |
| B19 | Mock checkout page + prod guard | `payment/MockCheckoutController.java`, `common/ProfileGuard.java`, `identity/SecurityConfig.java`, `MockCheckoutIT` | §4, §6, §8 | CUS-08.2, NFR-SEC | `MockCheckoutIT` passes | ☑ |
| B20 | Reconciliation job | `payment/PaymentReconciliationJob.java`, `PaymentReconciliationJobIT` | §3.1 | CUS-07.5, CUS-08.4/08.5, NFR-AVAIL | `PaymentReconciliationJobIT` passes (late capture, never auto-EXPIRE, 24 h REVIEW, parallel runs) | ☑ |
| B21 | Authz matrix, IDOR, architecture, traceability | `CustomerPortalAuthzIT`, `CustomerIdorIT`, `ArchitectureTest`, `docs/requirements/traceability.md` | §4, §9 | BR-10, XC-02, CUS-01.3, NFR-INTEG, NFR-MAINT | all pass; `./mvnw verify` green; slice 02 rows show code + tests || ☑ |

## Tasks — frontend (refs are `frontend.md` sections)

Paths under `frontend/` (code in `src/`, unit tests next to the code, e2e in `e2e/`). Every task: `bash scripts/check-frontend.sh` green
(typecheck, lint, test, gen:api:check). Each page task registers its page in `src/app/pageRegistry.ts` and adds MSW handlers it needs.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| F1 | Slice 02 API types + dev proxy | `scripts/gen-api.mjs`, `src/types/api/02-customer.ts` (generated), `src/types/api/index.ts`, `vite.config.ts` | §7 | NFR-MAINT, CUS-08.2 | `gen:api:check` and `typecheck` pass; `/mock-gateway` proxied | ☑ |
| F2 | Formatting + date window | `src/lib/format.ts` (`formatInr`, `isPositiveMoney`, dates), `src/lib/format.test.ts`, `src/lib/dateWindow.ts`, `src/lib/dateWindow.test.ts` | §4, §6 | XC-05, BR-05, CUS-04.1 | `format.test`, `dateWindow.test` pass (incl. exclusive `validTo`, `"0.00"`) | ☑ |
| F3 | Idempotency key + submit guard + test fixtures | `src/services/api/idempotency.ts` (`useIdempotencyKey`, `useSubmitGuard`), `idempotency.test.ts`, `src/test/fixtures/customer.ts`, `src/test/msw/customerHandlers.ts`, `src/test/msw/handlers.ts` | §4, §5, §8 | NFR-REL, CUS-06.6 | `idempotency.test` passes (path-scoped keys, sessionStorage record, one run on double call) | ☑ |
| F4 | shadcn primitives (1) | `src/components/ui/{dialog,alert-dialog,select,checkbox,radio-group}.tsx` (CLI-generated) | §4 | XC-08 | typecheck + lint green | ☑ |
| F5 | shadcn primitives (2) + DataTable | `src/components/ui/{textarea,table,tabs}.tsx`, `src/components/data/DataTable.tsx`, `DataTable.test.tsx` | §4 | NFR-RESP, XC-08 | `DataTable.test` passes (table ≥ md, cards < md, caption, `aria-busy`) | ☑ |
| F6 | List controls | `src/components/data/{DateRangeFilter,Pagination,StatusBadge}.tsx`, `useListParams.ts`, `listControls.test.tsx` | §3 lists, §4, §6 | XC-04, XC-05, CUS-05.1/05.3, CUS-07.2/07.3 | `listControls.test` passes (1-based URL page, invalid params dropped, manual tabs) | ☑ |
| F7 | Nested guarded routes + page registry | `src/permissions/navConfig.ts`, `src/app/pageRegistry.ts`, `src/app/routes.tsx`, `navConfig.test.ts`, `RequirePermission.test.tsx` | §2 | XC-02, BR-10, NFR-PERF | extended `navConfig.test` + `RequirePermission.test` pass (IN001 → 403 on every §2 route, no customer API call; lazy page under the guard) | ☑ |
| F8 | Shell: breadcrumbs + active nav for child routes | `src/components/layout/Breadcrumbs.tsx`, `src/components/layout/NavList.tsx`, `src/components/layout/AppShell.test.tsx` | §2 | XC-08, NFR-A11Y | `AppShell.test` passes (crumbs "Payment history › PAY-… › Receipt", parent item `aria-current` on child routes) | ☑ |
| F9 | Profile view + alias edit | `src/features/customer/profile/{queries.ts,ProfilePage.tsx,AliasForm.tsx,profile.test.tsx}`, `pageRegistry.ts` | §3 Profile, §6 | CUS-02.1/02.3/02.4, XC-07 | `profile.test` passes (cache `version` at submit) | ☑ |
| F10 | Contact verification dialog | `src/features/customer/profile/{ContactChangeDialog.tsx,contactSchemas.ts (incl. attemptsRemaining),contactVerification.test.tsx,ProfilePage.tsx,queries.ts}` | §3 Profile, §4, §6 | CUS-02.2, NFR-SEC | `contactVerification.test` passes; `profile.test` "confirm then alias save" passes | ☑ |
| F11 | My plan page | `src/features/customer/plan/{queries.ts,PlanPage.tsx,SubscriptionCard.tsx,plan.test.tsx}`, `pageRegistry.ts` | §3 My plan | CUS-03.1–03.3 | `plan.test` passes | ☑ |
| F12 | Upgrade options + plan change | `src/features/customer/plan/{UpgradeOptions.tsx,PlanChangeDialog.tsx,planChange.test.tsx,SubscriptionCard.tsx,queries.ts}` | §3 My plan, §6 | CUS-03.4/03.5, XC-06, NFR-REL | `planChange.test` passes | ☑ |
| F13 | Promotions + per-number claim | `src/features/customer/promotions/{queries.ts,PromotionsPage.tsx,ClaimDialog.tsx,promotions.test.tsx}`, `pageRegistry.ts` | §3 Promotions, §6, §10 D3 | CUS-04 | `promotions.test` passes | ☑ |
| F14 | Service request list | `src/features/customer/requests/{queries.ts,RequestListPage.tsx,requestList.test.tsx}`, `pageRegistry.ts` | §3 Service requests | CUS-05, BR-05/06, XC-04 | `requestList.test` passes | ☑ |
| F15 | Create service request | `src/features/customer/requests/{CreateRequestPage.tsx,createRequestSchema.ts,createRequest.test.tsx,queries.ts}`, `pageRegistry.ts` | §3 Create request, §6 | CUS-06.1/06.2/06.4–06.6, NFR-REL | `createRequest.test` passes | ☑ |
| F16 | Payment history | `src/features/customer/payments/{queries.ts,PaymentHistoryPage.tsx,paymentHistory.test.tsx}`, `pageRegistry.ts` | §3 Payment history | CUS-07.1–07.3/07.5 | `paymentHistory.test` passes | ☑ |
| F17 | Pay bill, redirect, abandoned checkout, bfcache | `src/features/customer/payments/{PayBillPage.tsx,redirect.ts,payBill.test.tsx,queries.ts}`, `pageRegistry.ts` | §3 Pay bill, §4 | CUS-08.1/08.2/08.4, NFR-REL, NFR-SEC | `payBill.test` passes | ☑ |
| F18 | Payment status (poll, poll errors) | `src/features/customer/payments/{PaymentStatusPage.tsx,paymentPolling.ts,paymentStatus.test.tsx}`, `pageRegistry.ts` | §3 Payment status | CUS-08.3–08.5, XC-07 | `paymentStatus.test` passes | ☑ |
| F19 | Printable receipt | `src/features/customer/payments/{ReceiptPage.tsx,receipt.test.tsx}`, `src/components/layout/{Sidebar,Header}.tsx` (`print:hidden`), `pageRegistry.ts` | §3 Receipt | CUS-07.4, CUS-08.5 | `receipt.test` passes | ☑ |
| F20 | Dashboard | `src/features/customer/dashboard/{DashboardPage.tsx,DashboardCards.tsx,dashboard.test.tsx}`, `pageRegistry.ts` | §3 Dashboard | CUS-01 | `dashboard.test` passes (one card 403/500, others render) | ☑ |
| F21 | e2e portal + forbidden | `e2e/customer-portal.spec.ts`, `e2e/forbidden.spec.ts`, `e2e/helpers/users.ts` (+ customer 5), `playwright.config.ts` (3 viewport projects, serial payment project) | §7, §8 | XC-02, XC-08, CUS-01/03/05/06/07 | needs backend D2; specs green against a fresh `dev,e2e` JAR | ⏸ deferred by user 2026-11-27 (browser tests postponed; unit tests + backend ITs only) |
| F22 | e2e payment + traceability | `e2e/payment.spec.ts`, `scripts/check-frontend.sh` (documents the fresh-JAR precondition), `docs/requirements/traceability.md` | §7, §8, §10 | CUS-08, CUS-07.4 | needs backend D1, D2; `check-frontend.sh e2e` green twice in a row (JAR restarted between); slice 02 rows show FE code + tests | ⏸ deferred by user 2026-11-27 (browser tests postponed; unit tests + backend ITs only) |

## Handoff (rewrite after every task, ≤ 5 lines)
- F1–F20 built; F21/F22 (browser e2e) deferred by the user 2026-11-27 (WIP specs in `frontend/e2e/`, not green).
- Code review: FC1–FC13 fixed (FC1/FC10 via erratum FE1: REVIEW payment alert on Pay bill, approved status labels); FC14/FC15 accepted.
- Review clean: no High/Medium open. `check-frontend.sh` default steps green (568 tests).
- Next: PR for `slice/02-customer-fe` (merge-steward); merge only on the user's "merge".
