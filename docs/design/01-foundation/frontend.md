# 01 Foundation — frontend design

- Status: **Approved** (revision 2) · Date: 2026-11-24
- BRD: §15 phase 1, §10, §10.1, §10.2, §16 (application shell), XC-01, XC-02, XC-03, XC-07, XC-08, BR-10, NFR-A11Y, NFR-RESP, NFR-OBS, NFR-MAINT
- Backend contract: [`openapi.yaml`](openapi.yaml), [`backend.md`](backend.md) §3, §4.2, §4.3, §5, **§12 erratum E2 (prerequisite, awaiting approval)** · Conventions: [`../00-overview.md`](../00-overview.md) · ADR 0001, 0003
- Visual direction: `docs/brd/images/00-overview.png`, `docs/brd/images/cus-01-dashboard.png` (dark navy sidebar, white header, card content)

## 1. Scope
**In:** Vite + React + TS project skeleton (`frontend/`), design tokens and shadcn/ui primitives, layout (AppShell, Sidebar, Header,
PageContainer), state components (XC-03), login/logout, session bootstrap and expiry, typed API client, permission-filtered navigation,
route guards with 403/404 pages, "coming soon" placeholders for every later-slice nav entry, unit and e2e tests.
**Out:** any feature screen or feature API call (slices 02–05), dashboard contents, notification centre (§16, slices 03–04), dark theme,
client error-reporting endpoint and web-vitals (06, OQ 8/10), MFA/step-up (06). Exit criterion (§15): all 7 role combinations log in and
see exactly their nav; route and API permission tests pass.

Stack choices: router = React Router v7 in data mode (`createBrowserRouter`, route `loader`, `handle`, `errorElement`, `router.navigate`);
linter = oxlint (`npm run lint`, run in the Linux container like every npm step, CLAUDE.md); Vitest, TanStack Query, Zustand, Zod, shadcn/ui.

Folder layout (§10.1): `src/app/` (providers, router, `routes.tsx`), `src/components/{ui,layout,states}/`, `src/features/authentication/`,
`src/permissions/` (`navConfig.ts`, `usePermissions`, `Can`, `RequirePermission`), `src/services/api/` (client, errors, csrf, `authChannel`),
`src/stores/` (`uiStore.ts`), `src/types/api/` (generated, never edited), `src/styles/` (`globals.css`), `e2e/` (Playwright).

## 2. Routes and navigation
One typed registry (`src/permissions/navConfig.ts`) drives both the sidebar and the protected routes, so a nav entry and its route
guard can never disagree. `Permission` is the generated `components['schemas']['Permission']` type.

```ts
type Section = 'customer' | 'inbound' | 'outbound' | 'operations' | 'business';
type NavItem = { id: string; label: string; icon: LucideIcon; path: string; section: Section | ((p: Set<Permission>) => Section);
                 requires: readonly Permission[] /* all-of, never empty */; userType?: 'CUSTOMER'; brd: string; slice: '02' | '03' | '04' | '05' };
```

| Route | Screen | Required (all of) | Nav entry (section · label) | Slice 01 content |
|---|---|---|---|---|
| `/login` | Login | public (signed-in user → landing) | — | live |
| `/` | Landing redirect | session | — | redirect to the first visible nav item; none → `NoAccessPage` |
| `/customer` | Customer home (CUS-01) | CUSTOMER + `customer.profile.read` | Customer · Home | ComingSoon |
| `/customer/profile` | Profile (CUS-02) | CUSTOMER + `customer.profile.read` | Customer · Profile | ComingSoon |
| `/customer/plan` | My plan (CUS-03) | CUSTOMER + `plan.read` | Customer · My plan | ComingSoon |
| `/customer/promotions` | Promotions (CUS-04) | CUSTOMER + `promotion.read` | Customer · Promotions | ComingSoon |
| `/customer/requests` | Service requests (CUS-05/06) | CUSTOMER + `case.read` | Customer · Service requests | ComingSoon |
| `/customer/payments` | Payment history (CUS-07/08) | CUSTOMER + `payment.read` | Customer · Payment history | ComingSoon |
| `/inbound` | Customer search / workspace (INB-01..04) | `customer.lookup` | Inbound · Customer search | ComingSoon |
| `/outbound` | Case queue (OUT-01..05) | `case.read.queue` | Outbound · Case queue | ComingSoon |
| `/outbound/bookmarks` | Bookmarks (OUT-06) | `case.bookmark` | Outbound · Bookmarks | ComingSoon |
| `/analytics` | Case analytics (BUS-01/02; outbound "operational view", §8) | `analytics.read` | Operations (holder of `case.read.queue`) or Business · Analytics | ComingSoon |
| `/audit` | Audit and export (BUS-03) | `audit.read` | Business · Audit & export | ComingSoon |
| `*` | NotFoundPage | session (signed out → `/login?returnTo=`) | — | live, inside the shell |

- **Decided (planner decision 1):** the customer portal is gated on `/me.userType === 'CUSTOMER'` plus each page's read permission. Agents
  also hold `customer.profile.read`, `plan.read`, `case.read`, `payment.read` but reach that data through their own workspace. No new
  backend permission: `userType` is already in `Me`, the gate is UX only, and the server enforces every API (BR-10, backend §5).
- Guard order: no session → `/login?returnTo=<path>`; session without the permission/user type → `ForbiddenPage` rendered **at the
  requested URL** (no redirect, XC-02); unknown path → `NotFoundPage` (signed out: login first, so the route space isn't revealed).
- Sections render in the order customer → inbound → outbound → operations → business; headings show only when the user has > 1 section.
  Heading texts: "Customer", "Inbound", "Outbound", "Operations", "Business" (pinned in `navFilter.test.tsx`).

Resulting nav and landing per role combination (backend §3.1):

| Role combination (seed user) | Sidebar items (headings) | Landing |
|---|---|---|
| CUSTOMER (`+919800000001`…`04`) | Home, Profile, My plan, Promotions, Service requests, Payment history (no heading) | `/customer` |
| INBOUND_AGENT (`IN001`) | Customer search | `/inbound` |
| INBOUND_AGENT + REACTIVATION_APPROVER (`IN002`) | Customer search (reactivation is a workspace action, §16; OQ 3) | `/inbound` |
| OUTBOUND_AGENT (`OUT001`…`003`) | Outbound: Case queue, Bookmarks · Operations: Analytics | `/outbound` |
| OUTBOUND_AGENT + OUTBOUND_SUPERVISOR (`SUP001`) | same as OUTBOUND_AGENT (`case.assign.any` is an action, slice 04) | `/outbound` |
| BUSINESS_ANALYST (`BUS001`) | Analytics, Audit & export (no heading) | `/analytics` |
| BUSINESS_ANALYST + BUSINESS_AUDITOR (`AUD001`) | same (`reports.export.pii` is an export option, slice 05) | `/analytics` |

Special permissions that are actions, not pages, use `<Can allOf={[…]}>`: Phase 1 role/permission routing infrastructure, unit-tested
here, first used in slices 03–05 (OQ 1).

## 3. Screens
### Login (§16 shell, NFR-SEC UX)
- Layout: centred card on a navy background; brand "Telecom"; fields *Mobile number or employee ID*, *Password* (show/hide toggle);
  "Sign in" button. Info `Alert` for `?reason=expired` ("Your session expired. Sign in again.") and `?reason=signed-out` ("You signed out.").
- Data: `useLoginMutation` (`gcTime: 0`) → `POST /api/v1/auth/login`; on 200 see §7 "After login". The password field is reset after
  any response; mutation `variables` never go into `ApiError`, toasts or `console.*` (NFR-SEC).
- States: idle · submitting (button busy, fields read-only) · validation (§6) · server error (root `Alert`) · throttled (countdown) · success.
- A11y: real `<form>`, `<Label htmlFor>`, `aria-invalid` + `aria-describedby`; on failed submit focus the first invalid field, or the
  root alert (`role="alert"`) for server errors; autocomplete `username` / `current-password`.

### Application shell (§16, XC-01, NFR-RESP)
```
┌──────────┬───────────────────────────────────────────────┐
│ Telecom  │ [≡] Breadcrumb                 (DN) Name ▾    │  Header h-16: menu/collapse toggle, breadcrumbs, user menu
│ ▣ Home   ├───────────────────────────────────────────────┤
│ ▢ …      │ <h1>Page title</h1>        [actions]          │  PageContainer
│ ⎋ Logout │ content                                       │
└──────────┴───────────────────────────────────────────────┘
```
- Sidebar: brand, `<nav aria-label="Main">` with `aria-current="page"`, Logout at the bottom (as in the mock). User menu
  (`DropdownMenu`): display name, username, role badges, "Sign out". Notification bell not rendered (OQ 5).
- Data: `useMeQuery` only. Placeholders (`ComingSoonPage`) make no API call and show title, BRD id and "Available in slice NN".
- States: bootstrap → `FullPageSpinner`; `/me` 5xx/network → full-page `ErrorState` with retry and correlation id.

### System pages
| Page | Content | Notes |
|---|---|---|
| `ForbiddenPage` (403) | "You don't have access to this page", link to landing | URL unchanged; `document.title` "Access denied"; also rendered by a page whose query gets 403 (§7) |
| `NotFoundPage` (404) | "Page not found", link to landing | always inside the shell (signed-out users are sent to login first) |
| `NoAccessPage` | `EmptyState` "No features are assigned to your account" + Sign out | user with no nav item |
| `RouteErrorPage` | router `errorElement`: `ErrorState` with retry; logs to `console.error` (OQ 8) | never shows stack traces |

## 4. Shared components introduced
| Component | Props | Used by |
|---|---|---|
| `AppShell` | `children` (via `<Outlet/>`) | protected layout |
| `Sidebar` / `NavList` | `items: NavItem[]`, `collapsed`, `onNavigate` | AppShell, mobile drawer |
| `Header` / `UserMenu` / `Breadcrumbs` | `me`; crumbs from route `handle.crumb` | AppShell |
| `PageContainer` | `title`, `description?`, `actions?`, `children` (renders the focusable `<h1>`, sets `document.title`) | every page |
| `LoadingState` / `FullPageSpinner` | `label` (sr-only), `variant: 'skeleton' \| 'spinner'`; `aria-busy` | XC-03, all slices |
| `EmptyState` | `icon`, `title`, `description?`, `action?` | XC-03 |
| `ErrorState` | `title`, `error: ApiError \| Error`, `onRetry?` — shows `detail` and a copyable correlation id | XC-03, NFR-OBS |
| `FormRootError` | `error?: ApiError` (`role="alert"`) | forms |
| `Can` / `RequirePermission` | `allOf: Permission[]`, `userType?`, `fallback?` / `children` | actions / routes |
| `ComingSoonPage`, `ForbiddenPage`, `NotFoundPage`, `NoAccessPage` | `item?: NavItem` | §2, §3 |
| shadcn/ui primitives | Button, Input, Label, Form, Card, Alert, Badge, Avatar, DropdownMenu, Sheet, Tooltip, Skeleton, Separator, Sonner | — |

## 5. Client state
| Store (Zustand) | Holds | Why not server state |
|---|---|---|
| `useUiStore` (`persist` → `localStorage`, key `tcs-ui`, only `sidebarCollapsed`) | `sidebarCollapsed`, `mobileNavOpen` | pure view preferences, never on the server |

No auth store: `/me` in TanStack Query (`['me']`, value `Me | null`) is the only session source of truth (§10.2, CLAUDE.md). No theme
store (light only, OQ 6). Toasts use Sonner's own queue. `QueryClient`: queries `retry` only on 5xx/network (max 2), never on 4xx;
mutations never retry and are never optimistic: nothing shows as done before the server confirms (XC-07).

## 6. Forms and validation
| Form | Zod schema fields | Server error mapping |
|---|---|---|
| Login | `username`: string, trim, 1–64 ("Enter your mobile number or employee ID"); `password`: string, 1–128, not trimmed | 400 `errors[]` → field errors for `username`/`password`, others → root · 401 → root "Invalid username or password." (server `detail`) · 403 → re-bootstrap CSRF and retry once, then root generic · 429 → root "Too many attempts. Try again in N s." with live countdown from `Retry-After`, submit disabled until 0 · 5xx/network → root generic + correlation id |

`applyProblemToForm(error, setError, fields)` (in `services/api/errors.ts`) is the reusable mapping for every later form: known
`errors[].field` → `setError(field)`, the rest → `setError('root.server')`.

## 7. Auth, session and API client
| Concern | Design |
|---|---|
| Types | `openapi-typescript` generates `src/types/api/<slice>.ts` per slice spec (`npm run gen:api`, committed; `gen:api:check` fails on drift). Shared schemas (`ProblemDetail`, `Permission`, paging) live only in `01-foundation/openapi.yaml`; later specs `$ref` them (`../01-foundation/openapi.yaml#/components/schemas/…`). `services/api/client.ts` composes `paths` = `paths01 & paths02 & …` |
| Client | `openapi-fetch` `createClient<paths>({ baseUrl: '', credentials: 'same-origin' })` + middleware: non-GET/HEAD → `X-XSRF-TOKEN` read from the `XSRF-TOKEN` cookie **at send time** (backend §4.2; if absent, await one de-duplicated `GET /auth/csrf` first); every request → `X-Correlation-Id` = UUID v4 built from `crypto.getRandomValues` (works in insecure contexts such as `http://host.docker.internal`; `crypto.randomUUID` is not used) (NFR-OBS) |
| Errors | non-2xx → `ApiError { status, problem: ProblemDetail \| null, correlationId (body, else response header), retryAfterSec? }`; a non-JSON body gives `problem = null` and a generic message. `api.call()` unwraps `{data, error}` and throws `ApiError` |
| Bootstrap | `AppProviders` → `GET /auth/csrf` once → `useMeQuery` (`GET /auth/me`, `staleTime` 60 s, `refetchOnWindowFocus` true, `retry` false on 4xx, no polling). **A `/me` 401 resolves to `null` = anonymous**, not an error and no redirect |
| Re-validation | the protected layout route's `loader` runs `queryClient.fetchQuery(meQuery)` on every navigation: returns the cache if < 60 s old, else refetches `/me`. Result `null` while the cache held a user → expiry (row below) |
| 401 handler | one handler in the client middleware. **Ignored:** `POST /auth/login`, `POST /auth/logout` (own flow), the bootstrap `/me` (cache has no user) and any request made while the router is on `/login`. **Otherwise** (a user was cached): `router.navigate('/login?reason=expired&returnTo=<current>')`, then `queryClient.clear()`. On `/login` nothing re-fires, so no loop. No client timers for 30 min idle / 12 h absolute (backend §4.3; OQ 7) |
| After login | `queryClient.clear()`, then `setQueryData(['me'], me)`, `authChannel.post('login')`, then navigate to `returnTo` if safe (§7.1) **and** the new user may open it; else the landing route. Navigation only via `router.navigate()`, never `location.*` |
| Logout | `POST /auth/logout` (403 → re-bootstrap CSRF + retry once). 204 **or** 401 → `queryClient.clear()`, `authChannel.post('logout')`, close drawer, navigate `/login?reason=signed-out`. 5xx/network → error toast with correlation id, stay signed in, no cache change |
| Cross-tab | `authChannel` = `BroadcastChannel('auth')`. On a `login` or `logout` message other tabs `queryClient.clear()` and re-bootstrap `/me`; the guard then re-evaluates the current URL (anonymous → `/login?returnTo=`, other user → their permissions apply) |
| 403 on an API call | **mutation:** toast "You don't have permission to do that." with correlation id; no cache change, no navigation. **query:** the page renders `ForbiddenPage` (or `ErrorState`), no toast |

### 7.1 returnTo safety (NFR-SEC, open redirect)
Accepted only if all hold: the raw value starts with exactly one `/` (not `//`, not `/\`); it contains no control or whitespace character
(`/[\u0000-\u001F\u007F-\u009F\s]/`); `new URL(raw, location.origin).origin === location.origin`; the path is not `/login`. The target is
`url.pathname + url.search + url.hash`, passed to `router.navigate()`. Otherwise → the role landing page. `returnTo.test.ts` rejects:
`//evil.example`, `/\evil.example`, `\/evil.example`, `/\t/evil.example` (literal tab), `/\n/evil.example`, `/ /x`,
` /x` (leading space), `https://evil.example/x`, `javascript:alert(1)`, `%2F%2Fevil.example`, `/login?x=1`, empty; accepts `/outbound`,
`/customer/plan?tab=1#top`, `/%2F%2Fevil.example` (stays a same-origin path → NotFound).

## 8. Design system, responsive and accessibility
| Token (CSS variables in `globals.css`, Tailwind v4 `@theme`, shadcn naming) | Value | Rule |
|---|---|---|
| `--primary` / `--primary-foreground` | `#1D4ED8` / `#FFFFFF` | buttons, active nav; 6.7:1 |
| `--sidebar` / `--sidebar-foreground` / `--sidebar-accent` | `#0B2545` / `#E2E8F0` / `#1D4ED8` | navy rail per mock |
| `--background` / `--foreground` / `--muted-foreground` | `#F8FAFC` / `#0F172A` / `#475569` | body text ≥ 4.5:1 (WCAG AA) |
| `--success` / `--warning` / `--destructive` | `#15803D` / `#B45309` / `#B91C1C` | status chips later; text ≥ 4.5:1 on white |
| `--ring` | `#2563EB` (white on the sidebar) | `focus-visible:ring-2 ring-offset-2` on every interactive element; never remove an outline without a ring |
| Typography | system-ui stack; Tailwind type scale; body 16 px, `h1` `text-2xl font-semibold` | no web font download (OQ 9) |
| Spacing / radius | Tailwind 4 px scale; header `h-16`; sidebar `w-64` / collapsed `w-16`; page `p-4 md:p-6`, `max-w-screen-2xl`; radius `0.5rem` | — |

| Breakpoint | Behaviour (NFR-RESP) |
|---|---|
| ≥ `lg` (1024) | fixed sidebar, collapsible to an icon rail (labels via `Tooltip`), preference persisted |
| `md`–`lg` (tablet) | icon rail by default |
| < `md` (mobile) | sidebar hidden; header menu button opens a `Sheet` drawer (focus trapped, `Esc` closes, closes on navigation, focus returns to the button) |

Accessibility (XC-08, NFR-A11Y): "Skip to main content" link is the first tab stop → `<main id="main" tabIndex={-1}>`; on route
change focus moves to the page `<h1>` and `document.title` becomes "<Page> · Telecom Support"; landmarks `header`/`nav`/`main`; every
control is keyboard-operable (Radix primitives); validation messages are linked to fields and announced; `prefers-reduced-motion` disables
transitions; axe has no serious/critical violations at the three test viewports.

## 9. Build and serving
| Mode | How | Notes |
|---|---|---|
| Local dev | Vite on `:5173`, `server.proxy` `/api` → `VITE_API_TARGET` (default `http://localhost:8080`) | same origin for the browser, no CORS; dev only |
| Package (ADR 0001) | `bash scripts/check-frontend.sh build` (`npm run build` in the container → `frontend/dist`), **then** `./mvnw -Pfrontend package` | the Maven `frontend` profile copies `frontend/dist` into `static/` and fails if `frontend/dist/index.html` is missing (backend §12 E2) |
| e2e | the packaged JAR with profiles `dev,e2e` against compose MySQL; Playwright container, `E2E_BASE_URL=http://host.docker.internal:8080` | production-like; deep links and reload served by the SPA forward controller (E2). Never against Vite |

`scripts/check-frontend.sh` (E2): mounts the repo root so `gen:api:check` sees `docs/design`; default steps `ci typecheck lint test
gen:api:check`; `build` and `e2e` are opt-in (`e2e` needs the running JAR).

## 10. Test plan
Unit/component: Vitest + RTL + `@testing-library/user-event`, MSW (`msw/node`) for `/api/v1/auth/*`, `vitest-axe`. Fixtures: one `Me`
per role combination with the exact §3 permission set. E2E: Playwright against the JAR (§9), password from `E2E_PASSWORD`.

E2E login budget. A Playwright **setup project** logs each of the 7 seed users in once and saves `e2e/.auth/<user>.json`; specs reuse
it read-only. Logout and expiry tests do their own fresh login and never touch a shared `storageState` (logout kills that server session).
Login-calling specs run `serial` with `retries: 0`; a `login()` helper waits `Retry-After` on 429. The `e2e` profile raises L1 to 100/min.

| Source | Login attempts per run |
|---|---|
| setup project (7 users) | 7 |
| `session.spec`: wrong password (unknown username, so no L2 pair is touched) · logout fresh login · expiry fresh login + re-login | 1 + 1 + 2 |
| `responsive-a11y.spec`: keyboard-only login at 375×812 (other viewports reuse `storageState`) | 1 |
| **Total** | **12** (≤ 12) |

| Test (Vitest/RTL/Playwright) | Proves | BRD |
|---|---|---|
| `navConfig.test.ts` | every item has a non-empty `requires`, a unique path and a BRD id; every protected route comes from the registry; every customer item has `userType: 'CUSTOMER'` | XC-01, NFR-MAINT |
| `navFilter.test.tsx` | for each of the 7 fixtures the sidebar equals the §2 table exactly (labels, order, heading texts incl. "Operations" for outbound) and landing is correct; a STAFF fixture holding `plan.read` sees no customer item | XC-01 |
| `RequirePermission.test.tsx` | forbidden route → `ForbiddenPage` with URL unchanged; unknown → `NotFoundPage`; signed out (incl. unknown path) → `/login?returnTo=`; `Can` hides/shows | XC-02, BR-10 |
| `LoginForm.test.tsx` | empty submit → field messages, `aria-invalid`, focus first invalid; 400 mapped; 401 root error; 429 countdown (fake timers); 403 → CSRF re-bootstrap + single retry; 500 shows correlation id; success clears the cache then goes to `returnTo` or landing; password field reset; no `variables` in the MutationCache after settle | XC-03, NFR-A11Y, NFR-SEC |
| `returnTo.test.ts` | the §7.1 reject and accept lists | NFR-SEC |
| `session.test.tsx` | (a) `/me` 401 at bootstrap → `null`, no redirect when on `/login`, no repeat request. (b) expiry: render `/customer` with `/me` 200, `vi.setSystemTime(+61 s)`, `server.use(/me → 401)`, click "Profile" → the loader refetches `/me` → `/login?reason=expired&returnTo=/customer/profile`, banner shown, cache empty, login returns to `/customer/profile`. (c) the same via a `focus` event. (d) a `logout` message on `BroadcastChannel('auth')` → cache cleared, `/me` refetched | XC-02, NFR-SEC |
| `apiClient.test.ts` | `X-XSRF-TOKEN` on POST from the current cookie (rotated value picked up), absent on GET; `X-Correlation-Id` is a v4 UUID **with `crypto.randomUUID` undefined**; ProblemDetail → `ApiError`; non-JSON error; `Retry-After` parsed; 401 handler ignore list; `applyProblemToForm` | NFR-OBS |
| `apiForbidden.test.tsx` | mutation 403 → toast with correlation id, cache unchanged; query 403 → `ForbiddenPage`, no toast | XC-02, XC-07 |
| `logout.test.tsx` | 204 and 401 clear the cache, broadcast `logout` and land on `/login?reason=signed-out`; 500/network → toast, still signed in, cache kept | NFR-SEC |
| `AppShell.test.tsx` | skip link is the first tab stop; focus moves to `<h1>` on navigation; title updates; drawer opens, `Esc` closes, focus returns; collapse persisted; axe clean | XC-08, NFR-A11Y, NFR-RESP |
| `states.test.tsx` | Loading/Empty/Error components render roles, labels, retry and correlation id | XC-03 |
| e2e `auth-nav.spec.ts` | each of the 7 seed users (setup `storageState`) lands on its route and sees exactly its §2 nav | XC-01, §15 exit |
| e2e `forbidden.spec.ts` | customer opens `/outbound`, `IN001` opens `/analytics`, `IN001` opens `/customer/plan` (direct URL + reload) → 403 page; `/nope` → 404 | XC-02 |
| e2e `session.spec.ts` | wrong password → error; logout → `/login?reason=signed-out`, Back shows no protected content; expiry: `page.clock.install()` before a fresh login, `context.clearCookies({ name: 'SESSION' })`, `page.clock.fastForward('01:01')`, click a sidebar link → loader refetch → 401 → expired banner → re-login returns to that route | XC-02, NFR-SEC |
| e2e `responsive-a11y.spec.ts` | at 1280×800, 768×1024, 375×812: keyboard-only navigation, drawer on mobile, axe no serious/critical; keyboard-only login at 375×812 | XC-08, NFR-RESP |

## 11. Open questions (defaults used until answered)
| # | Question | Default |
|---|---|---|
| 1 | Keep `Can` in slice 01 although first used in 03–05? | Yes, as Phase 1 role/permission routing infrastructure (small, unit-tested); else defer to slice 03 |
| 2 | Should outbound see the Analytics entry? | Yes, under "Operations": §8 gives outbound an "operational view" via `analytics.read`; content decided in 04/05 |
| 3 | Separate "Reactivations" page for `service.reactivate`? | No: §16 makes it a panel in the inbound workspace, so `IN001` and `IN002` have the same nav |
| 4 | Promotions in the sidebar? The mock shows it only as a dashboard quick link | Yes, as a sidebar entry |
| 5 | Notification centre (§16) in the header? | Not rendered until the notifications slice (03–04) |
| 6 | Dark theme? | Light only; tokens are CSS variables so a theme can be added later |
| 7 | Warn before the 30 min idle timeout? | No; the next navigation after 60 s, or the next request, gets a 401 → login with `returnTo` |
| 8 | Where do frontend runtime errors go (NFR-OBS)? | `console.error` + correlation id shown to the user; a reporting endpoint in slice 06 |
| 9 | Brand font? | System font stack (no external font request) |
| 10 | Frontend performance metrics (NFR-OBS)? | Deferred to slice 06 (web-vitals with the reporting endpoint); none collected in 01 |

## 12. Dependencies outside this design
Backend erratum **E2** ([`backend.md`](backend.md) §12, awaiting approval) must be approved before the FE build and built before the e2e
task: SPA forward controller, Maven `frontend` profile, configurable login limits + `e2e` profile + prod guard, `check-frontend.sh` changes.

## 13. Changes in revision 2
Review `docs/slices/01-foundation/review-frontend-design.md` → section: F1 §7 (Bootstrap, 401 handler), §10 `session.test` ·
F2 §7 (Re-validation), §10 `session.test` (b)(c), e2e `session.spec` · F3 §10 (setup project, budget), §12, backend §12 E2 ·
F4 §7 (Client), §10 `apiClient.test` · F5 §7 (After login, Logout, Cross-tab), §10 · F6 §7 (After login), §7.1, §10 `returnTo.test` ·
F7 §9, §12 · F8 §9, backend §12 E2 · F9 §7 (Types) · F10 §7 (Logout, 403), §10 · F11 §2, §3 System pages ·
F12 §3 Login, §10 `LoginForm.test` · F13 §2, OQ 2 · F14 §9 (no `/actuator` proxy), OQ 1 · F15 §1, OQ 10 · F16 traceability.md.
Planner decision 1 (customer portal gate): §2. Former §12 follow-ups F1–F3 moved into backend erratum E2.
