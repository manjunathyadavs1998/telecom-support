# 02 Customer portal — frontend design

- Status: **Proposed** (awaiting approval) · Date: 2026-11-25
- BRD: CUS-01..CUS-08, BR-05, BR-06, BR-10, XC-02..XC-05, XC-07, XC-08, NFR-A11Y, NFR-RESP, NFR-PERF, NFR-PRIV, NFR-REL, §14 #4, §16 (customer rows)
- Backend contract: [`openapi.yaml`](openapi.yaml), [`backend.md`](backend.md) (Approved rev 2; §11 erratum E1: initiate → 409 while a PENDING or REVIEW payment exists on the invoice)
- Precedent and conventions: [`../01-foundation/frontend.md`](../01-foundation/frontend.md) (shell, guards, API client, states, tokens, test setup) · [`../00-overview.md`](../00-overview.md) §3 paging/dates, §4 errors, §7 idempotency
- Visual direction: `docs/brd/images/cus-01-dashboard.png`, `cus-02-profile.png`, `cus-03-plan.png`, `cus-05-request-list.png`, `cus-06-create-request.png`, `cus-07-payment-history.png`

## 1. Scope
**In:** the six customer nav pages (replacing their `ComingSoonPage`), plus non-nav child routes: create request, pay bill, payment
status (gateway return), receipt. Generated slice 02 API types, money/date formatting, the 3-month date window, the idempotency-key
hook, list controls (date range, paging, status badges), Vite proxy for `/mock-gateway`, unit/component and e2e tests.
**Out:** attachments (CUS-06.3, backend OQ 4), usage (CUS-03.3), case detail and replies (04), removing a verified contact (backend OQ 3),
inbound "on behalf" variants (03), notification centre, PDF generation (OQ 1), any new backend endpoint.

Everything in 01 §5–§8 applies unchanged: `/me` in TanStack Query, no optimistic mutations (XC-07), 401/403 handling, `applyProblemToForm`,
`ErrorState` with correlation id, `PageContainer` focus/title, tokens and breakpoints. This document only adds to it.

Folder layout: `src/features/customer/{dashboard,profile,plan,promotions,requests,payments}/` (each: `queries.ts` with query keys, hooks and
mutations, page and component files, tests next to them) · `src/components/data/` (list controls) · `src/lib/{format,dateWindow}.ts` ·
`src/services/api/idempotency.ts` · `src/app/pageRegistry.ts`.

## 2. Routes and navigation
Nav entries are unchanged (01 §2: Home, Profile, My plan, Promotions, Service requests, Payment history; customer gate = `userType
CUSTOMER` + the read permission). New: `NavItem.children` for non-nav routes, so every protected route still comes from `NAV_ITEMS`
(`navConfig.test`). A child inherits the parent's `requires` + `userType` and adds its own. `pageRegistry.ts` maps item/child id → lazy page
(`route.lazy`, NFR-PERF); an id without a page still renders `ComingSoonPage`.

| Route | Screen | Required (all of; all with `userType CUSTOMER`) | Nav / crumb | BRD |
|---|---|---|---|---|
| `/customer` | Dashboard | `customer.profile.read` | Home | CUS-01 |
| `/customer/profile` | Profile | `customer.profile.read` (edit controls: `customer.profile.update` via `Can`) | Profile | CUS-02 |
| `/customer/plan` | My plan | `plan.read` (upgrade: `plan.change` via `Can`) | My plan | CUS-03 |
| `/customer/promotions` | Promotions | `promotion.read` (claim: `promotion.claim` via `Can`) | Promotions | CUS-04 |
| `/customer/requests` | Request list | `case.read` | Service requests | CUS-05 |
| `/customer/requests/new` | Create request | `case.read` + `case.create` | Service requests › New request | CUS-06 |
| `/customer/payments` | Payment history | `payment.read` | Payment history | CUS-07 |
| `/customer/payments/pay` | Pay bill | `payment.read` + `payment.initiate` | Payment history › Pay bill | CUS-08.1/08.2 |
| `/customer/payments/:reference` | Payment status (gateway return) | `payment.read` | Payment history › `PAY-…` | CUS-08.3–08.5 |
| `/customer/payments/:reference/receipt` | Receipt | `payment.read` | … › Receipt | CUS-07.4, CUS-08.5 |

- Staff (incl. INBOUND_AGENT, which holds `plan.read`, `payment.read`, …) get `ForbiddenPage` at the requested URL for **every** route
  above, child routes included, and the page component (hence its API calls) never mounts (XC-02, BR-10).
- `:reference` not matching `^PAY-\d{8}-\d{6,}$` → `NotFoundPage` without an API call. Static `pay` wins over `:reference` (React Router ranking).
- The gateway returns the browser (full page load) to `/customer/payments/{reference}`; the protected loader re-validates `/me`. If the
  session expired meanwhile → `/login?reason=expired&returnTo=/customer/payments/PAY-…` → after login back to the status page (01 §7.1).

## 3. Screens
Every page: `PageContainer` title, per-section `LoadingState` (skeleton), `EmptyState`, `ErrorState` (retry + correlation id), form
validation and success feedback (XC-03). Query 403 → `ForbiddenPage`; 404 on a detail → `ErrorState` "not found" with a back link.

### Dashboard `/customer` (CUS-01)
```
┌ Identity: name · primary mobile · account ref · customer ref ─────────┐
├ Promotion banner (first unclaimed offer, hidden if none) ─────────────┤
├ My current plan (primary sub) ───┬ Quick links ───────────────────────┤
│ name · ₹/month · status · [View] │ Create request · Pay now ·         │
├ Recent requests (3) [View all] ──┤ Payment history · Promotions       │
├ Bills & payments: outstanding total [Pay now] · last payment ─────────┤
```
- Data (one query per card, independent skeleton/empty/error, a failing card never blanks the page, CUS-01.2): identity `useProfile` (E1) ·
  plan `useSubscriptions` (E5, primary) · banner `usePromotions` (E8) · requests `useCases({size:3})` (E11, default window) · payments
  `useInvoices({status:'OPEN'})` (E13) + `usePayments({size:3})` (E14). Only own data exists in these endpoints (CUS-01.3, server-enforced).
- Links (CUS-01.4): plan card → `/customer/plan`, "View all" → `/customer/requests`, "Pay now" → `/customer/payments/pay` (shown only with
  `payment.initiate` and outstanding > 0), quick links per mock; actions hidden by `Can` when the permission is missing.
- No "Premium customer" badge (no data source, OQ 9). Grid: 1 column < `md`, 2 columns ≥ `md`. Cards are `<section aria-labelledby>`.

### Profile `/customer/profile` (CUS-02)
- Read-only block: full name, primary mobile, customer ref, account ref. **Billing address** read-only with an info `Alert`: "You can't change
  your billing address here. Contact customer support to change it." (CUS-02.3).
- **Alias form** (RHF + Zod, §6): Save / Cancel (reset). Sends `{aliasName, version}` from the loaded profile; 200 → `setQueryData(profile)`,
  toast "Profile saved" (CUS-02.4); nothing changes on screen before the 200 (XC-07).
- **Contacts**: Email and Secondary mobile rows: value or "Not set", badge "Verified" / "Not verified" (text, not colour only), button
  "Change" / "Add" → `ContactChangeDialog` (2 steps, focus trapped, `Esc` closes):
  1. *Enter new value* → E3 → 201 `{id, maskedDestination, expiresAt}` → step 2.
  2. *Enter the 6-digit code* sent to `maskedDestination`; live "Code expires in mm:ss" (from `expiresAt`, announced only at 1 min and 0);
     "Send a new code" = E3 again (supersedes; same 429 rules); Confirm → E4 → 200 `Profile` → `setQueryData`, toast "Email verified" /
     "Mobile number verified", dialog closes, focus returns to the row button.
  Error states per §6 (wrong code with attempts left, expired/locked → code input disabled + "Send a new code", 429 countdown, 503).
- The code never goes into toasts, logs, query keys or the mutation cache after settle (`gcTime: 0`) (NFR-SEC).

### My plan `/customer/plan` (CUS-03)
- One `SubscriptionCard` per subscription, primary first (E5). **Collapsed** (CUS-03.1): plan name, `₹999 / month`, subscription status
  badge, "Billing cycle: day 5 of each month", key benefits (`key=true`). **"View details"** = disclosure button (`aria-expanded`,
  `aria-controls`) → all benefits grouped Voice · Data · SMS · Roaming · OTT · Other, only non-empty groups (CUS-03.2). No usage (CUS-03.3).
- `pendingPlanChange` → info `Alert` "Changing to {toPlanCode} on {date}". Upgrade controls hidden when a change is pending, status ≠ ACTIVE,
  or no `plan.change`.
- **Upgrade options** (CUS-03.4): button "See upgrade options" fetches E6 for that subscription on demand → comparison table (current vs
  each option: monthly rental, `+₹ difference`, key benefits, effective date, charge). Empty → "No upgrades are available for this plan".
- **Confirm** (CUS-03.5, XC-06): `AlertDialog` "Change to {name}?" showing effective date, charge (`₹0.00` shown explicitly) and new rental;
  Confirm → E7 `{targetPlanCode, expectedEffectiveDate, expectedCharge}` echoing the quote verbatim, with an idempotency key. 201 →
  invalidate subscriptions + options, toast "Plan change scheduled for {date}". 422 → dialog shows the server `detail` + "Options were
  refreshed", options refetched (quote changed / ineligible). 409 → "Your request is still being processed", keep the key, retry allowed.

### Promotions `/customer/promotions` (CUS-04)
- Offer cards (E8, server returns only eligible active offers, CUS-04.2): title, short description, "Valid {from} – {to}", eligibility text,
  "Terms" disclosure (CUS-04.1). `claimed` → badge "Claimed", CTA disabled. Empty → "No offers for you right now".
- CTA "View & claim" → `ClaimDialog` (CUS-04.3): full terms, subscription choice (`eligibleSubscriptionIds`, labelled with the MSISDN from
  E5; preselected when only one), checkbox "I accept the terms and conditions" (required), Confirm → E9 `{subscriptionId, acceptTerms:true,
  termsHash}` + idempotency key. 201 → invalidate promotions, toast "Offer claimed". 422 → `detail` + promotions refetched (expired,
  ineligible, terms changed — CUS-04.4, the stale offer is then shown non-actionable or disappears). Nothing is claimed without Confirm.
- Dashboard banner links to `/customer/promotions#<code>` (card has that `id`, focus moved to its heading).

### Service requests `/customer/requests` (CUS-05)
- Toolbar: `DateRangeFilter` tabs **Last 3 months** (default, no `from`/`to` sent → server default) | **Custom range** (`from`, `to`
  native `<input type="date">`, Apply) (CUS-05.1/05.3, BR-06); reference search (`SR-…`, "Searches within the selected dates", OQ 6 backend);
  button "New request" (`Can case.create`).
- Table (`DataTable`, CUS-05.5): Reference · Category (› subcategory) · Created (IST) · Severity badge · Status badge. Newest first.
  `Pagination` 20/page, "Showing 21–40 of 57". < `md` each row is a stacked card with the same fields.
- Filters, search and page live in the URL (`?from&to&reference&page`): kept across pages, reload and Back (XC-04); a filter change resets
  `page`. `placeholderData: keepPreviousData` + `aria-busy` on the table while the next page loads.
- Empty (CUS-05.2): "No requests in the last 3 months" (or "…between {from} and {to}" / "No request {ref} in these dates") with actions
  "Change dates" (focuses Custom range) and "New request". Server 400 → message under the filter, last good data kept.

### Create request `/customer/requests/new` (CUS-06)
- Read-only (CUS-06.1): contact number = `profile.primaryMsisdn`, account reference = `profile.accountRef` (E1; shown, never sent).
- Fields: Category `Select` (E10) → Subcategory `Select` (shown and required only when the category has subcategories) (CUS-06.2) ·
  "Which number is it about?" (only when > 1 subscription; default "Not specific", OQ 7) · Subject · Description (counter 0/4000) ·
  Preferred contact `RadioGroup` Phone / SMS / Email (Email disabled with hint "Verify your email in Profile first" unless `emailVerified`).
- Severity is not an input: note "Priority is set automatically from the category" (§14 #4, CUS-06.4; mock's selector dropped, OQ 6). No
  attachment field (CUS-06.3 deferred).
- Submit (CUS-06.6): button disabled + spinner while pending; one idempotency key per filled-in form, reused on retry of the same payload.
  201 → in-page confirmation (`role="status"`, focus to its heading): "Request {reference} submitted", status Submitted, priority, created
  time, links "View my requests" / "Create another" (resets form + key) (CUS-06.5); invalidates `['customer','cases']`. Errors per §6.

### Payment history `/customer/payments` (CUS-07)
- Same `DateRangeFilter` / URL / paging rules as requests (CUS-07.2/07.3, XC-04/05); header action "Pay bill" (`Can payment.initiate`).
- Columns (CUS-07.1): Reference · Invoice · Date/time (IST, `initiatedAt`) · Amount (INR) · Method (or "—") · Status badge · Action:
  "Receipt" when `receiptAvailable` (→ receipt route, CUS-07.4), "View" for PENDING/REVIEW (→ status page). Status is whatever the server
  reconciled (CUS-07.5); the list is refetched on window focus.
- Empty: "No payments in the last 3 months" + "Change dates".

### Pay bill `/customer/payments/pay` (CUS-08.1, 08.2)
- E13 `status=OPEN`: each open invoice with reference, period, issue/due date, amount due, paid, **outstanding** (CUS-08.1). Several → radio
  choice (oldest due first preselected); none → `EmptyState` "You have nothing to pay right now".
- Info line with the mock's method icons: "You'll choose UPI, card, net banking or wallet on the secure payment page." (CUS-08.2).
- "Pay ₹{outstanding}" → E15 `{invoiceId, expectedAmount: invoice.outstanding}` (the server string, never recomputed) + idempotency key →
  201 → `redirectTo(redirectUrl)`: resolved against `location.origin`; only `http:`/`https:` accepted (else `ErrorState`, OQ 12) →
  `window.location.assign` (the only full-page navigation in the app). Button stays busy until the page unloads.
- Errors (CUS-08.4): 409 → "A payment for this invoice is already being processed." + link to history (invoices refetched; covers duplicate
  attempts and REVIEW, erratum E1) · 422 → "The amount due has changed." + invoices refetched · 503 → "We couldn't reach the payment
  provider. Try again in a few minutes. If a payment was started, it will appear in your payment history once confirmed." + Retry (same
  key) · 404 → invoices refetched.

### Payment status `/customer/payments/:reference` (CUS-08.3–08.5)
Query E16 only; the browser return and any query string are ignored (CUS-08.3, XC-07). `refetchInterval`: 3 s while `status = PENDING` and
the poll window (2 min) is open, else off. Result region is `aria-live="polite"`; its heading gets focus when a terminal state arrives.

| Status | Shown | Actions |
|---|---|---|
| PENDING (window open) | spinner, "Confirming your payment with the provider…", reference, amount | — |
| PENDING (window over) | "Still waiting for confirmation. Don't pay again: we'll update your payment history once the provider confirms." | "Check again" (re-opens a 2 min window), "Payment history" |
| SUCCEEDED | success panel: "Payment received", reference, invoice, amount, method, completed (IST) | "View receipt" (if `receiptAvailable`), "Back to home"; invalidates invoices + payments |
| FAILED | "Payment failed" + reason text (`gateway_unavailable` → "The payment provider was unavailable", other/null → "The payment was not completed"); "You were not charged" is **not** claimed | "Try again" → pay bill |
| EXPIRED | "The payment session expired before it was completed." | "Try again" |
| REVIEW | "Payment under review: we received a result that needs checking. Don't pay again for this invoice; the status will update in your payment history." | "Payment history", "Contact support" → create request |

Badges: PENDING "Processing", SUCCEEDED "Paid", FAILED "Failed", EXPIRED "Expired", REVIEW "Under review" (text + icon).

### Receipt `/customer/payments/:reference/receipt` (CUS-07.4, CUS-08.5)
E17 JSON rendered as a printable receipt: title "Payment receipt", customer name, account ref, invoice ref, payment reference, gateway
transaction id, method, amount (INR), paid at (IST). Button "Print or save as PDF" → `window.print()` (OQ 1). Print CSS hides sidebar, header
and buttons (`print:hidden` on shell parts). 422 → "A receipt is available only for successful payments" + link to the status page.

## 4. Shared components and helpers introduced
| Component / helper | Props / API | Used by |
|---|---|---|
| `DataTable<T>` | `columns: {id, header, cell(row), mobileLabel?}[]`, `rows`, `caption`, `busy` — `<table>` ≥ `md`, stacked `<dl>` cards < `md` | requests, payments, upgrade comparison |
| `DateRangeFilter` | `value: {from?, to?}`, `onApply`, `onReset`, `maxDays=366` — tabs Last 3 months / Custom range, Zod §6 | requests, payments |
| `Pagination` | `page`, `totalPages`, `totalItems`, `size`, `onPage` — `<nav aria-label="Pagination">`, `aria-current` | requests, payments |
| `useListParams(schema)` | URL search params ⇄ typed `{from?, to?, reference?, page}`; reset page on filter change | requests, payments |
| `StatusBadge` | `kind: 'case' \| 'severity' \| 'payment' \| 'subscription'`, `value` → label + icon + token colour | all lists |
| `Money` / `formatInr(s)` | decimal string → `₹1,234.50` (`Intl.NumberFormat('en-IN', INR)`); display only, no arithmetic on amounts | everywhere |
| `formatDateTime(iso)` / `formatDate(yyyy-mm-dd)` | `Asia/Kolkata`, e.g. `25 Nov 2026, 3:30 pm`; date-only values formatted without zone shift | everywhere |
| `dateWindow` | `todayIst()`, `defaultWindow()` (today − 3 months … today), `validateRange()` | filters, labels |
| `useIdempotencyKey()` | `keyFor(payload)` → same UUID v4 while the payload is unchanged and not yet succeeded; `reset()` on 2xx or "start over" | E7, E9, E12, E15 |
| `redirectTo(url)` | §3 Pay bill rules | pay bill |
| shadcn primitives added | Dialog, AlertDialog, Select, Checkbox, RadioGroup, Textarea, Table, Tabs | — |

## 5. Client state
| State | Where | Why |
|---|---|---|
| All API data | TanStack Query keys `['customer', 'profile' \| 'subscriptions' \| 'promotions' \| 'invoices' …]`, `['customer','plan-options',subId]`, `['customer','cases',params]`, `['customer','payments',params]`, `['customer','payment',ref]`, `['customer','receipt',ref]`, `['case-categories']` | server state (CLAUDE.md); cleared on logout/expiry by 01 |
| List filters + page | URL search params | survive paging, reload, Back, deep link (XC-04) |
| Dialog step, OTP verification id, idempotency keys, poll window | component state (`useState`/`useRef`) | one screen's transient UI; never persisted |

No new Zustand store; nothing customer-related goes to `localStorage` (NFR-PRIV). Lists `staleTime` 30 s; mutations never optimistic.

## 6. Forms and validation
| Form | Zod schema fields | Server error mapping |
|---|---|---|
| Alias | `aliasName`: trim; `''` → `null`; else 1–60, `/^[\p{L}\p{N} .'-]+$/u` ("Use letters, numbers, spaces, . ' -") · `version` hidden | 400 → field · 409 → root "Your profile was changed elsewhere. We loaded the latest version." + refetch · 422 → root `detail` |
| Contact start | `channel` EMAIL \| SECONDARY_MSISDN · `value`: EMAIL → `z.email()`, ≤ 254, lower-cased; MSISDN → 10 digits 6–9 normalised to `+91…` (OQ 4) then `^\+[1-9]\d{7,14}$`, ≠ primary ("This is already your primary number") | 400/422 → field `value` (else root) · 429 → root "Too many codes requested. Try again in N min." countdown from `Retry-After`, submit disabled · 503 → root "We couldn't send the code. Try again later." |
| Contact confirm | `code`: `/^\d{6}$/` ("Enter the 6-digit code"); `inputMode=numeric`, `autocomplete=one-time-code` | 422 with `attemptsRemaining > 0` → field "Incorrect code. N attempts left." · 422 otherwise → root `detail` + input disabled + "Send a new code" (expired/locked, OQ 5) · 404 → back to step 1 "Start again" |
| Plan change | no fields; confirm only | 409/422 → dialog root (§3 My plan) |
| Promotion claim | `subscriptionId` required ("Choose a number") · `acceptTerms: z.literal(true)` ("Accept the terms to continue") | 422 → root `detail` + refetch · 409 → root "Still processing" |
| Create request | `categoryCode` required · `subcategoryCode` required iff category has subcategories · `subscriptionId` optional · `subject` trim 5–150 · `description` trim 10–4000 · `preferredContact` PHONE \| SMS \| EMAIL (EMAIL only if verified) | 400 → fields · 422 → root `detail` · 409 → root "Your request is still being submitted…" (key kept) |
| Date range | `from`, `to` required in Custom; `from ≤ to`; `to ≤ todayIst()`; span ≤ 366 days ("Choose at most 12 months") | 400 → filter root message |
| Reference search | trim, upper-case, `/^SR-\d{8}-\d{6,}$/` ("Use the format SR-20261125-000042") | 400 → filter root message |
| Pay bill | `invoiceId` required | §3 Pay bill |

All forms: errors linked with `aria-describedby`, `aria-invalid`, focus first invalid field or the root `Alert` (01 §3 Login rules).
Mutation `variables` never appear in toasts or logs; OTP and contact mutations use `gcTime: 0`.

## 7. Build and dev
| Concern | Design |
|---|---|
| Types | `gen-api.mjs` adds `docs/design/02-customer/openapi.yaml` → `src/types/api/02-customer.ts`; `types/api/index.ts`: `paths = paths01 & paths02` plus named aliases (`Profile`, `Subscription`, `Payment`, …) from 02 `components` |
| Dev proxy | `vite.config.ts` proxies `/api` **and** `/mock-gateway` to `VITE_API_TARGET`; backend dev must set `app.payment.return-url-base=http://localhost:5173` when using Vite (D1) |
| e2e | packaged JAR, `dev,e2e` profiles (01 §9); `return-url-base` = `E2E_BASE_URL` origin so the session cookie survives the gateway round trip (D1); fresh DB per run (D2, OQ 8). No new logins: customer and IN001 reuse setup `storageState` (01 login budget stays 12) |

## 8. Test plan
Vitest + RTL + MSW handlers for E1–E17 with one fixture per state (`src/test/fixtures/customer.ts`); fake timers for polling/countdowns;
`vitest-axe` on every page. Playwright e2e against the JAR.

| Test | Proves | BRD |
|---|---|---|
| `format.test.ts`, `dateWindow.test.ts` | INR grouping (`"123456.70"` → `₹1,23,456.70`); IST display of a `Z` time across midnight; date-only no shift; default window; range rules incl. 366/367 days, `to` in the future | XC-05 |
| `idempotency.test.ts` | same payload → same key; changed payload → new key; reset after success; key is a v4 UUID without `crypto.randomUUID` | NFR-REL, CUS-06.6 |
| `listControls.test.tsx` | `DateRangeFilter` validation + messages; `useListParams` keeps filters across `Pagination` clicks and reload; filter change resets page | XC-04, XC-05 |
| `navConfig.test.ts` (extended), `RequirePermission.test.tsx` (extended) | children inherit parent gate; IN001 fixture on each §2 route incl. `/customer/payments/PAY-…` and `/customer/requests/new` → `ForbiddenPage`, **no customer API request made**; invalid `:reference` → NotFound | XC-02, BR-10 |
| `dashboard.test.tsx` | each card skeleton → data; E8 500 → only the banner shows `ErrorState`, others render; empty cards; links per CUS-01.4; Pay now hidden without outstanding | CUS-01, XC-03 |
| `profile.test.tsx` | read-only identity + billing notice; alias save sends `version`; `''` → `null`; 409 → refetch + message; 422; no UI change before 200 | CUS-02.1/02.3/02.4, XC-07 |
| `contactVerification.test.tsx` | start → code step with masked destination; wrong code "N attempts left"; `attemptsRemaining 0` → disabled + resend; 429 countdown; 503; success updates profile + focus return; code absent from MutationCache after settle | CUS-02.2, NFR-SEC |
| `plan.test.tsx` | collapsed fields; disclosure `aria-expanded` + all benefit groups; pending change hides upgrade; no usage shown | CUS-03.1–03.3 |
| `planChange.test.tsx` | options on demand; comparison; confirm shows date + charge; request echoes quote; 422 → refetch; same key on retry after network error | CUS-03.4/03.5, NFR-REL |
| `promotions.test.tsx` | card fields; claimed disabled; claim requires subscription + terms; sends `termsHash`; 422 refetch; nothing sent before Confirm | CUS-04 |
| `requestList.test.tsx` | default call has no `from`/`to`; custom range params; reference search; paging keeps filters; empty state actions; columns | CUS-05, BR-05/06 |
| `createRequest.test.tsx` | read-only contact/account; subcategory rule; Email disabled unless verified; double click → one POST; retry reuses key; confirmation with reference; 400/409/422 mapping | CUS-06 |
| `paymentHistory.test.tsx` | columns; Receipt only when `receiptAvailable`; View for PENDING/REVIEW; filters/paging | CUS-07 |
| `payBill.test.tsx` | invoice + outstanding shown; `expectedAmount` = server string; redirect called with resolved URL; `javascript:` URL refused; 409/422/503 messages | CUS-08.1/08.2/08.4 |
| `paymentStatus.test.tsx` | PENDING polls every 3 s and stops at SUCCEEDED; 2 min → "Still waiting" + Check again; FAILED/EXPIRED/REVIEW texts; success only from the API; query string ignored; expired session → login `returnTo` | CUS-08.3–08.5, XC-07 |
| `receipt.test.tsx` | fields; print button; 422 message | CUS-07.4 |
| e2e `customer-portal.spec.ts` | customer: dashboard cards, plan expand, request create → reference → visible in list, history paging, at 1280/768/375 with axe | CUS-01/03/05/06/07, XC-08 |
| e2e `payment.spec.ts` | pay → mock checkout "Fail" → status FAILED → Try again → "Pay (UPI)" → polling → Paid → receipt page | CUS-08, CUS-07.4 |
| e2e `forbidden.spec.ts` (extended) | IN001 opens `/customer/requests/new` and `/customer/payments/pay` → 403 page | XC-02 |

## 9. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
| 1 | Receipt "download" format (CUS-07.4; backend OQ 9 JSON)? | Printable page + browser "Save as PDF"; no PDF file generated |
| 2 | Status-page polling? | Every 3 s for 2 min, then "Still waiting" + manual "Check again" |
| 3 | TanStack Table for these lists? | Not used in 02 (fixed columns, one server sort); first used for the sortable queue in 04 |
| 4 | Accept a 10-digit Indian number for the secondary mobile? | Yes, normalised to `+91…` before sending; other countries in E.164 |
| 5 | OTP 422 has no machine-readable reason (wrong/expired/locked) | Map by `attemptsRemaining` (> 0 wrong) + show server `detail`; optional contract add `reason` later |
| 6 | Severity selector shown in the mock (CUS-06.4)? | Not an input; system-set by category (§14 #4), shown after submit |
| 7 | Subscription picker on create request? | Only with > 1 subscription, default "Not specific" |
| 8 | e2e data (payments and requests mutate seed data) | Fresh migrated + seeded DB per e2e run; payment spec uses Fail then Pay on one OPEN invoice of `+919800000001` |
| 9 | "Premium customer" badge in the mock? | Omitted (no data in the contract) |
| 10 | Mock's "All requests" tab? | Replaced by "Custom range" (BR-06 bounded filters) |
| 11 | Page size? | 20 on list pages, 3 on dashboard cards, no size selector |
| 12 | Allowed payment redirect targets? | Any `http(s)` URL from E15 (server-built, trusted); other schemes refused |

## 10. Dependencies outside this design
| # | Needs | Owner |
|---|---|---|
| D1 | `app.payment.return-url-base` = e2e base origin in the `e2e` profile, `http://localhost:5173` documented for Vite dev | backend build (config only) |
| D2 | e2e run starts from a fresh DB (`scripts/check-frontend.sh e2e` precondition) and seed customer `+919800000001` has ≥ 1 OPEN invoice, cases and payments in the last 3 months | backend seed + F20 |
