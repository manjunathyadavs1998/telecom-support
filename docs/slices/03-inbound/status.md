# Slice 03-inbound — status

| Gate | Backend | Frontend |
|---|---|---|
| Design | ☑ `docs/design/03-inbound/backend.md` + `openapi.yaml` — **Approved** rev 2 (review D1–D22 addressed; branch `design/03-inbound`) | ☐ `docs/design/03-inbound/frontend.md` |
| Approved by user | ☑ 2026-11-26 (OQ defaults kept; also 00-overview rev 4, 01 E3, 02 E7) | ☐ |
| Tasks planned | ☑ B1–B20 confirmed 2026-11-26 | ☐ |
| Built | ☐ branch `slice/03-inbound-be` | ☐ branch `slice/03-inbound-fe` |
| Checks green | ☐ | ☐ |
| Review clean (no open High) | ☐ `review.md` | ☐ `review.md` |
| PR | ☐ #… | ☐ #… |
| Merged (by user) | ☐ | ☐ |

## Tasks — backend (refs are `backend.md` sections / rules / endpoints)

Paths: main code under `backend/src/main/java/com/telecom/support/`, migrations/seed under `backend/src/main/resources/db/`, tests under
`backend/src/test/…`. Every task that adds an endpoint also adds its anonymous (401), forbidden-role (CUSTOMER, OUTBOUND_AGENT → 403)
and unverified (403 `verification-required`) cases in its IT. Refactors keep the existing 02 ITs green.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| B1 | V7 account suspension + 02 IT updates | `V7__account_suspension.sql`, `support/AbstractMySqlIT.java` (`suspendAccount(ref, reason)`, `reinstateAccount(ref)`), `PromotionIT`, `SubscriptionPlanIT`, `CustomerCaseIT`, `PaymentInitiateIT` (one-line switch to the helpers) | §2 | INB-04.1, NFR-INTEG | `SchemaV7toV9IT` V7 case passes and the four 02 ITs are green | ☑ |
| B2 | V8 inbound schema + permission | `V8__inbound.sql`, test-data `R__test_users_03_inbound.sql`, `identity/Permissions.java`, `PermissionMatrixIT` (26 + new row), `SchemaV7toV9IT` | §2, 01 §13 E3 | INB-01.2, INB-03.2, BR-07, §14 #4 | `SchemaV7toV9IT` V8 cases (identity_checks CHECKs, leaf × 4 severities) and `PermissionMatrixIT` pass | ☑ |
| B3 | V9 reactivation + notification schema | `V9__reactivation_notification.sql`, test-data `R__test_users_03_inbound.sql`, `SchemaV7toV9IT` | §2 | INB-04.2, INB-04.5, INB-04.7 | `SchemaV7toV9IT` passes (prohibited CHECK, open UK, 24 h CHECK, outbox UK) | ☑ |
| B4 | Dev + e2e seed | `db/seed/R__03_inbound.sql`, `db/seed/R__03_inbound_e2e.sql`, `E2eProfileIT` | §2 | INB-01..04 | `E2eProfileIT` passes with the 03 rows | ☑ |
| B5 | Cross-module APIs | `identity/UserDirectory.java`, `customer/AccountLookup.java` (lock, suspension fields, suspended MSISDNs, contacts), `customer/CustomerRepository.java`, `ArchitectureTest` (new edges), `CrossModuleApiIT` | §1, §6.1 | NFR-MAINT | `CrossModuleApiIT` + `ArchitectureTest` pass | ☑ |
| B6 | Extract `OtpCodec`; sender channel `PRIMARY_MSISDN` | `customer/OtpCodec.java`, `customer/ContactVerificationService.java`, `customer/VerificationCodeSender.java`, `customer/MockVerificationCodeSender.java`, `OtpCodecTest` | R3 | INB-01.2 | `OtpCodecTest` + unchanged `ContactVerificationIT` pass | ☑ |
| B7 | Search I1 + lookup tokens | `customer/CustomerLookupService.java`, `customer/CustomerLookupRepository.java`, `customer/LookupRateLimiter.java`, `customer/InboundProperties.java`, `customer/AgentCustomerController.java` (+ `CustomerLookupIT`) | R1 | INB-01.1/01.4, NFR-PRIV | `CustomerLookupIT` passes | ☑ |
| B8 | Identity checks I2–I4 + cleanup job | `customer/IdentityCheckRepository.java`, `customer/IdentityCheckService.java`, `customer/IdentityCheckController.java`, `customer/IdentityCheckCleanupJob.java`, `IdentityCheckIT` | R2–R4 | INB-01.1/01.2, NFR-SEC | `IdentityCheckIT` passes (parallel OTP starts = 5, parallel KBA = 3 evaluated, token 404s identical) | ☐ |
| B9 | Central verified scope + problem type | `customer/VerifiedCustomer.java`, `customer/VerifiedCustomerAccess.java`, `customer/VerifiedCustomerInterceptor.java` (+ resolver, `WebMvcConfigurer`), `common/error/{ApiExceptions,ProblemDetailFactory,GlobalExceptionHandler}.java`, `VerifiedCustomerAccessIT` | R5, R6, §4 | INB-01.2/01.4, BR-10 | `VerifiedCustomerAccessIT` passes (incl. parallel first reads) | ☐ |
| B10 | Verified view I5–I8 | `customer/CustomerService.java` (`profileOf`, `contactsOf`), `customer/SubscriptionService.java` (`subscriptionsOf`, `liveSubscription`), `customer/AgentCustomerController.java`, `payment/{PaymentQueryService,AgentPaymentController}.java`, `AgentCustomerViewIT` | R7, §6.1 | INB-01.3 | `AgentCustomerViewIT` passes; 02 ITs green | ☐ |
| B11 | Case list + detail I9, I10 | `servicecase/AgentCaseQueryService.java`, `servicecase/CaseRepository.java`, `servicecase/CaseActivityRepository.java`, `servicecase/AgentCaseController.java`, `AgentCaseQueryIT` | R8–R10 | INB-02, BR-04 | `AgentCaseQueryIT` passes | ☐ |
| B12 | Extract `CaseCreationService` | `servicecase/CaseCreationService.java`, `servicecase/CustomerCaseService.java`, `CaseCreationServiceIT` | R12 | BR-01/02/07 | `CaseCreationServiceIT` + unchanged `CustomerCaseIT` pass | ☐ |
| B13 | Categories + create on behalf I11, I12 | `servicecase/AgentCaseService.java`, `servicecase/CategoryRepository.java`, `servicecase/AgentCaseController.java`, `AgentCaseCreateIT`, `AgentRouteCoverageTest` | R11, R12 | INB-03, §14 #4, NFR-REL | both pass (replay after expiry → 403) | ☐ |
| B14 | Notification module | `notification/{NotificationSender,MockNotificationSender,NotificationRepository,NotificationService,NotificationDispatchJob}.java`, `application.yml` (`app.notification.*`) (+ `NotificationDispatchIT`) | R16 | INB-04.7 | `NotificationDispatchIT` passes (parallel runs → one send) | ☐ |
| B15 | Provisioning port, properties, crons, prod guards | `reactivation/{ProvisioningClient,MockProvisioningClient,ReactivationProperties}.java`, `application.yml` (`app.reactivation.*`), `common/{ProfileGuard,ProdAdapterCheck}.java` (+ `ProvisioningMockTest`, `ProdAdapterCheckIT`) | §6, §8 | INB-04.4, NFR-SEC | the tests + extended `ProfileGuardIT` pass | ☐ |
| B16 | Eligibility I13 | `reactivation/{ReactivationPolicyRepository,ReactivationRepository,ReactivationEligibilityService,ReactivationController}.java`, `ReactivationEligibilityIT` | R13 | INB-04.1/04.2/04.6 | `ReactivationEligibilityIT` passes | ☐ |
| B17 | Create + history I14, I15 | `reactivation/{ReactivationService,ReactivationTransitionService,ReactivationRepository,ReactivationController}.java`, `ReactivationCreateIT` | R14, R15, §3.1 | INB-04.1–04.4/04.6/04.7, NFR-REL | `ReactivationCreateIT` passes (agent-level parallel limit) | ☐ |
| B18 | Reconcile + expiry jobs | `reactivation/{ReactivationReconcileJob,ReactivationExpiryJob,ReactivationTransitionService}.java`, `ReactivationJobsIT` | §3.1 | INB-04.5, BR-03, BR-08 | `ReactivationJobsIT` passes (10 failed suspends then EXPIRED, late RESTORED → EXPIRING → EXPIRED) | ☐ |
| B19 | Authz matrix + IDOR | `InboundAuthzIT`, `InboundIdorIT` | §4, §9 | BR-10, XC-02, NFR-INTEG | both pass | ☐ |
| B20 | Traceability + pattern skill | `docs/requirements/traceability.md`, `.claude/skills/…` (rule 7) | §9 | all | slice 03 rows `Built`; `bash scripts/check-backend.sh` green | ☐ |

## Tasks — frontend
| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| F1 | (planned after the frontend design is approved) | | | | | ☐ |

## Handoff (rewrite after every task, ≤ 5 lines)
- Done: B1–B4 (V7–V9, test data, dev/e2e seed; test-data INTERNAL cases on CUST-T-0006, user decision). B5: `identity.UserDirectory`
  (displayNames, teamNames, lockForUpdate MANDATORY); `AccountLookup` + `SuspensionReason`, `lockForUpdate(accountId)` MANDATORY, `suspendedMsisdns`, `notificationContacts`; `ArchitectureTest` edge whitelist (rev 4).
- B6: `customer.OtpCodec` (newCode/hmac/matches, shared key `app.customer.otp.*`) used by `ContactVerificationService`; `Channel.PRIMARY_MSISDN` added.
  Guard: the 02 `ContactVerificationStart` validator rejects `PRIMARY_MSISDN` (400 on `channel`) so customers cannot use it.
- Next: B7 (search I1 + lookup tokens, `CustomerLookupIT`).
