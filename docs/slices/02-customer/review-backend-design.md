# Slice 02 Customer portal: backend design review (pre-approval)

- Target: the **Proposed** `docs/design/02-customer/backend.md` + `openapi.yaml`, the draft tasks in `docs/slices/02-customer/status.md`,
  and the slice 02 rows in `docs/requirements/traceability.md`.
- Process state (checked with git, 2026-11-25): branch `design/02-customer`. `backend.md`, `openapi.yaml` and `status.md` are
  **untracked**, and `traceability.md` is modified but not committed. The design is **not approved**: it isn't on `origin/main`, and
  its header says `Status: **Proposed**`. `origin/main` = `f736871`.
- Checked against: BRD CUS-01..08, BR-01/02/04..08/10, XC-03..07, NFR-AVAIL/INTEG/PRIV/REL/SEC, §8, §11, §12, §14 (from
  `brd_section.py`). Also 00-overview (rev 2), 01-foundation backend (rev 2), V1/V2, `R__01_identity.sql`,
  `R__test_users.sql`, `ArchitectureTest`, `CurrentUser`.
- Line numbers refer to the working-tree files.

| Severity | Count |
|---|---|
| High | 4 |
| Medium | 9 |
| Low | 10 |

## High

### D1. Payment SUCCEEDED can be applied twice, so `invoices.amount_paid` is double-counted
- **Location:** backend.md:98-99 (R13, R14), :193 (reconcile), :194 (webhook dedupe), :229-230 (tests).
- **Problem:** The only dedupe is `payment_gateway_events.gateway_event_id`, and it covers only webhook replays of the *same* event.
  Several paths can each apply SUCCEEDED to the same payment:
  - a webhook and `PaymentReconciliationJob.getStatus` running at the same moment;
  - two distinct gateway events for one capture (gateways do retry with new ids);
  - two app instances running the job.

  R14 says `amount_paid += amount` "on SUCCEEDED (same tx)". Nothing makes the transition itself conditional, and `payments.version` is
  listed without a rule for how it is used. Result: double credit, and a false `PAYMENT_OVERPAID` (NFR-INTEG, CUS-07.5).
- **Fix:** State the transition as a guarded update:
  `UPDATE payments SET status='SUCCEEDED', … WHERE id=:id AND status IN (<allowed from-states>)`.
  Touch the invoice and write the audit only when exactly 1 row was updated. Otherwise record the event as `IGNORED`.
  Use the same rule for FAILED/EXPIRED. Add a test that applies the webhook and reconcile concurrently and expects one credit, plus a
  test with two distinct success events for one payment.

### D2. After an EXPIRED payment the customer can pay twice, and a late capture with a lost webhook is never reconciled
- **Location:** backend.md:97 (R12: "another PENDING … → 409"), :99 (overpay only audited), :107-113 (state diagram), :193
  ("still-unknown session → EXPIRED"; the job polls only PENDING).
- **Problem:**
  - The reconciliation job marks a payment `EXPIRED` because the session is *unknown*, not because the gateway confirmed it closed.
    That frees the `pending_invoice_id` UK, so a second payment on the same invoice is allowed.
  - The diagram allows `EXPIRED → SUCCEEDED` only from a webhook. The job never polls EXPIRED rows, so if that webhook is lost the
    captured money is never reflected. The invoice stays OPEN, and the customer is invited to pay again.
  - The design accepts the overpayment ("recorded and audited, refunds out").

  This contradicts CUS-08.4 (duplicate-attempt scenario), CUS-07.5 (state reconciled with the payment source) and BR-08.
- **Fix:**
  - Mark EXPIRED only when `getStatus` returns a terminal expired/failed state. While the gateway is unreachable, keep the payment PENDING.
  - Keep polling EXPIRED and FAILED-by-timeout payments for a bounded window (e.g. 24 h).
  - At initiate, if the invoice has a non-SUCCEEDED payment younger than that window, call `getStatus` first and refuse with 409 if
    it may still capture.
  - Add tests: lost webhook plus late capture, then reconcile gives SUCCEEDED; and a second initiate after EXPIRED while the gateway
    still reports pending gives 409.

### D3. OTP brute force: the wrong-attempt counter can be rolled back, and concurrent confirms race
- **Location:** backend.md:88 (R3 "5 wrong attempts → LOCKED"), :136 (E4 422), :178 (only the *audit* row is "independent tx");
  openapi.yaml:138-144.
- **Problem:** A wrong code returns 422 (a `business-rule` exception). The design makes only the `CONTACT_VERIFICATION_FAILED` audit
  row survive the rollback. The `attempts` increment sits in the business transaction, so the usual throw-to-422 pattern rolls it back.
  Parallel confirms also read the same `attempts` value.

  Either way the 5-attempt lock doesn't hold. A 6-digit code with a 10-minute TTL and 5 starts per hour can then be brute-forced,
  which lets an attacker attach a contact they don't own (NFR-SEC, CUS-02.2).
- **Fix:** Specify an atomic, committed increment:
  `UPDATE contact_verifications SET attempts = attempts + 1, status = IF(attempts + 1 >= :max, 'LOCKED', status) WHERE id=:id AND status='PENDING' AND attempts < :max`.
  Run it in `REQUIRES_NEW`, or commit before returning 422. Compare codes in constant time (`MessageDigest.isEqual`) against the HMAC.
  Add tests: after 5 wrong codes sent as separate requests, the correct code gets 422 `locked`; N parallel wrong confirms leave
  `attempts` at N.

### D4. The module dependencies form a `customer` ↔ `catalog` cycle, which the existing `ArchitectureTest` rejects
- **Location:** backend.md:161-162 (§6), :90-93 (R5-R8), status.md:26-28 (B6-B8);
  `backend/src/test/java/.../ArchitectureTest.java:54-55` (`slices().matching(ROOT + ".(*)..").should().beFreeOfCycles()`).
- **Problem:** The dependencies run both ways:
  - `customer` → `catalog`: `SubscriptionController` (E6) and `PlanChangeService` (R6 "fresh quote") call `CatalogService`.
  - `catalog` → `customer`: `CatalogService` needs the subscription's current plan and status (R5). `PromotionService` needs the
    account's ACTIVE subscriptions and `AccountLookup` (R7/R8).

  The design can't be built as written without failing an approved test or improvising (CLAUDE.md workflow rule 4).
- **Fix:** Make the direction one-way in the design. Either:
  - `catalog` exposes only plan and promotion data, and quotes plus promotion eligibility live in `customer`; or
  - `customer` publishes a small read port in `common` (e.g. `common.customer.SubscriptionView`) that `catalog` depends on.

  Name the chosen classes in §6.

## Medium

### D5. Payment initiation: transaction boundaries and the gateway timeout outcome aren't specified
- **Location:** backend.md:97, :107/:112, :192, :203-207.
- **Problem:**
  - The sequence diagram inserts PENDING and then calls `createCheckout`, but doesn't say whether these run in one transaction.
  - In one transaction, the HTTP call (up to 5 s) runs while the row and the UK lock are held. On 503 the "payment FAILED" row would
    also roll back, yet `PaymentInitiateIT` expects it to persist.
  - `mode=TIMEOUT` maps to FAILED, but a real gateway may already have created the session. FAILED has no path to SUCCEEDED, and
    `getStatus(sessionId)` can't be called because no session id was stored.
  - The diagram shows `[*] → FAILED`, which contradicts the insert-first sequence.
- **Fix:** Spell out three steps:
  1. tx1 inserts PENDING and commits;
  2. call the gateway outside any transaction, enforcing the timeout on the client side;
  3. tx2 either stores `session_id`/`redirectUrl`, or sets FAILED plus a `PAYMENT_INITIATED` FAILURE audit row.

  Then the controller returns 503, and idempotency (01 §6.1) deletes the key. On timeout, keep the payment PENDING with
  `failure_reason='gateway_timeout'` and reconcile by payment reference: add `getStatusByReference` to the port.

### D6. The idempotency request hash ignores path variables, so a replay can return another resource's result
- **Location:** backend.md:139 (E7 `{subscriptionId}`), :141 (E9 `{promotionCode}`); 01-foundation backend §6.1 step 3 (hash = the
  request DTO only).
- **Problem:** A client that reuses an `Idempotency-Key` for subscription B with the same body it used for subscription A gets A's
  stored 201 replayed. That is silent data confusion instead of `422 idempotency-mismatch` (NFR-REL).
- **Fix:** In this design, require that the canonical request for E7/E9 includes the path variables (e.g. hash
  `{subscriptionId, body}`), or make `operation` include the path id. Add a replay test with a different path id.

### D7. Test data loads in the wrong order: `R__test_customer.sql` runs before `R__test_users.sql`
- **Location:** backend.md:78; status.md:21-22.
- **Problem:** Flyway applies repeatable migrations in description order, and "test customer" sorts before "test users". On a fresh
  Testcontainers DB, the customer script runs before the users it must link (`users.customer_id`) and before the "CUSTOMER user with
  no linked customer". The dev seed is fine (`R__01_…` < `R__02_…`).
- **Fix:** Rename the file so it sorts after the users script (e.g. `R__test_users_02_customer.sql`, or prefix both with numbers),
  and say in §2 which script sets `users.customer_id`.

### D8. CUS-06.3 "optional attachment" is deferred, but the traceability row shows CUS-06 fully designed
- **Location:** backend.md:17, :243 (OQ 4); traceability.md (CUS-06 row, `Designed`).
- **Problem:** CUS-06.3 is an acceptance criterion. The default drops it without the user explicitly accepting the gap, and
  traceability hides the gap.
- **Fix:** Mark CUS-06 as "Designed (partial: CUS-06.3 attachment → slice 03/04)" and ask the user to accept OQ 4 explicitly at
  approval. Alternatively, design a bounded attachment store now.

### D9. Severity/SLA resolution is ambiguous, and the §12 complaint fields are missing from `cases`
- **Location:** backend.md:63-65, :95 (R10).
- **Problem:**
  - It isn't said whether `cases.category_id` holds the subcategory or its parent.
  - It isn't said which level's `default_severity` and `sla_policies` row applies, or what happens when there is no active SLA row
    (500? 422?).
  - §12 asks to "distinguish ordinary service requests from formal complaints" and to capture the escalation route and SLA deadline.
    `sla_policies` stores `response_minutes`, but `cases` has only `sla_due_at` (no response due time and no request/complaint type).

  V5 is created here and can't be edited later.
- **Fix:**
  - Store the leaf category.
  - Resolve severity and SLA leaf-first, then parent.
  - With no active SLA row, fail with 422 `business-rule` and add a test for it, or require a row by seed check.
  - Add `case_type` (`REQUEST|COMPLAINT`, default REQUEST) and `response_due_at` to `cases` in V5, or record the deferral as an open
    question.

### D10. The public webhook writes an audit row for every bad-signature request and has no rate limit
- **Location:** backend.md:186, :194; openapi.yaml:478-507.
- **Problem:** An unauthenticated caller can force one `audit_events` insert per request. 01 avoided this kind of write amplification
  (429s aren't audited, and denials are sampled by `DeniedAccessRecorder`). The design also doesn't say that the signature is checked
  on raw bytes *before* JSON parsing, so malformed unauthenticated bodies get 400 `validation` detail.
- **Fix:**
  - Sample or throttle `PAYMENT_WEBHOOK_REJECTED` for `bad_signature`/`stale` (e.g. one per IP per minute plus a WARN log).
  - Add an IP rate limit (Bucket4j, as for login).
  - State the order: read raw body, verify the signature, then parse. A bad signature returns 401 before any 400.

### D11. The mock checkout endpoints have no security design
- **Location:** backend.md:124 ("SecurityConfig adds exactly that path"), :152, :195.
- **Problem:** `GET/POST /mock-gateway/checkout/{sessionId}` is an HTML form POST outside `/api/v1`. With the 01 `SecurityConfig` it
  gets CSRF 403 or the 401 entry point, and nothing says whether it is public or session-bound. The builder would have to improvise.
  Also, anyone holding a `sessionId` can mark another customer's payment paid or failed in dev/test.
- **Fix:** List the path explicitly (dev/test profiles only) with the CSRF approach: a server-rendered token, or exempt plus an
  unguessable session id. Also give the base URL the mock uses for its self-call to E18.

### D12. OQ 10's default invents a billing rule, and plan changes have no provider/provisioning seam
- **Location:** backend.md:90-91 (R5/R6), :249 (OQ 10).
- **Problem:**
  - CUS-03.5 requires displaying "applicable charges", and §11 lists effective dates and proration as key concerns. Charge = 0.00
    with no proration is a business decision that the BRD leaves open, not a neutral default.
  - `PlanChangeApplyJob` updates `plan_id` locally. BR-08 says provisioning actions must be confirmed by backend/provider systems.
    With no port, a real billing/provisioning integration later means a redesign.
- **Fix:** Ask the user to decide OQ 10 explicitly at approval. Put plan application behind a port (e.g. `PlanProvisioning.apply`)
  with a mock adapter and a failure mode, as the design already does for `PaymentGateway`.

### D13. The webhook state machine is incomplete
- **Location:** backend.md:98, :105-114, :194; openapi.yaml:866-879.
- **Problem:** The design doesn't cover:
  - out-of-order events (`payment.failed` arriving after SUCCEEDED, or `payment.succeeded` after FAILED);
  - a check that the event's `sessionId` equals `payments.gateway_session_id`;
  - a SUCCEEDED event without `gatewayTransactionId`/`method`, although both are nullable in the event and required in `Receipt`
    (E17 would 500);
  - an amount mismatch on a real capture. The event is REJECTED, the payment stays PENDING, and the job may later EXPIRE it while
    the money was taken.
- **Fix:**
  - Add a transition table listing the allowed from-states for each event type; anything else is IGNORED plus audit.
  - Match `sessionId`.
  - Require `gatewayTransactionId` and `method` for `payment.succeeded`.
  - On an amount mismatch, flag the payment for manual review (e.g. `failure_reason='amount_mismatch'`, kept out of auto-expiry).

## Low

### D14. Task list ordering and file boundaries
- **Location:** status.md:24-27.
- **Problem:**
  - B6 doesn't create `SubscriptionService` (listed in §6), yet E5 needs `pendingPlanChange`, whose repository only arrives in B7.
  - B5 is 6+ files (sender port + mock + config properties).
  - B4 omits `AccountRepository`, the `AccountLookup` implementation and an OTP/`@ConfigurationProperties` class.
- **Fix:** Add `SubscriptionService` and a read method on `PlanChangeRepository` to B6 (or put E5's pending change in B7). Split B5
  into port+mock and service+controller. Add `AccountRepository` to B3 or B4.

### D15. backend.md and openapi.yaml don't agree
- **Location:** backend.md:135 vs openapi.yaml:106; openapi.yaml:608, :614-624.
- **Problem:**
  - E3 has 503 in openapi (and §8 :196) but not in the §5 table.
  - `ProfileUpdate` doesn't distinguish an *absent* `aliasName` from `null` (clear), which is a risk in PATCH semantics.
  - `Profile.secondaryMsisdn` is a plain string, not `Msisdn`.
- **Fix:** Add 503 to E3. Make `aliasName` required (nullable) in `ProfileUpdate`. Use `oneOf [Msisdn, null]`.

### D16. The E12 `Location` header points at a list query, not a resource
- **Location:** openapi.yaml:340-341.
- **Problem:** `/api/v1/customers/me/cases?reference=…` isn't a resource URI.
- **Fix:** Drop the `Location` header until case detail exists (slice 04), or document it as a search link.

### D17. Account and customer status aren't used in any rule
- **Location:** backend.md:57-59, :86-97.
- **Problem:** Nothing says what an `INACTIVE` customer or a `SUSPENDED`/`CLOSED` account can do: create cases, claim promotions,
  initiate payments, change plans.
- **Fix:** Add a rule (e.g. reads allowed; on a CLOSED account, mutations get 422) and test it.

### D18. Schema details
- **Location:** backend.md:58-69.
- **Problem:**
  - `accounts.primary_msisdn` duplicates the primary subscription's `msisdn`, so the two can diverge.
  - The `UK msisdn` on `subscriptions` blocks number recycling for TERMINATED rows.
  - There are no CHECKs on money (`amount > 0`, `amount_paid >= 0`, `amount_due >= 0`) or on `attempts`.
  - `payments.gateway_session_id` has no index or UK.
  - `promotion_claims` doesn't say it has `created_at`, which `claimedAt` needs.
- **Fix:** Derive the primary MSISDN from the primary subscription, or add a consistency check. Add the CHECKs, the UK on
  `gateway_session_id`, and the timestamps.

### D19. `PlanChangeApplyJob` rules are thin
- **Location:** backend.md:90-91, :61.
- **Problem:**
  - The update isn't conditional on `status='SCHEDULED'`, the subscription still being ACTIVE, or `from_plan_id` still being current.
  - `CANCELLED` is never produced.
  - The "next billing-cycle day" isn't defined when today is the cycle day.
- **Fix:** Specify a guarded update, and set CANCELLED (with audit) when the guard fails. Define the effective date as strictly
  after today.

### D20. Traceability gaps
- **Location:** traceability.md:34-53 (slice 02 block).
- **Problem:**
  - The legend defines `Designed` as "design approved", yet the rows say `Designed` while the design is Proposed.
  - There are no slice 02 rows or refs for BR-01, BR-10, XC-04, XC-05, XC-06, XC-07, NFR-REL and NFR-SEC, although the design header
    claims them.
- **Fix:** Use a `Proposed` status (or leave Status empty) until approval, and add the missing rows.

### D21. Process: the design branch isn't cut from fresh `main`, and the 00-overview additions aren't written into 00-overview
- **Location:** git: `git log origin/main..HEAD` shows `0203322 docs(foundation): mark slice 01 frontend merged`, which changes only
  `docs/slices/01-foundation/status.md`. Also backend.md:20-21.
- **Problem:**
  - This breaks CLAUDE.md git rule 3 (one branch per step from fresh `main`), and the design PR would carry an unrelated commit.
  - The module-ownership changes are stated as "approve with this design" but not applied to the approved `00-overview.md` §1.
- **Fix:** Merge `0203322` separately or rebase it out. Edit `00-overview.md` §1 in the same design PR.

### D22. A promotion claim stores no evidence of terms acceptance
- **Location:** openapi.yaml:722-726; backend.md:62, :181.
- **Problem:** CUS-04.3 and XC-06 confirmation are FE-only, and the claim row and audit don't record which terms were accepted.
- **Fix:** Add `acceptTerms: true` (required) and store a terms version or hash in `promotion_claims` and the audit details.

### D23. The OTP channel default deviates from §14 #12 in dev
- **Location:** backend.md:196, :250 (OQ 11).
- **Problem:** §14 #12 adopts email via Mailpit in dev, but the default is an in-memory sender.
- **Fix:** Acceptable as a stated deferral. Note it as a §14 #12 deviation so slice 03/04 swaps in the adapter.

## Review disposition (ba-planner, design rev 2, 2026-11-25)

Section refs are to `docs/design/02-customer/backend.md` unless named. OA = `openapi.yaml`.

| # | Sev | Disposition | Where |
|---|---|---|---|
| D1 | High | Fixed: every payment transition is a guarded `UPDATE … WHERE status IN (:from)`; credit + audit only when 1 row changed, else `IGNORED`; single writer `PaymentTransitionService`; concurrency tests | §3.1, R13, R14, §6, §9 `PaymentWebhookIT`/`PaymentReconciliationJobIT`; status B16 |
| D2 | High | Fixed: EXPIRED/FAILED only when the gateway reports it; unknown → stays PENDING, after 24 h → `REVIEW` (never auto-EXPIRED); 24 h late-capture watch on FAILED/EXPIRED; open-payment UK covers PENDING + REVIEW; initiate re-checks recent FAILED/EXPIRED and returns 409 | §2 payments, §3.1, R12, §9; OA `PaymentStatus` |
| D3 | High | Fixed: attempt counted in a committed `REQUIRES_NEW` guarded update before a constant-time compare; LOCK and VERIFIED are guarded updates; sequential and parallel tests | §3.2, §9 `ContactVerificationIT`; OA E4 description |
| D4 | High | Fixed: one-way `customer → catalog`; quotes, eligibility and claims moved to `customer` (`PlanQuoteService`, `PromotionEligibilityService`, `PromotionClaimRepository`); `catalog` read-only | §1 deps, §6, §9 `ArchitectureTest`; 00-overview §1 rev 3; status B7–B11 |
| D5 | Med | Fixed: tx1 insert PENDING + commit → gateway call outside tx (5 s) → tx2 session or FAILED; timeout keeps PENDING `gateway_timeout`; payment ref is the merchant ref; `getStatus(paymentRef)` | §3.1, §8 table + sequence |
| D6 | Med | Fixed: idempotent endpoints hash `{pathVariables, body}`; other-path replay → 422 test | §5 intro, §9; OA info + E7/E9 |
| D7 | Med | Fixed: test data renamed `R__test_users_02_customer.sql`; both scripts state they set `users.customer_id` | §2 scripts table; status B1/B2 |
| D8 | Med | Fixed: CUS-06 marked partial, CUS-06.3 row `Deferred`; OQ 4 moved to "needs the user's decision" | traceability; §1, §10 |
| D9 | Med | Fixed: `cases.category_id` = leaf; severity/SLA leaf-first then parent; no SLA row → 422 + seed check; `case_type` (from category) and `response_due_at` added. Escalation route deferred (OQ 13) | §2, R10, §9 |
| D10 | Med | Fixed: IP rate limit (429), signature on raw bytes before parsing (401 before 400), `bad_signature`/`stale` audit sampled 1/IP/min + WARN | §7, §8, §6 `WebhookRateLimiter`; OA E18 |
| D11 | Med | Fixed: `/mock-gateway/checkout/**` only when mock enabled (dev/test), permitAll + CSRF-exempt, 128-bit random session id; self-call URL `app.payment.mock.webhook-url`; residual risk accepted for a dev-only mock | §4, §6 config, §8 |
| D12 | Med | Fixed: `PlanProvisioning` port + mock + failure mode; OQ 10 moved to "needs the user's decision" (default unchanged) | R6, §8, §10 |
| D13 | Med | Fixed: transition table with allowed from-states; session id match; `payment.succeeded` requires txn id + method (400); amount/session mismatch → `REVIEW`, out of auto-expiry | §3.1, §8; OA `GatewayWebhookEvent` if/then |
| D14 | Low | Fixed: tasks re-cut (21 tasks, ≤ ~5 files), `SubscriptionService` + pending-change read in B8, ports split (B5), `AccountRepository` in B3 | status.md |
| D15 | Low | Fixed: E3 503 in §5; `aliasName` required + nullable; `secondaryMsisdn` = `Msisdn` or null | §5; OA |
| D16 | Low | Fixed: `Location` removed from E12 until case detail (04) | §5; OA |
| D17 | Low | Fixed: R16 (reads allowed; INACTIVE/CLOSED → mutations 422; SUSPENDED → plan change/claim 422), as OQ 12 for the user | R16, §10, §9 |
| D18 | Low | Fixed: primary MSISDN derived (column dropped); live-MSISDN UK; money/attempt CHECKs; UK `gateway_session_id`; `created_at` on every table | §2 |
| D19 | Low | Fixed: guarded apply (`plan_id=:from`, ACTIVE), CANCELLED + audit on guard failure, effective date strictly after today | R5, R6, §7 |
| D20 | Low | Fixed: status `Proposed` until approval; BR-01, BR-10, XC-04..07, NFR-REL, NFR-SEC rows added | traceability |
| D21 | Low | 00-overview part Fixed (§1 rev 3, marked proposed). Commit `0203322` part **Accepted** per coordinator: intentional slice 01 status housekeeping | 00-overview §1 |
| D22 | Low | Fixed: claim requires `acceptTerms=true` + `termsHash`; `terms_hash` stored and audited | §2, R8, §7; OA |
| D23 | Low | **Accepted** as a stated deviation from §14 #12: in-memory OTP sender until the notification adapter (03/04) | §8, OQ 11 |
