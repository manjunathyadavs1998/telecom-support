# Review — slice 02 backend, part 1 (B1–B17, B19, erratum E1)

- Range: `git diff c34d910..e9e325b -- backend docs` (branch `slice/02-customer-be`; B18/B20/B21 not in range). File:line refs are at commit `e9e325b`.
- Spec: `docs/design/02-customer/backend.md` (rev 2 + §11 E1), `openapi.yaml`, `docs/design/00-overview.md`, `CLAUDE.md`; BRD NFR-AVAIL, NFR-SEC, CUS-03.5.
- No Maven or tests were run (the builder is working in the same tree). Findings come from reading the code at `e9e325b`.

## Summary

| Severity | Count |
|---|---|
| High | 0 |
| Medium | 4 |
| Low | 10 |

Checked with no finding:
- **Authz:** every E1–E9 and E11–E17 handler has `@PreAuthorize(<§8 permission> and @customerScope.isCustomer())`. E10 has only the permission, as §4 says (but see C2).
- **Scope (R1):** the account always comes from `CustomerScope`. Each repository filters by `account_id`, or by `customer_id` for verifications, so another account's id gives 404 for E4, E6, E7, E9, E12, E15, E16 and E17.
- **SQL:** all SQL uses named parameters in text blocks. The only concatenation joins fixed fragments: `REFERENCE_FILTER`, `STATUS_FILTER`, and the `Order` enums picked from the `CaseSort` / `PaymentSort` whitelists.
- **OTP (§3.2):** the attempt is counted in REQUIRES_NEW before the compare, the compare is constant-time, and LOCKED / VERIFIED are guarded updates.
- **Payments:** §3.1 transitions run under a payment row lock, then the invoice lock, then a version-guarded credit. Side effects happen only when one row changed.
- **E1 lock:** initiate takes `SELECT … FOR UPDATE` on the invoice and returns 409 on PENDING or REVIEW. The V6 key covers PENDING only.
- **Idempotency:** the hashes include path variables for E7 and E9. E12 and E15 hash the body only, which is correct because they have no path ids.
- **Migrations:** V3–V6 match §2. They are not on `main`, so editing V6 in place for E1 is allowed.
- **Logs and audit:** no OTP, secret or card data is logged, and audit values are masked as §7 asks.
- **Tests:** each IT has 401 and 403 cases for T-IN, T-OUT, T-BUS and the unlinked customer on `/customers/me/**`. The §9 cases listed for B1–B17 and B19 are present, and no test is skipped.

## Findings

| # | Severity | file:line | Finding | Why it matters (rule/BRD ID) | Suggested fix | Design change? | Status |
|---|---|---|---|---|---|---|---|
| C1 | Medium | `backend/src/main/java/com/telecom/support/customer/ContactVerificationService.java:142-148`; `backend/src/main/resources/application.yml:56,64`; `backend/src/main/java/com/telecom/support/common/ProfileGuard.java:28-44` | When `OTP_HMAC_SECRET` (and `PAYMENT_WEBHOOK_SECRET`, used by B18) is blank, each process makes its own random key. `ProfileGuard` does not reject a blank value under `prod`. With more than one instance, which §3.1 assumes ("several app instances"), a code started on instance A never matches on instance B. The customer is then told "wrong code" and locked out after 5 tries, and B18 will reject signed webhooks with 401. (Builder note B6.) | NFR-AVAIL, NFR-SEC; §3.1 assumes several instances | Have `ProfileGuard` fail `prod` when `app.customer.otp.hmac-secret` or `app.payment.gateway.webhook-secret` is blank, or require them with no default in a `prod` profile file. Keep the random fallback for dev and test only. | Yes, small: add both secrets to the §6 ProfileGuard list | Open |
| C2 | Medium | `backend/src/main/java/com/telecom/support/servicecase/CaseCategoryController.java:34`; design `backend.md` §4 vs §9 (`CustomerPortalAuthzIT`) | E10 `/case-categories` checks only `case.create`, which matches §4: `isCustomer()` is added only for `/customers/me/**`. But §9 `CustomerPortalAuthzIT` requires "every E1–E17 … unlinked CUSTOMER 403", and an unlinked customer gets 200 here. The design contradicts itself, and B21 will either fail or quietly drop E10 from the matrix. (Builder note B11/B12.) | BR-10, XC-02; design §4/§9 | Decide in an erratum. Either add `and @customerScope.isCustomer()` to E10 (the endpoint only serves the customer portal; 03 on-behalf uses another permission) and add an unlinked-customer 403 to `CaseCategoryIT`, or exclude E10 from that §9 row. | Yes (erratum E2) | Open |
| C3 | Medium | `backend/src/main/java/com/telecom/support/customer/PlanChangeApplyJob.java:120-127,141-150`; `backend/src/main/java/com/telecom/support/customer/PlanChangeRepository.java:75-78` | When provisioning fails, the change stays SCHEDULED and is retried every day with no limit (`apply_attempts` is capped at 127 but never acted on). While it stays SCHEDULED, the UK `scheduled_sub_id` blocks the subscription: E6 returns `[]` and E7 returns 422. A permanently failing change therefore freezes plan changes for that number, and the `effective_date` the customer confirmed keeps slipping. (Builder note B10.) | CUS-03.5 (confirmed effective date), NFR-AVAIL, R6 | Add `app.customer.plan-change.max-apply-attempts` (e.g. 3). After N failures, set `CANCELLED` with `cancel_reason=provisioning_failed` and audit `PLAN_CHANGE_CANCELLED`. Test it in `SubscriptionPlanIT`. | Yes (R6: bound the retries and say what happens after) | Open |
| C4 | Medium | `docs/design/02-customer/backend.md:287-301` (commits `4296bf2`, `8ecab87`) | Erratum E1 is marked "Status: **Approved**" but exists only on `slice/02-customer-be`. `git log origin/main -- docs/design/02-customer/backend.md` shows only `c0f883a`, and `origin/main` has no E1 text. CLAUDE.md says a design is approved only when that change is merged to `main`, from a `design/NN-*` branch. The E1 code (`f6385f0`: V6 key, invoice lock, REVIEW 409) was built on a design change that has not been merged. | CLAUDE.md gated workflow 1 and 3 | Before the BE PR merges, either merge E1 to `main` through a `design/02-*` PR, or have the user approve E1 explicitly as part of the BE PR and record that in `status.md` and the PR body. Apply the same process to any E2 from C2/C3. | Process only | Open |
| C5 | Low | `backend/src/main/java/com/telecom/support/payment/PaymentTransitionService.java:227-233`; `PaymentRepository.java:124` | If the stored `gateway_session_id` is null (initiate timed out), `matches()` accepts **any** non-null capture session and writes it to the row. §3.1 says "amount + currency + session match" and does not describe this. The behaviour is reasonable, because the webhook is signed and the reference is unique, but it is an undocumented rule in the money path. `PaymentTransitionIT.emptyStoredSessionIsFilledFromTheCapture` already tests it. | §3.1, NFR-INTEG | Add a §3.1 note: "no stored session (gateway_timeout) → session taken from the capture". | Yes (doc note) | Open |
| C6 | Low | `backend/src/main/java/com/telecom/support/customer/PlanChangeApplyJob.java:102-133` | `PlanProvisioning.apply` runs inside the DB transaction and holds row locks on `plan_changes` and `subscriptions`. If the provider succeeds and the commit fails, the provider has the new plan but the DB does not, and the next run calls `apply` again. This follows the R6 order, but it goes against the "no tx open during the port call" principle used for payments. | BR-08, XC-07 | Write in §8 that `PlanProvisioning.apply` must be idempotent per (msisdn, plan, date), or split the work: claim in tx, call the port, then finish in tx. | Optional (doc) | Open |
| C7 | Low | `backend/src/main/resources/application.yml:61`; `backend/src/main/java/com/telecom/support/payment/PaymentService.java:112-120` | `app.payment.return-url-base` defaults to `http://localhost:${server.port}` in every profile. If prod is not configured, customers are sent back to localhost after paying. `ProfileGuard` does not catch this. (Builder note B17.) | CUS-08.3, NFR-SEC (secure transport) | Under `prod`, require an `https://` `return-url-base` with no default, checked in `ProfileGuard` or by a fail-fast check at startup. | Yes, small (§6 ProfileGuard list) | Open |
| C8 | Low | `backend/src/main/java/com/telecom/support/payment/MockPaymentGateway.java:47`; `backend/src/main/java/com/telecom/support/customer/MockVerificationCodeSender.java:14` | The mock gateway and mock OTP sender are unconditional `@Component`s, so they are the only adapters in `prod` too. `mock.enabled=false` only removes the checkout page. In prod, E15 would return a `/mock-gateway/...` redirect that gives 404, and OTP codes would go nowhere. No money is at risk, but this fails silently. (Builder note B14.) | NFR-AVAIL; §8 (real adapters in 06) | Until 06, make `ProfileGuard` (or a `@Profile("!prod")` / `@ConditionalOnMissingBean` setup) refuse `prod` while only mock adapters exist, or record the deviation in §8. | Yes (doc or guard) | Open |
| C9 | Low | `docs/design/02-customer/openapi.yaml:6,604`; `backend/src/main/java/com/telecom/support/customer/CustomerRepository.java:29` | `Profile.primaryMsisdn` is `required` and not nullable, but it is null for an account with no primary subscription (LEFT JOIN). It is also not filtered by `status <> 'TERMINATED'`. The generated FE types will be wrong. The openapi header still says "Status: **Proposed**". (Builder note B4.) | XC-03, contract-first | Make `primaryMsisdn` nullable in openapi (or derive it only from a non-TERMINATED primary). Change the header to Approved. | Yes (openapi) | Open |
| C10 | Low | `backend/src/main/java/com/telecom/support/servicecase/CustomerCaseService.java:209-212,273-274`; `V5__servicecase.sql:12` | E12 returns 422 when there is no primary MSISDN. R10 does not list this case (`contact_msisdn` is NOT NULL, so some rejection is needed). Also, R10's "case_type from the leaf (else parent)" fallback can never happen, because `categories.case_type` is NOT NULL. (Builder notes B2, B13.) | R10, design conformance | Add both points to R10, and add the no-primary 422 to the openapi E12 responses. | Yes (doc) | Open |
| C11 | Low | `backend/src/main/java/com/telecom/support/servicecase/CustomerCaseService.java:162-167`; `backend/src/main/java/com/telecom/support/payment/PaymentQueryService.java:127-132` | When only `to` is given, `from` defaults to today − 3 months rather than `to` − 3 months. A `to` older than 3 months then gives a 400 "start after end" that the user did not cause. (Builder note B13.) | 00-overview §3, BR-05/06 | Default `from = to.minusMonths(3)` when only `to` is given, or say in 00-overview §3 that a missing `from` is anchored to today. | Yes (00-overview §3 clarification) | Open |
| C12 | Low | `backend/src/main/java/com/telecom/support/customer/CustomerProfileController.java:35-37` | An alias made only of whitespace (e.g. `"   "`) passes `@Size(1..60)` and the pattern, and is stored as is. (Builder note B4.) | R2, CUS-02.1 | Trim the alias, and treat blank as 400 (or as clear). Add a test case. | Yes, small (R2: trim rule) | Open |
| C13 | Low | `backend/src/main/java/com/telecom/support/payment/PaymentService.java:197-204`; design R12 | R12 says that if the gateway reports PENDING or SUCCEEDED on a recent FAILED/EXPIRED payment, "it is applied". The code applies SUCCEEDED but returns 409 with no transition for PENDING. That is correct, because §3.1 has no EXPIRED/FAILED→PENDING row, but the R12 wording and the code differ. (Builder note B17.) | R12, §3.1 | Reword R12: "SUCCEEDED → applied; PENDING → no change; both 409". | Yes (doc) | Open |
| C14 | Low | `docs/slices/02-customer/status.md:34-35` | Rows B14 and B15 end in `\|\| ☑`, which adds an extra table cell. | Docs hygiene | Remove the duplicate `\|`. | No | Open |

## Builder notes (`02-build-notes.md`): how each was assessed

| Note | Assessment |
|---|---|
| B2: R10 case_type fallback can't happen; 4th INACTIVE promotion; V5 placeholders | Fallback → C10. The extra test promotion and the placeholder categories are fine (OQ 5). |
| B4: whitespace alias; `primaryMsisdn` null; openapi "Proposed" | C12, C9. |
| B6: random HMAC key; dev fixed code in the service; a parallel correct confirm that loses gets 422 "expired"; new 404/429/503 exceptions | Key → C1 (Medium). The fixed code is fine (ProfileGuard blocks it in prod). The losing confirm getting 422 `expired` is acceptable: only one confirm can win the PENDING→VERIFIED update, and it gets one success audit. The new `ApiExceptions` map to ProblemDetail, and 429 sends `Retry-After`. |
| B10: unlimited retries; job audit has no actor | Retries → C3 (Medium). No actor matches §7 ("job rows: actor null"). |
| B11/B12: R7 semantics; unknown promotion 404 vs 422; E10 `isCustomer` | The R7 reading is reasonable and matches openapi. 404 for an unknown code is consistent with R1. E10 → C2 (Medium). |
| B13: no primary → 422; `to`-only window; cases are append-only in tests | C10, C11. Tests must handle extra cases; that is for B21 to handle. |
| B14/B15: mock gateway always a bean; malformed reference → 404; one properties class | C8. 404 for a malformed reference is fine (it doesn't confirm whether a reference exists). One class is fine. |
| B17: gateway PENDING on a re-check → 409 with no change; returnUrl path; default return-url-base | C13, C7. The returnUrl path `/customer/payments/{ref}` must be confirmed in the FE design. |

Not a finding yet: `docs/requirements/traceability.md` has not been updated. B21 plans to do it, and the reviewer must check it before the PR.

## Disposition (builder, 2026-11-25)

Errata E2–E4 in `backend.md` §11 (Approved) cover C2, C3 and the return-URL part of C7. Doc-only fixes are left for the
design owner, because builders do not edit design docs.

| # | Disposition |
|---|---|
| C1 | Fixed (`39c8894`): `ProfileGuard` refuses `prod` when `app.customer.otp.hmac-secret` or `app.payment.gateway.webhook-secret` is blank; random keys stay for dev/test. `ProfileGuardTest`, `ProfileGuardIT.prodWithoutTheHmacSecretsFailsToStart`. The §6 ProfileGuard list still needs a doc line. |
| C2 | Fixed (`39c8894`), erratum E2: E10 `@PreAuthorize` adds `@customerScope.isCustomer()`; `CaseCategoryIT` unlinked customer → 403. |
| C3 | Fixed (`39c8894`), erratum E3: the 3rd failed provisioning attempt → `CANCELLED provisioning_failed` + `PLAN_CHANGE_CANCELLED` (FAILURE, no actor); `SubscriptionPlanIT.thirdProvisioningFailureCancelsTheChangeAndFreesTheSubscription` also schedules a new change afterwards. |
| C4 | Process only, not handled by the builder: approval of E1–E4 must be recorded on `main` / in the PR before merge. |
| C5 | Accepted: the behaviour is intended and tested (`PaymentTransitionIT.emptyStoredSessionIsFilledFromTheCapture`); only a §3.1 doc note is needed. |
| C6 | Accepted: the code follows R6's order; making `PlanProvisioning.apply` idempotent (or splitting the tx) is a §8 design decision for the real adapter (slice 06). The mock is idempotent. |
| C7 | Fixed (`39c8894`), erratum E4: prod has no default `return-url-base` (`PAYMENT_RETURN_URL_BASE`), and `ProfileGuard` refuses a blank value (`ProfileGuardIT.prodWithoutAReturnUrlBaseFailsToStart`). The returnUrl path is asserted in `PaymentInitiateIT` and `MockCheckoutIT`. Requiring `https://` is accepted as not in E4. |
| C8 | Accepted: refusing prod while only mock adapters exist (or `@Profile` wiring) is a design decision; prod is not deployable before the real adapters in slice 06. |
| C9 | Accepted: needs an openapi change (`primaryMsisdn` nullable, header Approved), which is design-owned. |
| C10 | Accepted: doc-only (R10 and openapi E12 422); the code already rejects no-primary with 422. |
| C11 | Fixed (`39c8894`): with only `to` given, `from` = `to` − 3 months (E11, E14). This fits 00-overview §3 ("default to the last 3 months"). `CustomerCaseIT` / `PaymentHistoryIT.onlyToGivenListsTheThreeMonthsBeforeIt`. |
| C12 | Accepted: trimming, and choosing between 400 and clear for a blank alias, changes the R2/openapi contract (the pattern allows spaces); this needs a design rule. |
| C13 | Accepted: doc-only (R12 wording); the code matches §3.1. |
| C14 | Fixed (this docs commit): the B14/B15 rows in `status.md` no longer have the extra cell. |
| Extra: reconcile FAILED reason | Accepted: the §3.1 row "failed / expired reported by the gateway" names no reason, so adding `gateway_failed` would be a new value not in the design. |
