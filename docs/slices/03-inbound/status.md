# Slice 03-inbound — status

| Gate | Backend | Frontend |
|---|---|---|
| Design | ☑ `docs/design/03-inbound/backend.md` + `openapi.yaml` — **Proposed** (branch `design/03-inbound`) | ☐ `docs/design/03-inbound/frontend.md` |
| Approved by user | ☐ | ☐ |
| Tasks planned | ☐ draft B1–B18 below (confirmed after approval) | ☐ |
| Built | ☐ branch `slice/03-inbound-be` | ☐ branch `slice/03-inbound-fe` |
| Checks green | ☐ | ☐ |
| Review clean (no open High) | ☐ `review.md` | ☐ `review.md` |
| PR | ☐ #… | ☐ #… |
| Merged (by user) | ☐ | ☐ |

## Tasks — backend (**draft**; refs are `backend.md` sections / rules / endpoints)

Paths: main code under `backend/src/main/java/com/telecom/support/`, migrations/seed under `backend/src/main/resources/db/`, tests under
`backend/src/test/…`. Every task that adds an endpoint also adds its anonymous (401), forbidden-role (CUSTOMER, OUTBOUND_AGENT → 403)
and unverified (403 `verification-required`) cases in its IT. Refactors keep the existing 02 ITs green.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| B1 | Schema V7: suspension, identity checks, internal categories, SLA rows, override permission | `V7__inbound.sql`, test-data `R__test_users_03_inbound.sql`, `identity/Permissions.java`, `SchemaV7toV8IT` | §2 | INB-01.2, INB-03.2, BR-07, §14 #4 | `SchemaV7toV8IT` V7 cases pass (suspension CHECK, SLA × 4 severities, permission on INBOUND_AGENT only); all 02 ITs green | ☐ |
| B2 | Schema V8: reactivation + notification | `V8__reactivation_notification.sql`, test-data `R__test_users_03_inbound.sql`, `SchemaV7toV8IT` | §2 | INB-04.2, INB-04.5, INB-04.7 | `SchemaV7toV8IT` passes (prohibited CHECK, open-request UK incl. REVIEW, 24 h CHECK, outbox UK) | ☐ |
| B3 | Dev + e2e seed | `db/seed/R__03_inbound.sql`, `db/seed/R__03_inbound_e2e.sql`, `E2eProfileIT` | §2 scripts | INB-01..04 | `E2eProfileIT` passes with the 03 rows (suspended NON_PAYMENT + FRAUD customers present) | ☐ |
| B4 | Extract `OtpCodec`; sender channel `PRIMARY_MSISDN` | `customer/OtpCodec.java`, `customer/ContactVerificationService.java`, `customer/VerificationCodeSender.java`, `customer/MockVerificationCodeSender.java`, `OtpCodecTest` | R2, §6 | INB-01.2, NFR-SEC | `OtpCodecTest` and unchanged `ContactVerificationIT` pass | ☐ |
| B5 | Lookup I1 + rate limit | `customer/CustomerLookupService.java`, `customer/LookupRateLimiter.java`, `customer/InboundProperties.java`, `customer/AgentCustomerController.java`, `CustomerLookupIT` | R1, §7 | INB-01.1, INB-01.4, NFR-PRIV | `CustomerLookupIT` passes (masking, miss, 429, audit) | ☐ |
| B6 | Identity checks I2–I4 | `customer/IdentityCheckRepository.java`, `customer/IdentityCheckService.java`, `customer/IdentityCheckController.java`, `IdentityCheckIT` | R2, R3, §7 | INB-01.2, §14 #2, NFR-SEC | `IdentityCheckIT` passes (parallel wrong confirms, per-customer 429 across agents, KBA, no secrets in audit) | ☐ |
| B7 | Verified access + record-access audit + I5, I6 | `customer/VerifiedCustomerAccess.java`, `customer/AgentCustomerService.java`, `customer/AgentCustomerController.java`, `common/error/ApiExceptions.java` (+ `verification-required`), `VerifiedCustomerAccessIT` | R4–R6, §4 | INB-01.1–01.4, BR-10 | `VerifiedCustomerAccessIT` + I5/I6 cases of `AgentCustomerViewIT` pass | ☐ |
| B8 | Payment context I7, I8 | `payment/PaymentQueryService.java` (account overloads), `payment/AgentPaymentController.java`, `AgentCustomerViewIT` | R6 | INB-01.3, BR-05/06 | `AgentCustomerViewIT` passes; `PaymentHistoryIT` green | ☐ |
| B9 | Case list + detail I9, I10 | `servicecase/AgentCaseQueryService.java`, `servicecase/CaseRepository.java`, `servicecase/CaseActivityRepository.java`, `servicecase/AgentCaseController.java`, `AgentCaseQueryIT` | R7–R9 | INB-02.1–02.4, BR-04, XC-04/05 | `AgentCaseQueryIT` passes (INTERNAL visibility, duplicates, other-account 404) | ☐ |
| B10 | Extract `CaseCreationService` | `servicecase/CaseCreationService.java`, `servicecase/CustomerCaseService.java`, `CaseCreationServiceIT` | R11, §6 | BR-01/02/07 | `CaseCreationServiceIT` and unchanged `CustomerCaseIT` pass | ☐ |
| B11 | Categories + create on behalf I11, I12 | `servicecase/AgentCaseService.java`, `servicecase/CategoryRepository.java`, `servicecase/AgentCaseController.java`, `AgentCaseCreateIT` | R10, R11, §7 | INB-03.1–03.5, §14 #4, NFR-REL | `AgentCaseCreateIT` passes (channel/creator, internal 403, override 400/403, idempotent replay + mismatch) | ☐ |
| B12 | Notification module (outbox + mock sender + dispatch job) | `notification/NotificationSender.java`, `notification/MockNotificationSender.java`, `notification/NotificationRepository.java`, `notification/NotificationService.java`, `notification/NotificationDispatchJob.java` (+ `NotificationDispatchIT`) | R15, §8 | INB-04.7, §14 #12 | `NotificationDispatchIT` passes (3 failures → FAILED, dedupe) | ☐ |
| B13 | Provisioning port + mock + properties + ProfileGuard | `reactivation/ProvisioningClient.java`, `reactivation/MockProvisioningClient.java`, `reactivation/ReactivationProperties.java`, `common/ProfileGuard.java`, `ProvisioningMockTest` | §6, §8 | INB-04.4, NFR-AVAIL, NFR-SEC | `ProvisioningMockTest` + extended `ProfileGuardIT` pass (prod + mock / `-` cron refused) | ☐ |
| B14 | Eligibility I13 | `reactivation/ReactivationPolicyRepository.java`, `reactivation/ReactivationRepository.java`, `reactivation/ReactivationEligibilityService.java`, `reactivation/ReactivationController.java`, `ReactivationEligibilityIT` | R12 | INB-04.1, INB-04.2, INB-04.6 | `ReactivationEligibilityIT` passes (every reason code) | ☐ |
| B15 | Create + history I14, I15 | `reactivation/ReactivationService.java`, `reactivation/ReactivationTransitionService.java`, `reactivation/ReactivationRepository.java`, `reactivation/ReactivationController.java`, `ReactivationCreateIT` | R13, R14, §3.1, §7 | INB-04.1–04.4, INB-04.6/04.7, NFR-REL | `ReactivationCreateIT` passes (parallel → one 201 + 409, REJECT/UNAVAILABLE/TIMEOUT paths, replay) | ☐ |
| B16 | Reconcile + expiry jobs | `reactivation/ReactivationReconcileJob.java`, `reactivation/ReactivationExpiryJob.java`, `reactivation/ReactivationTransitionService.java`, `application.yml`, `ReactivationJobsIT` | §3.1 | INB-04.5, BR-08, XC-07 | `ReactivationJobsIT` passes (expiry, reinstated → ENDED, 5 failures → REVIEW, parallel runs, run budget) | ☐ |
| B17 | Authz matrix, IDOR, architecture | `InboundAuthzIT`, `InboundIdorIT`, `ArchitectureTest` | §4, §9 | BR-10, XC-02, NFR-INTEG, NFR-MAINT | all three pass (ACCESS_DENIED asserted per the 01 §7 sampling rule) | ☐ |
| B18 | Traceability + pattern skill | `docs/requirements/traceability.md`, `.claude/skills/…` (guarded-transition + provider-port pattern, rule 7) | §9 | all | slice 03 rows `Built`; `bash scripts/check-backend.sh` green | ☐ |

## Tasks — frontend
| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| F1 | (planned after the frontend design is approved) | | | | | ☐ |

## Handoff (rewrite after every task, ≤ 5 lines)
- Done: backend design + openapi proposed on `design/03-inbound`; draft task list B1–B18.
- Next: user reviews `backend.md` §10 (OQ 1, 2, 4, 13 need a decision) and approves; then confirm B1–B18 and cut `slice/03-inbound-be`.
- Blockers / open questions: OQ 14 proposes a docs-only erratum E7 on 02 (ACCESS_DENIED wording); 00-overview §4 gets `403 verification-required` on approval.
