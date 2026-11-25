# Slice 02-customer — status

| Gate | Backend | Frontend |
|---|---|---|
| Design | ☑ `docs/design/02-customer/backend.md` + `openapi.yaml` — **Approved** rev 2 (review D1–D23 addressed) | ☐ `docs/design/02-customer/frontend.md` |
| Approved by user | ☑ 2026-11-25 (also 00-overview §1 rev 3; OQ defaults kept) | ☐ |
| Tasks planned | ☑ B1–B21 confirmed 2026-11-25 | ☐ |
| Built | ☐ branch `slice/02-customer-be` | ☐ branch `slice/02-customer-fe` |
| Checks green | ☐ | ☐ |
| Review clean (no open High) | ☐ `review.md` (design review: `review-backend-design.md`) | ☐ `review.md` |
| PR | ☐ #… | ☐ #… |
| Merged (by user) | ☐ | ☐ |

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
| B14 | PaymentGateway port + mock | `payment/PaymentGateway.java`, `payment/MockPaymentGateway.java`, `payment/PaymentGatewayProperties.java`, `MockPaymentGatewayTest` | §8 | CUS-08.2, NFR-AVAIL | `MockPaymentGatewayTest` passes (modes, NOT_FOUND, signature round-trip) || ☑ |
| B15 | Payment reads E13, E14, E16, E17 | `payment/InvoiceRepository.java`, `payment/PaymentRepository.java`, `payment/PaymentQueryService.java`, `payment/CustomerPaymentController.java`, `PaymentHistoryIT` | R11, R15 | CUS-07.1–07.4, CUS-08.1/08.5 | `PaymentHistoryIT` passes || ☑ |
| B16 | Guarded payment transitions + credit | `payment/PaymentTransitionService.java`, `payment/PaymentRepository.java`, `payment/InvoiceRepository.java`, `PaymentTransitionIT` | §3.1, R13, R14 | CUS-07.5, BR-08, NFR-INTEG | `PaymentTransitionIT` passes (parallel SUCCEEDED → one credit, disallowed pairs IGNORED) | ☑ |
| B17 | Initiate E15 | `payment/PaymentService.java`, `payment/CustomerPaymentController.java`, `PaymentInitiateIT` | R12, §3.1, §8 | CUS-08.1/08.2/08.4, NFR-REL, NFR-AVAIL | `PaymentInitiateIT` passes (tx1/tx2, refused 503 FAILED, timeout PENDING, recent EXPIRED re-check 409) | ☐ |
| B18 | Signed webhook E18 | `payment/PaymentWebhookController.java`, `payment/GatewayEventRepository.java`, `payment/WebhookRateLimiter.java`, `identity/SecurityConfig.java`, `PaymentWebhookIT` | §3.1, §4, §7, §8 | CUS-08.3/08.4, CUS-07.5, BR-08 | `PaymentWebhookIT` passes (401 before 400, 429, sampling, two events one credit, REVIEW) | ☐ |
| B19 | Mock checkout page + prod guard | `payment/MockCheckoutController.java`, `common/ProfileGuard.java`, `identity/SecurityConfig.java`, `MockCheckoutIT` | §4, §6, §8 | CUS-08.2, NFR-SEC | `MockCheckoutIT` passes | ☑ |
| B20 | Reconciliation job | `payment/PaymentReconciliationJob.java`, `PaymentReconciliationJobIT` | §3.1 | CUS-07.5, CUS-08.4/08.5, NFR-AVAIL | `PaymentReconciliationJobIT` passes (late capture, never auto-EXPIRE, 24 h REVIEW, parallel runs) | ☐ |
| B21 | Authz matrix, IDOR, architecture, traceability | `CustomerPortalAuthzIT`, `CustomerIdorIT`, `ArchitectureTest`, `docs/requirements/traceability.md` | §4, §9 | BR-10, XC-02, CUS-01.3, NFR-INTEG, NFR-MAINT | all pass; `./mvnw verify` green; slice 02 rows show code + tests | ☐ |

## Tasks — frontend
| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| F1 | (planned after the frontend design is approved) | | | | | ☐ |

## Handoff (rewrite after every task, ≤ 5 lines)
- Done: B1–B16, B19 (mock checkout page, `ProfileGuard` refuses prod + mock, CSRF-ignore only when mock on; `SpaForwardController` excludes `mock-gateway`). Full suite 339/0.
- Test infra: `src/test/resources/spring.properties` caps cached test contexts at 8 (was hitting MySQL's 151 connections); no max-connections bump.
- Gaps: B19's webhook post reaches nothing until B18; FE dev needs a Vite proxy for `/mock-gateway`.
- E1 schema/transition part done (commit `fix(payment): apply design erratum E1 …`: V6 key PENDING only, REVIEW always applied); initiate part in B17.
- Next: B17 (incl. E1 invoice lock + 409 on PENDING/REVIEW), B18, B20, B21.
