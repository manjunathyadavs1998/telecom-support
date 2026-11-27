# 03 Inbound assistant workspace — frontend design

- Status: **Proposed** (awaiting approval), revision 2 (review F1–F10 addressed) · Date: 2026-11-27
- BRD: INB-01..INB-04, BR-04, BR-05/06, BR-10, XC-02..XC-05, XC-07, XC-08, NFR-PRIV, NFR-REL, NFR-SEC, §14 #4
- Backend contract: [`openapi.yaml`](openapi.yaml) I1–I15, [`backend.md`](backend.md) (Approved rev 2; §11 errata E1/E2 add
  audit/limit behaviour only, no API change)
- Precedent: [`../02-customer/frontend.md`](../02-customer/frontend.md) (Approved + §11 FE1) and [`../01-foundation/frontend.md`](../01-foundation/frontend.md):
  route shape, 403 rule, states, `DataTable`, `DateRangeFilter`, `Pagination`, `useListParams`, `useIdempotencyKey`, `useSubmitGuard`,
  `SectionErrorState`, `StatusBadge`/`statusLabel.ts`, `formatInr`/`formatDateTime`, §6 common error row. None is restated here.
- Review: [`../../slices/03-inbound/review-frontend-design.md`](../../slices/03-inbound/review-frontend-design.md) (disposition at its end) · Visual: `docs/brd/images/inb-workspace.png`
- Process: tests are **deferred** by the user (§7). Tasks are verified by typecheck, lint and build only.

## 1. Scope
**In:** the `/inbound` customer search, the identity check (OTP or KBA), the verified workspace (overview, billing, cases list + detail,
raise request on behalf, reactivation), the verification countdown and re-verify flow, generated 03 API types, and the label/format additions.
**Out:** attachments (backend OQ 5), case updates or notes on existing cases (04), promotions for agents (backend OQ 12), an ops UI for
`needsAttention` (06), ending a verification early (no endpoint, OQ 5), and every test (deferred, §7).

Layout: `src/features/inbound/{search,verification,workspace,billing,cases,reactivation}/` (`queries.ts` per folder, as 02) ·
`src/stores/inboundSessionStore.ts` (lookup tokens only, §5). Only INBOUND_AGENT holds `customer.lookup`, so every route below is a `ForbiddenPage` for any other role (XC-02).

## 2. Routes and navigation
The existing nav item `inbound-search` (`/inbound`, `customer.lookup`) gets `children` (02 §2 route shape: guard before the lazy page; a child
whose path extends another nests under it and inherits its gate and crumb). No new sidebar entries. `cases/new` ranks above `cases/:reference`.
**Layout route (F8):** `inbound-customer` is a layout route: its element is guard → lazy `WorkspaceFrame` → `<Outlet/>`, and Overview
is its index child, so the frame (verify step, cooldowns, dialog, countdown) survives tab navigation. `NavChild` gains `layout?: string`
(id in `pageRegistry`'s new `LAYOUT_REGISTRY`), read by `routes.tsx`.

| Route (child id) | Screen | Required (all of; + parent's) | Crumb | BRD |
|---|---|---|---|---|
| `/inbound` (`inbound-search`) | Customer search | `customer.lookup` | Customer search | INB-01.1 |
| `/inbound/customers/:customerId` (`inbound-customer`) | Verify, then Overview | `customer.verify` (overview sections: `customer.profile.read`, `plan.read`, `payment.read`, `case.read` via `Can`) | Customer | INB-01.2/01.3 |
| `…/:customerId/billing` (`inbound-billing`) | Invoices + payments | `payment.read` | Billing | INB-01.3 |
| `…/:customerId/cases` (`inbound-cases`) | Case list | `case.read` | Cases | INB-02.1 |
| `…/:customerId/cases/new` (`inbound-case-new`) | Raise request | `case.create.onbehalf` + `customer.profile.read` + `plan.read` | New request | INB-03 |
| `…/:customerId/cases/:reference` (`inbound-case`) | Case detail | — (inherits `case.read`) | `SR-…` | INB-02.2–02.4 |
| `…/:customerId/reactivation` (`inbound-reactivation`) | Reactivation | `customer.profile.read` (eligibility + create: `service.reactivate` via `Can`) | Reactivation | INB-04 |

- `:customerId` must match `^\d{1,18}$` and `:reference` `^SR-\d{8}-\d{6,}$`, else `NotFoundPage` with no API call (`useInboundParams()`).
- The MSISDN never appears in a URL, query key, crumb or `document.title` (NFR-PRIV). Titles: "Customer search", "Customer workspace", "Request SR-…".

## 3. Screens
Every screen follows 02 §3 states (skeleton · `EmptyState` · `ErrorState` with correlation id · validation · success) and the 403 rule, with one
addition: **a 403 whose `type` ends in `verification-required` is never a `ForbiddenPage`.** It triggers the re-verify flow (§3.3).
XC-08: dialogs trap focus and close on `Esc`; tables become cards below `md`; badges always carry text; countdowns update silently
(only the 5-minute warning and expiry are announced); tabs and the status multi-select are keyboard-operable.

### 3.1 Customer search `/inbound` (INB-01.1, INB-01.4)
- Mobile number form (§6) → I1 `POST {msisdn}`. The result card shows masked name, masked number, customer ref, customer/account/line
  status badges, "Verified · N min left" if `verifiedUntil`, and [Verify identity] or [Open workspace]. `[]` → `EmptyState` "No active line found for this number" (also TERMINATED, R1). One match → the card.
- On a hit, `rememberMatch(match)` (§4): the match without its token goes to the Query cache (`inboundKeys.match(id)`), the token to
  `inboundSessionStore` (§5). The button navigates to `/inbound/customers/{customerId}`. The mutation itself uses `gcTime: 0`.
- 429 → root "Too many searches. Try again in N s" (countdown from `Retry-After`, Search disabled). 400 → field error.
- Masking is server-side. The UI never shows or stores more than `CustomerMatch` before verification (NFR-PRIV).

### 3.2 Identity check — `VerifyPanel` (INB-01.2)
Shown in place of the workspace when I4 `current` returns 404 (first visit), and inside `ReverifyDialog` (§3.3). Header from the cached
match (masked name, masked number, ref). **No token, or older than 15 min** (reload, long call) → the panel starts with its own **Search
step** (`CustomerSearchForm`, the §3.1 form): I1 → the hit's `customerId` must equal `:customerId`, else "This number belongs to a different
customer" (nothing stored); `[]` → "No active line found". A matching hit → `rememberMatch` → Method step, in place, no navigation (F1).

| Step | UI | API | Errors (§6 maps the rest) |
|---|---|---|---|
| Method | `RadioGroup`: "Send a code to the customer's primary mobile" (default) · "Ask security questions" | — | — |
| OTP send | [Send code] | I2 `{method: OTP, lookupToken}` → 201 `{id, maskedDestination, expiresAt}` | 404 → back to the Search step, "The search expired. Enter the number again." · 422 `no_otp_destination` → switch to KBA + notice · 429 → "Too many verification attempts for this customer. Try again in N min." · 503 → "We couldn't send the code. Try again or use security questions." |
| OTP confirm | "Code sent to +91******0001 · expires in mm:ss"; 6-digit input; [Verify]; "Send a new code" (30 s cooldown, as 02) | I3 `{code}` → 200 `IdentityCheck` VERIFIED | 422 `wrong_code` + `attemptsRemaining` > 0 → field "Incorrect code. N attempts left." · `expired`/`locked` → root `detail`, input disabled, "Send a new code" · 404 → back to Method |
| KBA | "Billing postal code" + "Registered email or second mobile" (both required) [Verify] | I2 `{method: KBA, lookupToken, postalCode, registeredContact}` → 201 VERIFIED | 422 `kba_mismatch` → root "The answers don't match our records." (never which one) + "N attempts left this hour" when `attemptsRemaining` is present (F10) · 404 as OTP send · `kba_unavailable` → switch to OTP + notice · 429 as above |

- Success → `setQueryData(checkKey, check)`; the workspace renders, focus moves to its `<h1>`, toast "Customer verified for 30 minutes".
- Codes and answers: `autocomplete="off"` (code: `one-time-code`), mutations `gcTime: 0`, never in toasts, logs or keys (NFR-SEC).

### 3.3 Workspace frame and verification lifetime (INB-01.2, INB-01.3)
`WorkspaceFrame` is the layout route element (§2) for every page under `/inbound/customers/:customerId`: header (name, customer/account refs, customer and account
status, suspension reason + since, `VerifiedBadge`, [New search]), tab links Overview · Billing · Cases · Reactivation, [Raise request]
(`Can case.create.onbehalf`), then `<Outlet/>`.
- It calls I4 first. Protected queries (customer-scoped I5–I15) are `enabled` only while the cached check is VERIFIED, so a first visit
  fires no 403s. The header uses I5 once verified (cached match before).
- **Server time (F6):** the I2/I3/I4 query functions read the response `Date` header and store `serverOffsetMs` (server − client, 0 if
  absent) with the check; `serverNow()` = `Date.now() + offset`. `VerifiedBadge` counts down to `validUntil` against `serverNow()` in whole
  minutes; warning tone and one polite announcement at 5 min left. The check is not extended (backend R5); "Verify again" is offered from
  5 min. At 0 the frame **refetches I4 once**: 404 → verification lost; 200 → new baseline (no loop). The server 403 stays the authority.
- **Verification lost** = I4 404 after the countdown, **or** any inbound query/mutation gets 403 `verification-required`. Then
  `markVerificationLost(customerId)`: set the check to `null`, `removeQueries` for every protected key of that customer (no stale PII), open
  `ReverifyDialog` (modal, focus trapped, the page stays mounted so forms keep their input). Success → protected queries refetch, the dialog
  closes, focus returns to the trigger, and a pending form shows "Verified again. Submit when ready." (no automatic resubmit). The token is
  always older than 15 min by then, so the dialog normally opens on the Search step (§3.2): the agent re-enters the number and verifies
  in place; form input is kept (F1). Cancel → `/inbound` (warns first if a form is dirty).
- Tabs are `NavLink`s with `aria-current="page"`; below `md` they scroll horizontally. "New search" → `/inbound` (the server check stays
  valid until it expires, OQ 5).

### 3.4 Overview `/inbound/customers/:customerId` (INB-01.3)
Four independent sections (each own query, own states, `Can` its permission; a failing one never blanks the page):

| Section | Source | Shows |
|---|---|---|
| Profile | I5 | full name, alias, customer/account refs, customer + account status, suspension reason + since, primary mobile, email and second mobile each with "Verified" / "Not verified" (text). No billing address (R7) |
| Lines and plans | I6 | per subscription: MSISDN, "Primary", status badge, plan name, `₹/month`, key benefits, billing day, pending plan change. Read-only |
| Open bills | I7 `status=OPEN` | invoice ref, due date, outstanding (`formatInr`); "View billing" |
| Recent requests | I9 `size=5` (default window) | reference (link to detail), category, severity, status, updated; "View all" |

### 3.5 Billing `…/billing` (INB-01.3, BR-05/06)
- Invoices (I7, latest 12, no filter): Invoice · Period · Issued · Due · Amount · Paid · Outstanding · Status. Empty "No invoices yet".
- Payments (I8): 02 Payment history rules (`DateRangeFilter`, 3-month default, URL params, `Pagination` 20, sort `initiatedAt,desc`) with
  columns Reference · Invoice · Date/time (IST) · Amount · Method · Status. No receipt, pay or retry actions (R7).

### 3.6 Cases `…/cases` (INB-02.1, XC-04/05)
- Toolbar: `DateRangeFilter` (3-month default) · reference search (02 schema) · **Status** multi-select (8 internal statuses, `CaseStatus`) ·
  Sort (`Created newest` default, `Created oldest`, `Updated newest`, `Updated oldest` = the I9 whitelist). All in the URL (`useListParams`
  extended, §4); filter change resets the page.
- `DataTable`: Reference (link) · Category › subcategory · Subject · Severity · Status (internal label; public label as secondary text
  "Customer sees: In progress") · Owner (or "Unassigned") · Channel ("Portal" / "Inbound") · Created · Updated (IST).
- Empty: "No requests in the last 3 months" / "…in these dates" + "Change dates" + "Raise request" (`Can case.create.onbehalf`).

### 3.7 Case detail `…/cases/:reference` (INB-02.2–02.4, BR-04)
- Summary (INB-02.1 fields) + description, preferred contact, contact number, created by (name + channel), SLA due (if present).
- **Activity** timeline (`<ol>`, oldest first as served): time, actor, `CREATED` / `STATUS_CHANGED old → new` / `ASSIGNED` / `NOTE`. Each
  entry carries a text badge: "Visible to customer" (PUBLIC) or **"Internal — never shown to the customer"** (INTERNAL, distinct background
  + lock icon, not colour only). The UI shows only what I10 returns (BR-04 is server-side).
- **Possible duplicates** (INB-02.3): ≤ 5 rows (reference link, subject, status, created) with the note "Same category within 30 days. For
  information only." Empty → "No related requests found".
- 404 → `ErrorState` "Request not found for this customer" + back to Cases.

### 3.8 Raise request `…/cases/new` (INB-03)
- Read-only block (INB-03.1): customer name, account ref (I5). Contact number = the chosen line's MSISDN (shown, never sent).
- Fields (§6): **Category** `Select` from I11, two groups "Customer categories" / "Internal categories" (internal options carry "(Internal)"
  in their text; only present if the server returned them, INB-03.2) → **Subcategory** when the category has any · **Line** (I6 lines
  not TERMINATED, primary preselected; with one line it's shown read-only) · **Issue summary** · **Customer statement** · **Agent notes**
  (hint "Internal note: never shown to the customer") · **Preferred contact** Phone / SMS / Email (Email disabled unless `emailVerified`).
- **Severity** (§14 #4): text "Priority: High (from the category)" = subcategory `defaultSeverity` ?? category's (if both are null:
  "set automatically"). With `Can case.severity.override`: checkbox "Override priority" reveals Severity `Select` + **Reason** (required).
  Without override the body sends `severity: null`. If the derived value is unknown (both null), any chosen severity counts as an override
  (reason required) (F7).
- Submit: `useSubmitGuard` + ``useIdempotencyKey('CASE_CREATE_ONBEHALF', `/agent/customers/${customerId}/cases`)`` (the concrete path,
  so a key never crosses customers); mutation `meta: OWN_FORBIDDEN_HANDLING`. A 403 `verification-required` **keeps the key**, so the
  resubmit after re-verify replays the same request (F4). 201 → confirmation (`role="status"`, focus to heading): **"Request SR-… created. Read this reference to
  the customer."** + [Copy reference] (announces "Copied"), severity, status, created (IST), contact number, account ref, channel
  "Inbound agent" (INB-03.4/03.5); "View request" / "Raise another" (reset form and key). Invalidates the customer's case list keys.
- No attachment field (backend OQ 5).

### 3.9 Reactivation `…/reactivation` (INB-04)
**Eligibility** (`Can service.reactivate`; I13): suspension reason (label), suspended numbers, maximum hours.
- Not eligible → `Alert` listing each reason in plain words (§4 `eligibilityText`); `PROHIBITED_REASON` → "Suspended for {Fraud/Legal/
  Regulatory}. This can never be overridden." (danger tone, no form); `OPEN_REQUEST` → "A restoration is already open: {openRequestReference}"
  (scrolls to it in History). No form is rendered (INB-04.1/04.2/04.6).
- Eligible → form (§6): Hours (1..`maxHours`, default `maxHours`, sent only if changed) · Reason (hint "Explain why. Don't include personal
  data.") · Approval reference (optional). [Request restoration] → `AlertDialog` "Restore {n} number(s) for {h} hours? Service is suspended
  again automatically when the time ends." → I14 with ``useIdempotencyKey('REACTIVATION_CREATE', `/agent/customers/${customerId}/reactivations`)``,
  `useSubmitGuard`, `meta: OWN_FORBIDDEN_HANDLING`; 403 `verification-required` keeps the key (as §3.8). **Without `service.reactivate`:** the section shows "Only reactivation approvers can
  restore service" and History only.

| I14 result | Shown (nothing claims success before the server does, XC-07) | Then |
|---|---|---|
| 201 `ACTIVE` | success `Alert` "Service restored until {expiresAt IST} ({reference}). The customer will be notified by SMS." (queued, not yet sent, F3) | invalidate eligibility + history; reset form and key |
| 422 with `reasons[]` | `HOURS_EXCEED_POLICY` → field Hours "At most {maxHours} hours"; other reasons → root list (same copy as eligibility) | refetch eligibility |
| 422 with `reference`, status FAILED | root "The provisioning system rejected the request ({reference}). Nothing was restored." | refetch history |
| 503, status FAILED | root "The provisioning system is unavailable. Nothing was restored. Try again later." + Retry | key kept (backend released it) |
| 503, status PENDING | warning "No answer from the provisioning system yet. Request {reference} is pending and is checked automatically. Don't submit again." | form disabled; history polls; when that reference leaves PENDING, eligibility is invalidated and the form re-renders from it (F5) |
| 409 | root "A restoration is already open for this account." | refetch eligibility + history |

**History** (I15, every inbound agent): `DataTable` Reference · Status · Numbers · Hours · Requested by · Requested (IST) · Active from–until
· Ended / outcome; 3-month default window + `DateRangeFilter` + paging (URL). Honest status copy (INB-04.4/04.5, BR-08):

| Status | Badge · detail |
|---|---|
| PENDING | "Waiting for provider" · "Restoration not confirmed yet; checked automatically" |
| ACTIVE | "Restored" · "Until {expiresAt}" |
| EXPIRING | "Ending" · "Time is up; suspension is being confirmed with the provider" |
| EXPIRED / ENDED | "Expired" / "Ended" · `endReason`: `expired` "Time limit reached", `account_reinstated` "Account reinstated", `policy_prohibited` "Ended early by policy" |
| FAILED | "Failed" · `failureReason`: `provider_rejected` "Rejected by provider", `provider_unavailable` "Provider unavailable", `not_created`/`not_restored` "Not restored" |

`needsAttention` adds a warning badge "Needs attention: operations alerted; automatic checks continue". While page 1 holds a PENDING or
EXPIRING row, History refetches every 15 s (OQ 3) and on window focus.

## 4. Shared components and helpers introduced or extended
| Component / helper | API | Used by |
|---|---|---|
| `WorkspaceFrame` (layout route) | reads `:customerId`; I4 gate, header, tabs, `VerifiedBadge`, `ReverifyDialog`, `<Outlet/>`; exposes `useVerifiedCustomer()` → `{customerId, verified, serverNow}` | every workspace page |
| `VerifyPanel` / `ReverifyDialog` | `{customerId, onVerified}`: Search step (if no fresh token) → Method → OTP/KBA (§3.2) / `{open, customerId, onCancel}` | frame |
| `CustomerSearchForm` | `{onHit(match), expectCustomerId?}`: §3.1 form + I1; with `expectCustomerId` a different id is an error | search page, VerifyPanel |
| `rememberMatch(qc, match)`, `useCustomerMatch(id)` | match minus token → `setQueryData(inboundKeys.match(id))` (`staleTime: Infinity`, `gcTime` 15 min, no `queryFn`); token → store | 3.1, 3.2 |
| `VerifiedBadge` | `{validUntil, serverNow, onElapsed}`, ticks every 15 s, polite announcement at 5 min | frame |
| `isVerificationRequired(e)`, `markVerificationLost(qc, id)` | `ApiError` 403 with `type` ending `verification-required` / §3.3 | all inbound hooks |
| `inboundKeys` | `['inbound','customer',id, 'match'\|'check'\|'profile'\|'subscriptions'\|'invoices'\|['payments',p]\|['cases',p]\|['case',ref]\|'eligibility'\|['reactivations',p]]`, `['inbound','categories']` | queries |
| `useInboundParams()` | validated `customerId`, `reference` or `NotFoundPage` | routes |
| `statusLabel.ts` (extend) | kinds `caseInternal` (Created, Assigned, In progress, Pending customer, Pending external, Resolved, Closed, Reopened), `customer`, `account`, `reactivation` (§3.9) | badges |
| `src/lib/inboundText.ts` | `suspensionReasonText` (Non-payment, Customer request, KYC pending, Fraud, Legal, Regulatory, Other), `eligibilityText` (one sentence per `EligibilityReason`), `endReasonText`, `failureReasonText`; unknown → "Other" | 3.3, 3.9 |
| `useListParams` (extend) | schema adds `status?: readonly string[]` (repeated `?status=`; unknown values dropped + notice) and `sort?: readonly string[]` whitelist | cases |
| `idempotency.ts` (extend) | `IdempotentOperation` += `CASE_CREATE_ONBEHALF`, `REACTIVATION_CREATE` (in-memory, not persisted) | 3.8, 3.9 |
| shadcn added | Tabs exists; adds `Popover` + `Command` only if the status multi-select needs it (else checkbox group) | cases |

## 5. Client state
| State | Where | Why |
|---|---|---|
| All API data | TanStack Query `inboundKeys`; lists `staleTime` 30 s; I4 `staleTime` 0 | server state; removed on verification loss (§3.3) and on logout (01) |
| Search match (masked name/number, refs, statuses) | TanStack Query `inboundKeys.match(id)` via `setQueryData`, `gcTime` 15 min; cleared with the cache on logout | server data stays in Query (CLAUDE.md, F2) |
| `{lookupToken, storedAt}` per customerId | Zustand `inboundSessionStore`, **memory only** (no persist); entries older than 15 min ignored; `clear()` on logout (`useLogout`) | **Recorded exception (F2):** a short-lived capability, not displayable data; kept apart so it never lands in Query devtools/cache dumps; never in storage (NFR-PRIV) |
| List filters, page, sort | URL search params | XC-04 |
| Verify step, check id, cooldowns, override toggle, keys | component state (frame is a layout route, so it survives tab changes) | transient |

No `localStorage`/`sessionStorage` for anything in this slice. Mutations are never optimistic (XC-07).

## 6. Forms and validation
02 §6 common row applies (other status/500/network → `FormRootError` with correlation id; `idempotency-mismatch` → fresh key + "Please try
again"). Any 403 `verification-required` → §3.3; another 403 on an inbound mutation → root "You don't have permission to do that."

| Form | Zod schema fields | Server error mapping |
|---|---|---|
| Search | `msisdn`: trim, strip spaces/dashes; 10 digits starting 6–9 → `+91…`; else E.164 `^\+[1-9]\d{7,14}$` | 400 → field · 429 → root countdown |
| OTP code | `code` `/^\d{6}$/`, `inputMode=numeric` | §3.2 |
| KBA | `postalCode` trim 3–12 · `registeredContact` trim 3–254 (email lower-cased; 10-digit mobile → `+91…`) | §3.2 |
| Raise request | `categoryCode` · `subcategoryCode` iff subcategories · `subscriptionId` (default primary) · `subject` trim 5–150 · `customerStatement` trim 10–4000 · `agentNotes` trim ≤ 4000, `''` → `null` · `preferredContact` (EMAIL only if verified) · `override` bool → `severity` required and ≠ derived (any value when derived is unknown), `severityReason` trim 5–200; else both `null` | 400 → fields · 403 (non-verification) → root "You can't use this category or priority" · 404 → root "That line is no longer on this account" + refetch I6 · 409 → "Still being submitted…" · 422 → root `detail` |
| Reactivation | `hours` int 1..`maxHours` (optional) · `reason` trim 10–500 · `approvalReference` trim ≤ 64, `''` → `null` | §3.9 table · 400 → fields |
| Case list filters | 02 date range + reference; `status` ⊆ `CaseStatus`; `sort` ∈ whitelist | 400 → filter message, last data kept |

Errors link via `aria-describedby`/`aria-invalid`; focus goes to the first invalid field or the root `Alert`. Character counters as 02.

## 7. Deferred tests (user decision: write after development)
| Test (Vitest/RTL, Playwright) | Would prove | BRD |
|---|---|---|
| `inboundRoutes.test.tsx` | CUS/OUT/BUS users → `ForbiddenPage` on every §2 route with no API call; bad `:customerId`/`:reference` → NotFound | XC-02, BR-10 |
| `customerSearch.test.tsx` | 10-digit → `+91`; MSISDN absent from URL/keys; empty, hit, 429 countdown | INB-01.1, NFR-PRIV |
| `verifyPanel.test.tsx` | OTP send/confirm, attempts left, expired/locked, 503, `no_otp_destination` → KBA; KBA mismatch + attempts left/unavailable; no token → Search step; other customer's number refused | INB-01.2 |
| `workspaceFrame.test.tsx` | no 403 before verification; countdown 0 (I4 404) and a 403 `verification-required` → dialog, data removed; re-search + verify in place keeps form input and the idempotency key; skewed clock uses `Date` offset; tab change keeps frame state | INB-01.2, NFR-PRIV |
| `overview.test.tsx`, `billing.test.tsx` | independent sections; no billing address; payments window/paging | INB-01.3, XC-04 |
| `agentCases.test.tsx` | status/sort/date in URL; owner "Unassigned"; INTERNAL badge; duplicates | INB-02 |
| `raiseRequest.test.tsx` | internal group only when served; override needs reason, hidden without permission; double click → one POST; reference + copy | INB-03, §14 #4 |
| `reactivation.test.tsx` | reasons copy; prohibited → no form; each I14 outcome row; history polling only with open rows | INB-04 |
| e2e `inbound.spec.ts` (IN002) | search `+919800000006` → OTP (dev code) → workspace → raise request → restore 2 h → History "Restored" | INB-01..04 |

## 8. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
| 1 | Expiry mid-task: block with a modal or swap the page? | Modal `ReverifyDialog` over the mounted page with its own number search (the 15-min token has always lapsed); data removed, form input and key kept, no auto-resubmit |
| 2 | Keep the lookup token across a reload? | No: memory only; re-verification always needs a fresh search (in place); a still-valid check (I4) needs none |
| 3 | Reactivation history refresh | Every 15 s while page 1 has PENDING/EXPIRING, plus on focus |
| 4 | Show internal and customer-facing case status together? | Internal badge + "Customer sees: …" secondary text |
| 5 | "End verification" button | None (no endpoint); "New search" only, the check lapses after 30 min |
| 6 | Accept 10-digit numbers in search/KBA | Yes, normalised to `+91…` (as 02 OQ 4) |
| 7 | Severity override UX | Checkbox reveals Severity + required Reason; the derived value can't be "overridden" to itself |
| 8 | Page sizes | 20 on lists, 5 on the overview requests card |
