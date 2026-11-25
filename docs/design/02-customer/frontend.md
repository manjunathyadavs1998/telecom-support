# 02 Customer portal — frontend design

- Status: **Approved** (revision 2, review F1–F24 addressed) · Date: 2026-11-25 · approved by user 2026-11-25 with OQ defaults; depends on backend errata E4–E6 (approved, merge with the BE PR)
- BRD: CUS-01..CUS-08, BR-05, BR-06, BR-10, XC-02..XC-05, XC-07, XC-08, NFR-A11Y, NFR-RESP, NFR-PERF, NFR-PRIV, NFR-REL, NFR-SEC, §14 #4, §16 (customer rows)
- Backend contract: [`openapi.yaml`](openapi.yaml), [`backend.md`](backend.md) (Approved rev 2) + errata on `slice/02-customer-be` (merge with the BE PR):
  **E1** (approved: initiate → 409 while a PENDING or REVIEW payment exists), **E4** (returnUrl path, awaiting approval), and the FE-driven
  items in §10 (awaiting approval)
- Precedent and conventions: [`../01-foundation/frontend.md`](../01-foundation/frontend.md) (shell, guards, API client, states, tokens, tests) · [`../00-overview.md`](../00-overview.md) §3 paging/dates, §4 errors, §7 idempotency
- Visual direction: `docs/brd/images/cus-01-dashboard.png`, `cus-02-profile.png`, `cus-03-plan.png`, `cus-05-request-list.png`, `cus-06-create-request.png`, `cus-07-payment-history.png`
- Review: [`../../slices/02-customer/review-frontend-design.md`](../../slices/02-customer/review-frontend-design.md) (disposition at its end)

## 1. Scope
**In:** the six customer nav pages (replacing `ComingSoonPage`) and the non-nav routes create request, pay bill, payment status (gateway
return) and receipt. Also: generated slice 02 API types, money/date formatting, the 3-month window, the idempotency-key hook, list controls,
nested routes with breadcrumbs, the Vite proxy for `/mock-gateway`, and unit/component and e2e tests.
**Out:** attachments (CUS-06.3, backend OQ 4), usage (CUS-03.3), case detail and replies (04), removing a verified contact (backend OQ 3),
inbound variants (03), the notification centre, PDF generation (OQ 1), and any new endpoint.

Everything in 01 §5–§8 applies unchanged: `/me` lives in TanStack Query, there are no optimistic mutations (XC-07), 401/403 handling,
`applyProblemToForm`, `ErrorState` with the correlation id, `PageContainer` focus/title, tokens and breakpoints. Layout:
`src/features/customer/{dashboard,profile,plan,promotions,requests,payments}/` (`queries.ts` with keys, hooks and mutations, pages, tests
next to them) · `src/components/data/` · `src/lib/{format,dateWindow}.ts` · `src/services/api/idempotency.ts` · `src/app/pageRegistry.ts`.

## 2. Routes and navigation
Nav entries are unchanged (01 §2). `NavItem.children?: {id, path, label | (params) => string, requires?}[]` keeps every protected route in
`NAV_ITEMS` (`navConfig.test`). **Route shape (F9, F14):** each nav item is a parent route whose `element` is the guard
`<RequirePermission allOf userType><Outlet/></RequirePermission>`. Its `index` child and its children get the page from `route.lazy` via
`pageRegistry.ts` (NFR-PERF). A child's guard is nested in the same way, with parent `requires` ∪ its own. The guard renders before any
lazy page mounts, so a forbidden user never fires a customer API call. An id without a page renders `ComingSoonPage`.
`handle.crumb: string | (params) => string`; `Breadcrumbs` renders one crumb per matched route that has a crumb (non-last crumbs are links).
`NavList` uses `end` only for items without children (Home), so the parent item stays `aria-current="page"` on its child routes.

| Route | Screen | Required (all of; all `userType CUSTOMER`) | Crumbs | BRD |
|---|---|---|---|---|
| `/customer` | Dashboard | `customer.profile.read` (each card also needs its own read permission via `Can`) | Home | CUS-01 |
| `/customer/profile` | Profile | `customer.profile.read` (edit: `customer.profile.update` via `Can`) | Profile | CUS-02 |
| `/customer/plan` | My plan | `plan.read` (upgrade: `plan.change` via `Can`) | My plan | CUS-03 |
| `/customer/promotions` | Promotions | `promotion.read` (claim: `promotion.claim` + `plan.read` via `Can`) | Promotions | CUS-04 |
| `/customer/requests` | Request list | `case.read` | Service requests | CUS-05 |
| `/customer/requests/new` | Create request | `case.read` + `case.create` + `customer.profile.read` (number picker: `plan.read` via `Can`) | Service requests › New request | CUS-06 |
| `/customer/payments` | Payment history | `payment.read` | Payment history | CUS-07 |
| `/customer/payments/pay` | Pay bill | `payment.read` + `payment.initiate` | Payment history › Pay bill | CUS-08.1/08.2 |
| `/customer/payments/:reference` | Payment status (gateway return) | `payment.read` | Payment history › `PAY-…` | CUS-08.3–08.5 |
| `/customer/payments/:reference/receipt` | Receipt | `payment.read` | Payment history › `PAY-…` › Receipt | CUS-07.4, CUS-08.5 |

- Staff, including INBOUND_AGENT (which holds `plan.read`, `payment.read`, …), get `ForbiddenPage` at the requested URL on every route above,
  child routes included (XC-02, BR-10). A `:reference` that doesn't match `^PAY-\d{8}-\d{6,}$` → `NotFoundPage` with no API call. The static
  `pay` route ranks above `:reference`.
- **Gateway return (F1):** the backend builds `returnUrl = {app.payment.return-url-base}/customer/payments/{reference}`
  ([`backend.md`](backend.md) §11 E4, returnUrl path). The SPA ignores any query string. The return is a top-level GET, so the
  `SameSite=Lax` SESSION cookie is sent. The protected loader re-validates `/me`. An expired session → `/login?reason=expired&returnTo=…` →
  after login the customer is back on the status page (01 §7.1).

## 3. Screens
Every page has a `PageContainer` title, per-section `LoadingState` (skeleton), `EmptyState`, `ErrorState` (retry + correlation id),
validation and success feedback (XC-03). **403 rule (F2):** a 403 on the page's *primary* query (the one its route permission covers) →
`ForbiddenPage`. A 403 on any *secondary* query (dashboard cards, supporting data) → that section's `ErrorState` "This information isn't
available for your account" (hooks pass `meta: OWN_FORBIDDEN_HANDLING`). A 404 on a detail → `ErrorState` "not found" with a back link.

### Dashboard `/customer` (CUS-01)
```
┌ Identity: name · primary mobile · account ref · customer ref ─────────┐
├ Promotion banner (first offer with a claimable number; hidden if none)┤
├ My current plan (primary sub) ───┬ Quick links ───────────────────────┤
│ name · ₹/month · status · [View] │ Create request · Pay now ·         │
├ Recent requests (3) [View all] ──┤ Payment history · Promotions       │
├ Bills: each open invoice (≤ 3) ref · due · outstanding [Pay now] · last payment ┤
```
- Every card is its own query and a section with its own skeleton/empty/error, wrapped in `Can` with that card's read permission. A failing
  card (incl. 403) never blanks the page (CUS-01.2). Sources: identity E1 · plan E5 (primary) · banner E8 · requests E11 `size=3` (default
  window) · bills E13 `status=OPEN` + last payment E14 `size=1`. Own data only, enforced by the server (CUS-01.3).
- Bills show each open invoice's **server** `outstanding` (no total, F8). "Pay now" is shown with `payment.initiate` and at least one
  invoice where `isPositiveMoney(outstanding)` (§4, a string check, not arithmetic).
- Links (CUS-01.4): plan card → `/customer/plan`, "View all" → `/customer/requests`, "Pay now" → `/customer/payments/pay`, quick links per
  the mock (each one in `Can`). No "Premium customer" badge (OQ 9). One column < `md`, two ≥ `md`; cards are `<section aria-labelledby>`.

### Profile `/customer/profile` (CUS-02)
- Read-only: full name, primary mobile, customer ref, account ref. **Billing address** is read-only with an info `Alert`: "You can't
  change your billing address here. Contact customer support to change it." (CUS-02.3).
- **Alias form** (§6): Save / Cancel. At submit, `version` is read from the **current** `['customer','profile']` cache, not from the
  form's initial data. When the cached `version` changes and the form isn't dirty, the form `reset()`s to the new profile (F6). 200 →
  `setQueryData(profile)` + form reset, toast "Profile saved" (CUS-02.4). Nothing changes on screen before the 200 (XC-07).
- **Contacts**: Email and Secondary mobile rows show the value or "Not set" and a "Verified" / "Not verified" badge (text, not colour
  only). "Change" / "Add" opens `ContactChangeDialog` (focus trapped, `Esc` closes):
  1. Enter new value → E3 → 201 `{id, maskedDestination, expiresAt}` → step 2.
  2. Enter the 6-digit code sent to `maskedDestination`; shows "Code expires in mm:ss" (announced only at 1 min and at 0). "Send a new
     code" = E3 again; disabled while pending and for a **30 s cooldown** with a visible countdown (F22). Confirm → E4 → 200 `Profile` →
     `setQueryData` (its new `version` re-baselines the alias form) + `invalidateQueries(['customer','profile'])`, toast "Email verified" /
     "Mobile number verified", focus returns to the row button.
- The code never goes into toasts, logs, query keys or the mutation cache after settle (`gcTime: 0`) (NFR-SEC).

### My plan `/customer/plan` (CUS-03)
- One `SubscriptionCard` per subscription, primary first (E5). **Collapsed** (CUS-03.1): plan name, `₹999 / month`, status badge, "Billing
  cycle: day 5 of each month", key benefits. **"View details"** is a disclosure (`aria-expanded`, `aria-controls`) that lists all benefits
  grouped Voice · Data · SMS · Roaming · OTT · Other, showing only non-empty groups (CUS-03.2). No usage (CUS-03.3).
- `pendingPlanChange` → `Alert` "Changing to {toPlanCode} on {date}". Upgrade controls are hidden while a change is pending, when status ≠
  ACTIVE, or without `plan.change`.
- **Upgrade options** (CUS-03.4): "See upgrade options" fetches E6 for that subscription → comparison `DataTable` (current vs each option:
  rental, `+₹ difference`, key benefits, effective date, charge). Empty → "No upgrades are available for this plan".
- **Confirm** (CUS-03.5, XC-06): `AlertDialog` "Change to {name}?" with effective date, charge (shown even when `₹0.00`) and new rental.
  Confirm → E7 echoing the quote verbatim, with an idempotency key scoped to the subscription path (§4). 201 → invalidate subscriptions and
  options, toast "Plan change scheduled for {date}". Error mapping is in §6.

### Promotions `/customer/promotions` (CUS-04)
- Offer cards (E8, eligible active offers only, CUS-04.2): title, short description, "Valid until {validTo as IST date and time}" (exclusive
  end, F23), eligibility, a "Terms" disclosure (CUS-04.1).
- **Per number (F11):** CTA "View & claim" is enabled iff `eligibleSubscriptionIds` is non-empty. Numbers already claimed show "Claimed for
  number ending 1234" (from `claimedSubscriptionIds`, §10 D3). With no claimable number left, the CTA is disabled with "Claimed".
- `ClaimDialog` (CUS-04.3): full terms; number choice from `eligibleSubscriptionIds` labelled with the MSISDN from E5 (preselected when only
  one); "I accept the terms and conditions" (required); Confirm → E9 `{subscriptionId, acceptTerms:true, termsHash}` + a key scoped to the
  promotion path. 201 → invalidate promotions, toast "Offer claimed". Nothing is claimed without Confirm.
- The dashboard banner links to `/customer/promotions#<code>`. Focus goes to that card's heading only **after** the shell's route-change
  `<h1>` focus effect has run (F18).

### Service requests `/customer/requests` (CUS-05)
- Toolbar: `DateRangeFilter` with **Last 3 months** (default; no `from`/`to` sent) and **Custom range** (native date inputs + Apply, hint
  "Dates are in India Standard Time (IST)") (CUS-05.1/05.3, BR-06, XC-05). Reference search ("Searches within the selected dates").
  "New request" (`Can case.create`).
- `DataTable` (CUS-05.5): Reference · Category (› subcategory) · Created (IST) · Severity · Status, newest first. `Pagination` with 20 per
  page, "Showing 21–40 of 57". Below `md`, rows become stacked cards.
- URL state (XC-04, F16): `?from&to&reference&page`, where `page` is **1-based** in the URL and converted to 0-based at the API boundary.
  Filter changes reset `page`. Invalid URL params (bad date, range rules, bad reference, page < 1) are dropped before any request, with an
  inline notice "Some filters in the link were invalid and were reset". If `page > totalPages`, show "This page is empty" + "Go to page 1".
  `keepPreviousData` + `aria-busy` while paging. A server 400 on a filter change → message under the filter, last good data kept.
- Empty (CUS-05.2): "No requests in the last 3 months" / "…between {from} and {to}" / "No request {ref} in these dates" + "Change dates"
  (focuses Custom range) + "New request".

### Create request `/customer/requests/new` (CUS-06)
- Read-only (CUS-06.1): contact number = `profile.primaryMsisdn`, account reference = `profile.accountRef` (E1; shown, never sent).
- Fields: Category `Select` (E10) → Subcategory (shown and required only when the category has subcategories) (CUS-06.2) · "Which number
  is it about?" (only with `plan.read` and > 1 subscription; default "Not specific", OQ 7) · Subject · Description (counter linked by
  `aria-describedby`; a polite announcement only from 3900 characters, F18) · Preferred contact Phone / SMS / Email (Email disabled with
  the hint "Verify your email in Profile first" unless `emailVerified`).
- E10 error → `ErrorState` with retry in place of the form. E10 empty → "Service requests are unavailable right now" (F24).
- Severity is not an input: "Priority is set automatically from the category" (§14 #4, CUS-06.4, OQ 6). No attachment field.
- Submit (CUS-06.6, F21): a synchronous in-flight `useRef` guard plus a disabled busy button, and an idempotency key per filled-in form.
  201 → in-page confirmation (`role="status"`, focus to its heading): "Request {reference} submitted", status, priority, created time,
  "View my requests" / "Create another" (resets form and key) (CUS-06.5). Invalidates `['customer','cases']`.

### Payment history `/customer/payments` (CUS-07)
- Same filter, URL and paging rules as requests (CUS-07.2/07.3, XC-04/05). Header action "Pay bill" (`Can payment.initiate`).
- Columns (CUS-07.1): Reference · Invoice · Date/time (IST) · Amount · Method (or "—") · Status · Action. "Receipt" when `receiptAvailable`
  (CUS-07.4); "View" (status page) for PENDING/REVIEW. Status is whatever the server reconciled (CUS-07.5); refetched on window focus.
- Empty: "No payments in the last 3 months" + "Change dates".

### Pay bill `/customer/payments/pay` (CUS-08.1, 08.2, 08.4)
- E13 `status=OPEN`: each open invoice with reference, period, issue/due date, amount due, paid, **outstanding** (CUS-08.1). With several,
  a radio choice (oldest due first, preselected). None → "You have nothing to pay right now". Info line: "You'll choose UPI, card, net
  banking or wallet on the secure payment page." (CUS-08.2).
- **Pay** "Pay ₹{outstanding}" → E15 `{invoiceId, expectedAmount: invoice.outstanding}` (server string verbatim) with the persisted key (§4,
  operation `PAYMENT_INITIATE`, path `/customers/me/payments`, scope `invoiceId`). 201 → the record stores `reference` →
  `redirectTo(redirectUrl)` (§4) → `window.location.assign`.
- **Abandoned checkout (F3):** on load, a stored E15 record for the selected invoice with a `reference` → E16. If it is **PENDING**, show
  `Alert` "You started a payment for this bill ({reference})" with **"Continue payment"**: re-sends E15 with the same key, the server replays
  the stored 201, and the customer is redirected to the same checkout. If it is terminal, the record is dropped and "Pay" uses a fresh key.
- **bfcache (F4):** on `pageshow` with `event.persisted`, reset the busy state and the in-flight guard and refetch invoices (and the stored
  record's payment).
- Errors (CUS-08.4, §6): 409 → "A payment for this bill is still open. If you left the payment page, continue it from {reference link, if
  stored, else Payment history}, or try again in about 15 minutes." (covers duplicate attempts and REVIEW, E1). 422 → "The amount due has
  changed" + refetch. 503 → "We couldn't reach the payment provider. Try again in a few minutes. If a payment was started, it will appear in
  your payment history once confirmed." + Retry (same key). 404 → "This bill is no longer available" + refetch.

### Payment status `/customer/payments/:reference` (CUS-08.3–08.5)
Only E16 is used. The browser return and any query string are ignored (CUS-08.3, XC-07). `refetchInterval` is 3 s while `status = PENDING`
and the 2 min poll window is open, else off. `refetchOnWindowFocus` stays on for every status (late capture, F12).
**Poll failures (F5):** a failed refetch (5xx/network after retries) keeps the last confirmed status on screen with an inline notice "We
couldn't check just now. Retrying…". The interval keeps running inside the window, and an error is never shown as FAILED. Only a first
load without data shows `ErrorState`. A 401 follows 01 (login with `returnTo`).
**Announcements (F18):** progress text sits in a polite live region. On a terminal state the live region is not updated; focus moves to
the result heading instead.

| Status | Shown | Actions |
|---|---|---|
| PENDING (window open) | spinner, "Confirming your payment with the provider…", reference, amount | — |
| PENDING (window over) | "Still waiting for confirmation. Don't pay again: we'll update your payment history once the provider confirms." | "Check again" (new 2 min window), "Payment history" |
| SUCCEEDED | "Payment received", reference, invoice, amount, method, completed (IST) | "View receipt" (if `receiptAvailable`), "Back to home"; invalidates invoices + payments; drops the stored E15 record |
| FAILED | "Payment failed" + reason (`gateway_unavailable` → "The payment provider was unavailable", else "The payment was not completed") + "If money was taken from your account, it will appear in your payment history within 24 hours. Check there before paying again." | "Try again" → pay bill; drops the stored record |
| EXPIRED | "The payment session expired before it was completed." + the same 24 h note | "Try again"; drops the stored record |
| REVIEW | "Payment under review: we received a result that needs checking. Don't pay again for this bill; the status will update in your payment history." | "Payment history", "Contact support" → create request |

Badges: PENDING "Processing", SUCCEEDED "Paid", FAILED "Failed", EXPIRED "Expired", REVIEW "Under review" (text + icon).

### Receipt `/customer/payments/:reference/receipt` (CUS-07.4, CUS-08.5)
E17 rendered as a printable receipt: customer name, account ref, invoice ref, payment reference, gateway transaction id, method, amount,
paid at (IST). "Print or save as PDF" → `window.print()` (OQ 1). `print:hidden` on sidebar, header and buttons. 422 → "A receipt is
available only for successful payments" + a link to the status page.

## 4. Shared components and helpers introduced
| Component / helper | Props / API | Used by |
|---|---|---|
| `DataTable<T>` | `columns: {id, header, cell(row), mobileLabel?}[]`, `rows`, `caption`, `busy` — `<table>` ≥ `md`, stacked `<dl>` cards < `md` | lists, upgrade comparison |
| `DateRangeFilter` | `value`, `onApply`, `onReset`, `maxDays=366` — `Tabs` with `activationMode="manual"` (F17), IST hint, Zod §6 | requests, payments |
| `Pagination` | `page` (1-based), `totalPages`, `totalItems`, `size`, `onPage` — `<nav aria-label="Pagination">`, `aria-current` | requests, payments |
| `useListParams(schema)` | URL ⇄ typed `{from?, to?, reference?, page}`; drops invalid params + `invalidNotice`; 1-based ⇄ 0-based at the API | requests, payments |
| `StatusBadge` | `kind: 'case' \| 'severity' \| 'payment' \| 'subscription'`, `value` → label + icon + token colour | lists, status page |
| `formatInr(s)`, `isPositiveMoney(s)` | `Money` string → `₹1,23,456.70` (`Intl.NumberFormat('en-IN', INR)`, display only); `isPositiveMoney` = matches `^\d{1,10}\.\d{2}$` and isn't all zeros. **No arithmetic on amounts anywhere** (F8) | everywhere |
| `formatDateTime(iso)`, `formatDate(ymd)` | `Asia/Kolkata`, e.g. `25 Nov 2026, 3:30 pm`; date-only values without a zone shift | everywhere |
| `dateWindow` | `todayIst()`, `defaultWindow()`, `validateRange()` | filters |
| `useIdempotencyKey(operation, path, {persist?, scope?})` | `keyFor(body)`: the same UUID v4 while `{operation, path, stable-serialised body}` is unchanged; `reset()`. Kept on 409/5xx/network, dropped on 2xx and on 400/404/422. `persist` (E15 only) stores `{key, bodyHash, reference?, savedAt}` in **sessionStorage** under `idem:<operation>:<path>:<scope>` until a terminal payment status or 24 h (no PII, NFR-PRIV) (F3, F7) | E7, E9, E12, E15 |
| `useSubmitGuard()` | synchronous `useRef` in-flight flag; `run(fn)` ignores calls while one is in flight; reset on settle and on bfcache `pageshow` (F21, F4) | all mutation forms |
| `redirectTo(url)` | resolved against `location.origin`; accepted only if same-origin or its origin is in `PAYMENT_GATEWAY_ORIGINS` (build-time constant, empty in 02); else `ErrorState` (F19) | pay bill |
| `attemptsRemaining(error)` | reads the generated `VerificationProblem` field with a runtime `typeof === 'number'` check (F13) | contact dialog |
| shadcn primitives added | Dialog, AlertDialog, Select, Checkbox, RadioGroup, Textarea, Table, Tabs | — |

## 5. Client state
| State | Where | Why |
|---|---|---|
| All API data | TanStack Query `['customer', 'profile' \| 'subscriptions' \| 'promotions' \| 'invoices' …]`, `['customer','plan-options',subId]`, `['customer','cases',params]`, `['customer','payments',params]`, `['customer','payment',ref]`, `['customer','receipt',ref]`, `['case-categories']` | server state (CLAUDE.md); cleared on logout/expiry (01) |
| List filters + page | URL search params | survive paging, reload, Back, deep link (XC-04) |
| E15 idempotency record | `sessionStorage` (UUID, hash, payment reference only); cleared on logout with the query cache | resume an abandoned checkout in this tab (F3) |
| Dialog step, verification id, other keys, poll window, cooldowns | component state | transient UI of one screen |

No new Zustand store. Nothing goes in `localStorage` (NFR-PRIV). Lists use `staleTime` 30 s. Mutations are never optimistic.

## 6. Forms and validation
Common rule (F24): any status not listed below, a 500 or a network error → root `FormRootError` with the correlation id. The key is kept,
so retrying is safe. A 422 whose `type` ends in `idempotency-mismatch` → fresh key + "Please try again" (never business copy) (F7).

| Form | Zod schema fields | Server error mapping |
|---|---|---|
| Alias | `aliasName`: trim; `''` → `null`; else 1–60, `/^[\p{L}\p{N} .'-]+$/u` · `version` from the cache at submit | 400 → field · 409 → root "Your profile was changed elsewhere. We loaded the latest version." + refetch · 422 → root `detail` |
| Contact start | `channel` · `value`: EMAIL → `z.email()`, ≤ 254, lower-cased; MSISDN → 10 digits 6–9 normalised to `+91…` (OQ 4), then E.164, ≠ primary | 400/422 → field `value` (else root) · 429 → root "Too many codes requested. Try again in N min." countdown from `Retry-After` · 503 → root "We couldn't send the code. Try again later." |
| Contact confirm | `code`: `/^\d{6}$/`, `inputMode=numeric`, `autocomplete=one-time-code` | 422 with `attemptsRemaining(e) > 0` → field "Incorrect code. N attempts left." · other 422 → root `detail`, input disabled, "Send a new code" (OQ 5) · 404 → back to step 1 |
| Plan change | none (confirm only) | 422 → root `detail` + "Options were refreshed" + refetch · 409 → "Still processing" (key kept) |
| Promotion claim | `subscriptionId` required · `acceptTerms: z.literal(true)` | 422 → root `detail` + refetch · 404 → "This offer is no longer available" + refetch, dialog closes · 409 → "Still processing" |
| Create request | `categoryCode` · `subcategoryCode` iff subcategories · `subscriptionId?` · `subject` trim 5–150 · `description` trim 10–4000 · `preferredContact` (EMAIL only if verified) | 400 → fields · 422 → root `detail` · 409 → root "Your request is still being submitted…" |
| Date range | Custom: `from`, `to` required; `from ≤ to`; `to ≤ todayIst()`; ≤ 366 days | 400 → filter message |
| Reference search | trim, upper-case, `/^SR-\d{8}-\d{6,}$/` | 400 → filter message |
| Pay bill | `invoiceId` required | §3 Pay bill |

All forms link errors with `aria-describedby`/`aria-invalid` and move focus to the first invalid field or to the root `Alert`. Mutation
`variables` never appear in toasts or logs. OTP and contact mutations use `gcTime: 0`.

## 7. Build, dev and e2e
| Concern | Design |
|---|---|
| Types | `gen-api.mjs` adds `02-customer/openapi.yaml` → `src/types/api/02-customer.ts`; `index.ts`: `paths = paths01 & paths02` + named aliases |
| Dev proxy | `vite.config.ts` proxies `/api` **and** `/mock-gateway` to `VITE_API_TARGET`; with Vite, run the backend with `return-url-base=http://localhost:5173` |
| e2e data (F10) | Each run starts from a fresh DB: the `e2e` profile runs Flyway `clean` + `migrate` at JAR start (§10 D2), so the host step is "start the JAR (`dev,e2e`) → `check-frontend.sh e2e`", documented in the script. Disjoint seed customers: `+919800000001` read-only portal checks (and request create, asserted by its new reference only), `+919800000005` payment spec only |
| e2e runs | `customer-portal.spec` runs in the 3 viewport projects (`playwright.config.ts`); `payment.spec` runs `serial` in one project. Setup project +1 login (customer 5) → budget **13** (L1 raised to 100/min by `e2e`, 01 §10) |

## 8. Test plan
Vitest + RTL + MSW handlers for E1–E17 with one fixture per state (`src/test/fixtures/customer.ts`); fake timers for polling and
countdowns; `vitest-axe` on every page. Playwright e2e against the JAR.

| Test | Proves | BRD |
|---|---|---|
| `format.test.ts`, `dateWindow.test.ts` | INR grouping; `isPositiveMoney("0.00")` false; IST across midnight; date-only no shift; exclusive `validTo` at IST midnight; window and range rules (366/367 days, future `to`) | XC-05, CUS-04.1 |
| `idempotency.test.ts` | same body + path → same key; same body, **other path** → other key; reset on 2xx/422; kept on 409/5xx; persisted record survives remount, removed on terminal; `useSubmitGuard` lets two fast calls → one run | NFR-REL, CUS-06.6 |
| `listControls.test.tsx` | filter validation; filters kept across pages and reload; 1-based URL page → 0-based request; invalid URL params dropped + notice, no request with them; page past the end; manual tab activation | XC-04, XC-05 |
| `navConfig.test.ts`, `RequirePermission.test.tsx`, `AppShell.test.tsx` (extended) | children inherit the gate; IN001 on every §2 route → `ForbiddenPage` and **no customer API request**; bad `:reference` → NotFound; crumbs "Payment history › PAY-… › Receipt"; parent nav item `aria-current` on child routes | XC-02, BR-10, XC-08 |
| `dashboard.test.tsx` | each card skeleton → data; E8 500 and E5 **403** → only that card shows `ErrorState`, the page stays; per-invoice outstanding; Pay now rules; links | CUS-01, XC-03 |
| `profile.test.tsx` | read-only block + billing notice; alias sends cache `version`; `''` → `null`; 409 → refetch; **contact confirm then alias save → one PATCH with the new version, no 409** | CUS-02.1/02.3/02.4, XC-07 |
| `contactVerification.test.tsx` | masked destination; wrong code "N attempts left"; `attemptsRemaining` 0 → disabled + resend; 30 s resend cooldown; 429 countdown; 503; success updates profile + returns focus; code absent from MutationCache | CUS-02.2, NFR-SEC |
| `plan.test.tsx`, `planChange.test.tsx` | collapsed fields; disclosure; pending change hides upgrade; options on demand; confirm shows date + charge; body echoes the quote; 422 refetch; idempotency-mismatch copy; one request on double click | CUS-03, NFR-REL |
| `promotions.test.tsx` | card fields; CTA iff `eligibleSubscriptionIds` non-empty; "Claimed for number ending …"; claim needs number + terms; `termsHash` sent; 404/422 refetch | CUS-04 |
| `requestList.test.tsx`, `createRequest.test.tsx` | default call has no dates; custom range; reference search; empty actions; read-only contact; subcategory rule; Email rule; E10 error/empty; double click → one POST; confirmation | CUS-05, CUS-06 |
| `paymentHistory.test.tsx` | columns; Receipt only when available; View for PENDING/REVIEW | CUS-07 |
| `payBill.test.tsx` | outstanding shown; `expectedAmount` = server string; redirect same-origin OK, `https://evil.example/x` and `javascript:` refused; 409/422/503/404 copy; stored PENDING → "Continue payment" re-sends the same key; persisted `pageshow` resets busy + refetches | CUS-08.1/08.2/08.4 |
| `paymentStatus.test.tsx` | polls every 3 s until SUCCEEDED; **PENDING → 500 → PENDING → SUCCEEDED keeps the status + notice**; 2 min → "Still waiting"; FAILED/EXPIRED 24 h note; REVIEW; query string ignored; expired session → login `returnTo` | CUS-08.3–08.5, XC-07 |
| `receipt.test.tsx` | fields, print, 422 | CUS-07.4 |
| e2e `customer-portal.spec.ts` | customer 1: dashboard cards, plan expand, history page 2 keeps the filter, request create → reference found by search; 3 viewports + axe | CUS-01/03/05/06/07, XC-08 |
| e2e `payment.spec.ts` | customer 5: pay invoice A → mock "Fail" → URL is `/customer/payments/PAY-…` (E4) → FAILED → pay invoice B → "Pay (UPI)" → polling → Paid → receipt | CUS-08, CUS-07.4 |
| e2e `forbidden.spec.ts` (extended) | IN001 on `/customer/requests/new`, `/customer/payments/pay` → 403 page | XC-02 |

## 9. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
| 1 | Receipt "download" format (CUS-07.4; backend OQ 9 JSON)? | Printable page + browser "Save as PDF" |
| 2 | Status-page polling? | Every 3 s for 2 min, then "Check again"; refetch on focus |
| 3 | TanStack Table for these lists? | Not in 02 (fixed columns, one server sort); first used in 04 |
| 4 | Accept a 10-digit Indian number for the secondary mobile? | Yes, normalised to `+91…` |
| 5 | OTP 422 has no machine-readable reason | Map by `attemptsRemaining` + show `detail`; `reason` can be added to the contract later |
| 6 | Severity selector shown in the mock (CUS-06.4)? | Not an input; system-set (§14 #4) |
| 7 | Number picker on create request? | Only with > 1 subscription; default "Not specific" |
| 8 | e2e data reset | Flyway clean + migrate in the `e2e` profile + disjoint seed customers (§7, §10 D2) |
| 9 | "Premium customer" badge in the mock? | Omitted (no data) |
| 10 | Mock's "All requests" tab? | Replaced by "Custom range" (BR-06) |
| 11 | Page size? | 20 on lists, 3 on dashboard cards |
| 12 | Payment redirect targets? | Same-origin + `PAYMENT_GATEWAY_ORIGINS` allowlist (empty in 02) |

## 10. Dependencies outside this design (backend errata, awaiting approval)
| # | Needs | Why |
|---|---|---|
| D1 | E4: `returnUrl = {return-url-base}/customer/payments/{reference}`, asserted by `MockCheckoutIT`; `e2e` profile `return-url-base` = e2e base origin | F1, gateway return |
| D2 | `e2e` profile: Flyway `cleanDisabled=false` + clean before migrate at startup (`ProfileGuard` rejects it with `prod`). Dev seed: customer 1 (`+919800000001`) has ≥ 21 cases and ≥ 21 payments inside the last 3 months (dates relative to `NOW()`, incl. one SUCCEEDED) and ≥ 1 OPEN invoice; new customer 5 (`+919800000005`, linked user) with **2 OPEN invoices** and nothing else | F10, e2e reproducibility |
| D3 | E8 `Promotion`: `eligibleSubscriptionIds` excludes already-claimed subscriptions; new `claimedSubscriptionIds: int64[]`; `claimed` = claimed on at least one | F11, per-number claim state |

Until D3 is approved, the FE uses only `eligibleSubscriptionIds` (CTA rule above) and maps a 422 on an already-claimed number by refetching.
