# 04 Outbound assistant workspace — frontend design

- Status: **Draft — Proposed** (awaiting approval) · Date: 2026-11-28
- BRD: OUT-01..OUT-06, BR-03, BR-04, BR-05/06, BR-10, XC-02..XC-08, NFR-PRIV, NFR-REL, NFR-SEC, §14 #3, #7, #12
- Backend contract: [`openapi.yaml`](openapi.yaml) O1–O13, [`backend.md`](backend.md) (Approved rev 1). Review dispositions:
  `docs/slices/04-outbound/review.md` (**note:** that file and its fix commit `3c33320` are on `slice/04-outbound-be` only and are **not
  in `main`** until PR #22 merges (PR #21 merged `bb2bcc5`). So on `main`, an agent who changes the status of an unassigned case gets 403, not 422 `unassigned`
  (review #3). A supervisor's `publicComment` is false on unassigned cases (#4), and the `openapi.yaml` header still says Draft (#7). This
  design handles both behaviours, §6.)
- Precedent (not restated): [`../03-inbound/frontend.md`](../03-inbound/frontend.md) (Approved rev 2 + FE1: route children with per-route
  `params`, states, 403 rule, `statusLabel` `caseInternal`, `DataTable`, `DateRangeFilter`, `Pagination`, `useListParams`,
  `useIdempotencyKey`, `useSubmitGuard`, `OWN_FORBIDDEN_HANDLING`, §6 common error row) and [`../02-customer/frontend.md`](../02-customer/frontend.md).
  03 FE review lessons apply ([`review-frontend.md`](../../slices/03-inbound/review-frontend.md) #1–#4). L1: never show removed or
  out-of-scope data as a placeholder. L2: forms and dialogs keep their input across refetches. L3: notices that must survive a refetch sit above the refetched part.
- Visual: `docs/brd/images/out-bus-workspace.png` (5.8 case list + case details) · Process: tests **deferred** (user, 2026-11-27), §7.

## 1. Scope
**In:** the queue with summary counts, filters, sort and paging (O1–O4); case detail with customer context and timeline (O5); claim (O6),
reassign (O7), status change (O8), notes and customer comments (O9); bookmarks: toggle, view, changed indicator, mark seen (O10–O13);
generated 04 types.
**Out:** routing/unassign (backend OQ 2/18), customer reopen (OQ 13), email notifications (OQ 8), analytics (05), SLA pause, every test (§7).

Layout: `src/features/outbound/{queue,case,bookmarks}/` (`queries.ts` per folder) · `src/stores/outboundQueueStore.ts` (§5) ·
`src/lib/outboundText.ts`. Only OUTBOUND_AGENT holds `case.read.queue` (SUP001 = OUTBOUND_AGENT + OUTBOUND_SUPERVISOR). Every other role
gets `ForbiddenPage` on every route below, with no API call (XC-02).

## 2. Routes and navigation
The two existing nav items lose their ComingSoon state (pages registered in `pageRegistry`). There are no new sidebar entries.

| Route (id) | Screen | Required (all of; + parent's) | Crumb | BRD |
|---|---|---|---|---|
| `/outbound` (`outbound-queue`, nav) | Queue | `case.read.queue` | Case queue | OUT-01, OUT-02, OUT-03.1 |
| `/outbound/cases/:reference` (`outbound-case`, child of `outbound-queue`) | Case detail | — (inherits) | `SR-…` | OUT-03..OUT-05, OUT-06.1/06.4 |
| `/outbound/bookmarks` (`outbound-bookmarks`, nav) | Bookmarks | `case.read.queue` + `case.bookmark` (was `case.bookmark` only; the server needs both, backend §4) | Bookmarks | OUT-06 |

- `outbound-case` declares `params: { reference: /^SR-\d{8}-\d{6,}$/ }` (03 FE1; `INBOUND_PARAM_PATTERNS.reference` is reused). A mismatch renders
  `NotFoundPage` with no API call. `/outbound/bookmarks` and `/outbound/cases/:reference` never collide (different segments).
- The detail's back link comes from `location.state.from` (the queue URL with its search params, or `/outbound/bookmarks`), default `/outbound`.
- Titles: "Case queue", "Case SR-…", "Bookmarks". A mobile number never appears in a URL, query key, crumb or title (NFR-PRIV).

## 3. Screens
States follow 02/03 §3 (skeleton · `EmptyState` · `ErrorState` with correlation id · validation · success) and the 403 rule.
**Refetch rule (L1, L2):** a page shows its skeleton only while it has no data (`isPending`). A background refetch never unmounts a form or
dialog. A refetch that fails with data present keeps the page and shows an inline "Couldn't refresh (time)". A refetch that fails with
**404** removes the cached detail and shows the not-found state, because the case left the actor's scope.
XC-08: tables become cards below `md` (as `DataTable`); dialogs trap focus, close on `Esc` and return focus to their trigger; every badge
carries text, not colour only. Changes are announced in one polite live region per page.

### 3.1 Queue `/outbound` (OUT-01, OUT-02, OUT-03.1)
```
[Summary tiles: New · Open · In progress · Pending · Resolved · Closed · Critical · Overdue]   "Counts for cases created 28 Aug – 28 Nov 2026 (IST)"
[Bookmark notice (§3.4)]  [Queue notice region (claim results, L3)]
[Filters]  Created ▾ · Updated ▾ · Status ☐… · Severity ☐… · Category ▾ · Assigned to ▾ · Customer mobile · Case ID · ☐ Overdue only   [Apply] [Clear all]
"124 matching cases · Created 28 Aug – 28 Nov 2026 (default)"   Sort ▾   [Refresh]
[DataTable]  [Pagination]
```
**Summary (O1, OUT-01):** 8 tiles. Each is a toggle `button` (`aria-pressed`, name "Overdue: 3 cases"). O1 gets the URL's created range
(if any); the caption shows the returned `window`. A tile is a **filter shortcut**. It replaces the status, severity and overdue filters,
keeps the other filters and resets the page. Pressing the active tile clears it.

| Tile | Count | Shortcut sets |
|---|---|---|
| New · Open · In progress · Pending | `new` · `open` · `inProgress` · `pending` | `status` = [CREATED] · [ASSIGNED, REOPENED] · [IN_PROGRESS] · [PENDING_CUSTOMER, PENDING_EXTERNAL] |
| Resolved · Closed | `resolved` · `closed` | `status` = [RESOLVED] · [CLOSED] |
| Critical | `critical` | `severity` = [CRITICAL], `status` = the 6 non-terminal statuses |
| Overdue | `overdue` (danger tone when > 0) | `overdue` = true |

A tile is active when the URL filters equal exactly its shortcut. Counts ignore the other filters. The caption says "Counts for cases
created …", and backend R1 counts only within the scope and the window (OQ 4).

**Filters (O2, OUT-02):** applied with [Apply] (one search per apply, not per keystroke). Any change resets the page. Everything except the
mobile number lives in the URL (`useQueueParams`, §4). The mobile number goes in `outboundQueueStore` (§5) and is sent only in the O2 body.

| Filter | Control | URL | O2 body |
|---|---|---|---|
| Created | `DateRangeFilter` (default "Last 3 months") | `from`, `to` | `createdFrom/To` (absent → server default, echoed) |
| Updated | `DateRangeFilter` with `defaultLabel="Any time"` (§4) | `ufrom`, `uto` | `updatedFrom/To` |
| Status · Severity | checkbox groups (8 `caseInternal` labels · 4 severities) | repeated `status`, `severity` | `status[]`, `severity[]` |
| Category | `Select` from O3, grouped by top level; a top level means "this and its subcategories"; INTERNAL ones carry "(Internal)" | `category` | `categoryCode` |
| Assigned to | `Select`: Anyone · Me · Unassigned · agents from O4 ("Name — Team") | `assignee` = `me` \| `unassigned` \| `<userId>` | `assignee` ME/UNASSIGNED/USER (+`assigneeUserId`), absent = ANY |
| Customer mobile | input (`inputMode=tel`, `autocomplete=off`), 03 `msisdnSchema` (10 digits → `+91…`) | **never** | `customerMsisdn` |
| Case ID | input, 02 `requestReferenceSchema` | `ref` | `reference` |
| Overdue only | checkbox | `overdue=1` | `overdue` |
| Sort | `Select`: Created newest (default) / oldest · Updated newest / oldest · SLA due soonest / latest · Severity highest / lowest (= `QueueSort`) | `sort` | `sort` |

- Applied filters show as removable chips above the table. The mobile chip shows "Mobile ••••0001" (last 4 only).
  **Clear all** (OUT-02.1) clears the URL filters and the store, and resets sort and page.
- Total: "N matching cases" (`totalItems`, OUT-02.2) + the echoed `filters.created` / `filters.updated` ("(default)" when the URL has no
  created range). Paging, sort and Back keep every filter (URL + store).
- **Columns (OUT-01.1):** Case ID (link) · Customer (name, customer ref, masked mobile) · Category › subcategory · Severity · Created ·
  Assigned to (`ownerName`, else "Unassigned") · Status (`caseInternal` badge + "Customer sees: …") · Last updated · SLA ("Overdue" danger
  badge when `overdue`, else due time) · a bookmark icon with sr-text "Bookmarked" when `bookmarked` · Actions: [Claim] (§3.3) and [Open].
  Times are in IST (`formatDateTime`).
- States: skeleton rows · empty "No cases match these filters" + [Clear all] (no filters: "No cases in your queue in the last 3 months" +
  "Change dates") · `ErrorState` + Retry · a 400 shows the server's `detail` as a filter error, and the last good page stays (03 §6) ·
  `DataTable busy` while refetching. `staleTime` 30 s, refetch on focus, [Refresh] button. There is no polling (OQ 3).

### 3.2 Case detail `/outbound/cases/:reference` (OUT-03..OUT-05)
```
← Back   SR-… · Subject                                [☆ Bookmark]
[Status badge + "Customer sees"] [Severity] [Overdue]   Actions: [Claim] [Reassign] [Change status]
[Action notice region (L3)]
Main (lg: 2/3): Description · Activity timeline · Note composer        Side (lg: 1/3): Customer · Case · Resolution
```
- **Customer (OUT-04, OUT-04.4):** name, customer ref, account ref, account status (`statusLabel` `account`). Contact number: if
  `contactMsisdn` is set (assignee only), show it in full as a `tel:` link. Otherwise show `maskedMsisdn` with the hint "Full number shown to
  the assigned agent only". Preferred contact (Phone/SMS/Email). No email or address is ever shown.
- **Case:** category › subcategory, type (Request/Complaint), channel + creator "Created by {creatorName} via {Customer portal|Inbound
  agent}" (OUT-04.2), created, last updated, response due, SLA due (+ Overdue), owner (`assigneeName` · `teamName`, or "Unassigned"),
  "Reopened N times" if `reopenCount` > 0.
- **Resolution** (when `resolvedAt`): resolved at/by, remarks (labelled "Internal — not shown to the customer", backend OQ 11), closed at,
  closure type ("Customer confirmed" / "Verification window elapsed").
- **Activity** (OUT-03.4, OUT-04.1/04.3): 03 `ActivityTimeline` (oldest first, Internal/Visible badges), extended for 04. ASSIGNED with an
  old value reads "Reassigned from A to B", and without one "Assigned to B". Its `note` is the reason. A NOTE reads "Internal note" or
  "Comment to the customer", by visibility.
- **Actions** come only from `allowedActions` (server-computed, the backend re-checks). [Reassign] also needs `Can case.assign.any`. A
  hidden action is not rendered (it is not shown disabled). If there are no actions and no composer: "View only. Claim this case to work on it." or "Owned by {name}".
- 404 → `ErrorState` "This case isn't in your queue" + back link (the cached detail is removed, L1).
- **Mark seen (OUT-06.4):** after the first successful O5 load with `bookmarked: true`, call O13 once per mount (404 ignored), then invalidate
  the bookmark keys. Own writes clear the flag server-side.

### 3.3 Work actions (OUT-03, OUT-04.1, OUT-05; XC-06, XC-07)
None is optimistic. A 200/201 response replaces the case detail (`setQueryData` with the returned detail, or an O5 refetch after O9) and
invalidates the queue, summary and bookmark keys. Mutations use `useSubmitGuard`, `meta: OWN_FORBIDDEN_HANDLING`.

**Claim (O6; queue row and detail).** Detail: shown when `allowedActions.claim`. Queue row: shown when `assignedUserId === null`, the status is not
RESOLVED/CLOSED, and `Can case.assign`. No confirmation (self-assignment, OQ 6). Row and detail buttons have the accessible name "Claim SR-…".

| O6 result | Shown (in the notice region above the refetched part, L3) | Then |
|---|---|---|
| 200 | "SR-… is now assigned to you." (queue: + [Open]) | detail replaced; queue + summary invalidated |
| 409 `already_assigned` | warning "Someone else claimed SR-… first. The case has been refreshed." | refetch (row shows the new owner) |
| 422 `case_closed` | "SR-… is resolved or closed and can't be claimed." | refetch |
| 404 | "SR-… is no longer in your queue." | refetch queue / detail 404 state |

**Reassign dialog (O7; `allowedActions.reassign`, SUP001).** The header shows "Currently: {owner or Unassigned} · {status}" from live
data. **Assign to**: `Select` from O4, excluding the current assignee. **Reason**: textarea 5–500, with the hint "Saved in the audit log.
Don't include personal data." (backend OQ 17). [Reassign] is the confirmation (XC-06). It sends the detail's `version` **as read at submit
time**.

| O7 result | Shown | Then |
|---|---|---|
| 200 | toast "SR-… reassigned to {name}" | close; detail replaced |
| 409 `stale_version` | root "This case changed while you were working. Check the latest details and submit again." | refetch O5; dialog stays open with its input (L2) |
| 422 `invalid_assignee` · `no_change` · `case_closed` | field "This agent can't take cases now" (refetch O4) · field "Already assigned to this agent" · root "Resolved or closed cases can't be reassigned" | |
| 400 `errors[]` | `reason` / `assigneeUserId` field messages | |

**Status dialog (O8; `allowedActions.transitions` non-empty).** Step 1 Edit: **New status** = `RadioGroup` of the allowed transitions
only (`caseInternal` labels, current status shown as text). Its fields depend on the target:

| Target | Fields (§6) | High-risk confirm step (XC-06) |
|---|---|---|
| IN_PROGRESS, PENDING_CUSTOMER, PENDING_EXTERNAL | Remarks optional ≤ 1000 | no; [Change status] submits |
| RESOLVED (OUT-05.2) | **Resolution remarks** required 10–1000, hint "Internal. To tell the customer, add a customer comment." | yes |
| CLOSED (OUT-05.3/05.4) | **How was closure confirmed?** Customer confirmed → remarks required 10–1000 ("How did the customer confirm?") · Verification window elapsed → remarks optional. The second option is shown only when `resolvedAt` + 3 days ≤ now (display hint only; the server decides) | yes |
| REOPENED (§14 #7) | **Reason** required 10–1000. Shown only if the server offers it (CLOSED within 7 days, or RESOLVED) | yes |

Step 2 Review (same dialog, focus to its heading): "Move SR-… from {A} to {B}?" + the closure type + a remarks preview, and
consequences: Resolved → "The customer will see Resolved. It closes automatically after the verification window."; Closed → "The
customer will see Closed. It can be reopened within 7 days."; Reopened → "The customer will see In progress again." Then [Back] and
[Confirm {B}].

| O8 result | Shown | Then |
|---|---|---|
| 200 | toast "Status changed to {B}"; polite announcement | close; focus to [Change status]; detail replaced |
| 409 `stale_version` | root "This case changed while you were working…" (as O7) | refetch; dialog back to step 1 with its input; a target no longer allowed → field "Not allowed from {new status}" and submit disabled |
| 422 `invalid_transition` | as the 409 target message | refetch |
| 422 `closure_condition_unmet` | field on closure type "The verification window hasn't ended yet. Choose Customer confirmed, or wait for automatic closure." | |
| 422 `reopen_window_expired` · `case_closed` | root "Closed more than 7 days ago; it can't be reopened." · root `detail` | refetch |
| 422 `unassigned` **or** 403 (non-owner, incl. unassigned on `main`, header note) | root "Only the assigned agent or a supervisor can change this case. Claim it first if it's unassigned." | refetch (actions update) |
| 400 `errors[]` | `remarks` / `closureType` / `toStatus` fields | |

**Note composer (O9; OUT-04.1, BR-04).** Rendered when `internalNote || publicComment`. **Type** `RadioGroup`: "Internal note: staff
only" (lock icon) if `internalNote`. "Comment to the customer: shown in their portal" (eye icon) if `publicComment`. The default is Internal
when it is allowed. With one option the type shows as text. Text: 1–2000, with a counter. The button reads "Add internal note" or **"Post to customer"**, and the
composer border matches the timeline badge style. The key is ``useIdempotencyKey('CASE_NOTE_ADD', `/outbound/cases/${reference}/notes`)``;
the key and text are reset on 201. The composer is mounted once O5 has loaded and stays mounted during refetches (L2).

| O9 result | Shown | Then |
|---|---|---|
| 201 | announcement "Note added" / "Comment posted to the customer" | O5 refetch; form + key reset |
| 403 | root "You can't add this type of note on this case." | refetch O5 (options update); input kept |
| 422 `case_closed` | root "The case is closed: customer comments are off. Internal notes still work." | refetch |
| 409 · `idempotency-mismatch` · 400 | "Still saving…" · 03 §6 common row (fresh key) · field `text` | |

**Bookmark toggle (O10/O11; `Can case.bookmark`).** A toggle `button` with `aria-pressed`, labelled "Bookmark" / "Bookmarked". It is
disabled while pending and not optimistic. 204 → detail `bookmarked` updated, bookmark keys invalidated. 422 `bookmark_limit` → notice "You can bookmark up
to 200 cases. Remove some on the Bookmarks page." Ownership never changes (OUT-06).

### 3.4 Bookmarks `/outbound/bookmarks` (OUT-06.2–06.4)
- O12 paged (20, `?page` via `useListParams` with no extra filters). There is no date filter (backend OQ 15). Heading + polite status "N
  updated since you last looked" (`changedCount`).
- Columns: Updated indicator (dot + text badge "Updated", not colour only) · Case ID (link, `state.from` = this page) · Customer · Category
  · Status (badge + "Customer sees") · Severity · Assigned to · Last updated (OUT-06.3) · Bookmarked on · [Remove].
- `accessible: false` → row "SR-… · No longer in your queue" + [Remove], no link and no other data (L1).
- Remove → O11 → invalidate. If the page becomes empty, go to the previous page. Empty: "No bookmarks yet. Use Bookmark on a case to follow it."
- **Queue notice:** when `changedCount` > 0, the queue page shows "{n} bookmarked case(s) updated since you last looked. [View
  bookmarks]" from O12 `size=1` (refetch on focus) (OQ 5). There is no nav badge.

### 3.5 Role differences (OUT-01.4, OUT-03.3; server-enforced, the UI only reflects them)
| Capability | OUT001 (OUTBOUND_AGENT) | SUP001 (+ OUTBOUND_SUPERVISOR, `case.assign.any`) |
|---|---|---|
| Queue / summary scope | own + own team + unrouted pool | all cases |
| "Assigned to" agent list (O4) | own team | all queue agents |
| Claim · bookmarks · internal notes | yes | yes |
| Reassign | hidden (`allowedActions.reassign` false) | yes |
| Status change · customer comment | only on own cases | any case (per `allowedActions`) |
| Full contact number | own cases only | own cases only (backend R4) |

## 4. Shared components and helpers introduced or extended
| Component / helper | API | Used by |
|---|---|---|
| `useQueueParams()` (`features/outbound/queue`) | the §3.1 URL params ⇄ typed filters + `toSearchBody()`, following `useListParams` rules: invalid → dropped + `INVALID_LINK_NOTICE`, range rules via `validateRange`, a filter change resets the page, `LIST_PAGE_SIZE`. Unknown `category` / `assignee` ids are dropped once O3/O4 have loaded | queue |
| `outboundKeys` | `['outbound','summary',window]`, `['outbound','queue',urlFilters,mobileSeq]`, `['outbound','case',ref]`, `['outbound','categories']`, `['outbound','agents']`, `['outbound','bookmarks',page\|'changed']` | queries |
| `SummaryTiles` | `{summary, active, onShortcut}` | queue |
| `ClaimButton` / `useClaimCase(reference)` | §3.3 claim table; reports to the page's notice region | queue row, detail |
| `ReassignDialog`, `StatusDialog`, `NoteComposer`, `BookmarkToggle` | `{detail}` (read live from the case query) | detail |
| `ActivityTimeline` (03, extend) | ASSIGNED "Reassigned from A to B" + reason; NOTE "Internal note" / "Comment to the customer" | 03 + 04 detail |
| `DateRangeFilter` (extend) | `defaultLabel?: string` (default "Last 3 months"); "Any time" sends no dates | queue Updated |
| `src/lib/outboundText.ts` | `closureTypeText`, `caseTypeText`, `channelText`, `preferredContactText`, `queueSortText`, `caseProblemText(reason)` (the copy in §3.3) | 04 |
| `idempotency.ts` (extend) | `IdempotentOperation` += `CASE_NOTE_ADD` (in memory) | composer |
| Types | `gen-api.mjs` += `04-outbound`; `types/api/index.ts`: `paths04` in `paths`, + `QueueSummary`, `QueueSearch`, `QueueSort`, `AssigneeFilter`, `AppliedFilters`, `CaseCustomer`, `OutboundCaseSummary`, `OutboundCasePage`, `QueueCategory`, `QueueAgent`, `OutboundCaseDetail`, `AllowedActions`, `ClosureType`, `ReassignRequest`, `StatusChangeRequest`, `NoteRequest`, `Bookmark`, `BookmarkPage`, `CaseProblem`. `CaseStatus`, `CaseActivity` and `Severity` stay on their 02/03 names | all |

## 5. Client state
| State | Where | Why |
|---|---|---|
| All API data | TanStack Query `outboundKeys`; `staleTime` 30 s (detail 0 s, so every open is fresh); the queue uses `keepPreviousData` for paging only. A 404 detail is `removeQueries`'d (L1) | server state |
| Queue filters, sort, page; bookmarks page | URL | XC-04 |
| Mobile number filter `{msisdn, seq}` | Zustand `outboundQueueStore`, **memory only** (no persist), cleared by Clear all and on logout (`useLogout`). `seq` increments per apply and is the only mobile-related part of the query key | UI filter input that must stay out of the URL and keys (NFR-PRIV). It survives queue ⇄ detail navigation but not a reload (OQ 2) |
| Dialog step, form input, idempotency key, notices | component state (page-level, above the refetched parts) | transient (L2, L3) |

No `localStorage`/`sessionStorage` in this slice. Mutations are never optimistic (XC-07).

## 6. Forms and validation
The 02/03 §6 common row applies. A 403 on a mutation → its root message (§3.3), never `ForbiddenPage`. A `CaseProblem.reason` maps via
`caseProblemText`. An unknown reason → the root `detail`. Contract drift (header note): the UI treats 403 and 422 `unassigned` the same,
and follows `allowedActions` for `publicComment`, so both the `main` and the reviewed backend behave correctly.

| Form | Zod schema fields | Server error mapping |
|---|---|---|
| Queue filters | `created`/`updated` via `dateRangeSchema` (from ≤ to, ≤ 366 days, IST); `status` ⊆ `CaseStatus`; `severity` ⊆ `Severity`; `category` `^[A-Z0-9_]{1,32}$`; `assignee` `me\|unassigned\|^\d{1,15}$`; `msisdn` = 03 `msisdnSchema` or empty; `ref` = 02 `requestReferenceSchema` or empty; `sort` ∈ `QueueSort` | 400 → filter `Alert` with `detail`, last data kept |
| Reassign | `assigneeUserId` int required, ≠ current; `reason` trim 5–500 | §3.3 O7 table |
| Status | `toStatus` ∈ `allowedActions.transitions`; `closureType` required iff CLOSED; `remarks` trim ≤ 1000, `''` → `null`; required 10–1000 for RESOLVED, REOPENED, CLOSED+CUSTOMER_CONFIRMED | §3.3 O8 table |
| Note | `visibility` ∈ the allowed options; `text` trim 1–2000 | §3.3 O9 table |

Errors use `aria-describedby`/`aria-invalid`. Focus moves to the first invalid field or the root `Alert`. Counters are as in 02.

## 7. Deferred tests (user decision: written after development; listed in `docs/testing/test-plan.md`)
| Test (Vitest/RTL, Playwright) | Would prove | BRD |
|---|---|---|
| `outboundRoutes.test.tsx` | CUSTOMER/IN001/BUS001 → `ForbiddenPage` on all §2 routes with no API call; `PAY-…` on the case route → NotFound | XC-02, BR-10 |
| `queueParams.test.ts` | URL ⇄ filters; invalid params dropped + notice; tile shortcuts; Clear all; the MSISDN is never in the URL or a key | OUT-02, NFR-PRIV |
| `queuePage.test.tsx` | summary tiles, totals + echoed window, empty/error/400 states, row claim 200/409 notice survives refetch | OUT-01, OUT-03.1 |
| `caseDetail.test.tsx` | masked vs full number; actions only from `allowedActions`; 404 → no stale data; mark-seen once | OUT-04, OUT-06.4 |
| `statusDialog.test.tsx` | remarks rules, closure options, confirm step for RESOLVED/CLOSED/REOPENED, 409 keeps input, 403/`unassigned` copy | OUT-05, XC-06 |
| `reassignDialog.test.tsx`, `noteComposer.test.tsx` | O7 errors; INTERNAL/PUBLIC options; double click → one POST; key kept on 409/403 | OUT-03.2, OUT-04.1, NFR-REL |
| `bookmarks.test.tsx` | changed badge + count, inaccessible rows, remove, limit message | OUT-06 |
| e2e `outbound.spec.ts` (OUT001, OUT002, SUP001) | two agents claim 900401 → one wins, the other sees the notice; resolve → close (customer confirmed); SUP001 reassigns; bookmark changed → seen | OUT-01..06 |

## 8. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
| 1 | Where the mobile filter lives (it can't be in the URL) | Memory-only Zustand store, cleared by Clear all/logout. Survives detail ⇄ queue, lost on reload. The chip shows the last 4 digits only |
| 2 | Keep the mobile filter across a reload (`sessionStorage`)? | No (no PII in browser storage, as 03 §5) |
| 3 | Live queue updates | No polling: refetch on focus and after own writes, plus [Refresh] |
| 4 | Should summary counts follow every filter? | No: scope + created window only (backend R1), stated in the caption |
| 5 | How to notify about changed bookmarks (OUT-06.4, §14 #12) | `changedCount` on the Bookmarks page + a notice on the queue page; no nav badge, no email |
| 6 | Which actions need a confirmation step (XC-06) | RESOLVED, CLOSED and REOPENED get a review step; reassign's dialog submit is its confirmation; claim and the other transitions have none |
| 7 | After a claim from the queue | Stay on the queue with "now assigned to you · [Open]" (several cases can be claimed) |
| 8 | Show "Verification window elapsed" before 3 days? | Hidden until `resolvedAt` + 3 days (display hint; config value per backend OQ 4); the server stays the authority |
| 9 | Page sizes | 20 (queue, bookmarks) |
