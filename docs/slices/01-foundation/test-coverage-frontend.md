# Slice 01 frontend: test coverage matrix (independent gap pass)

Sources: `docs/design/01-foundation/frontend.md` §10 (test plan), §7.1, §13 (rev 2 F-rows), `backend.md` §12 (E2), BRD XC-01/02/03/07/08, NFR-A11Y/RESP/OBS.
Legend: covered = existed before this pass; ADDED = written in this pass; e2e-gap = needs a Playwright test, not added (login budget 12/12 is used up).

## Plan items

| Plan item | Test | Status |
|---|---|---|
| navConfig: requires, unique path, BRD id, customer userType | `navConfig.test.ts` (gives every item..., marks every customer-portal item...) | covered |
| navFilter: 7 role combos, labels, order, headings, landing | `navFilter.test.tsx` `it.each(EXPECTED)` for customer, IN001, IN002, OUT001, SUP001, BUS001, AUD001 | covered |
| STAFF with plan.read sees no customer item | `navFilter.test.tsx`, `navConfig.test.ts` | covered |
| RequirePermission: forbidden keeps URL, unknown -> NotFound, signed out -> login+returnTo, `Can` | `RequirePermission.test.tsx` | covered |
| LoginForm: validation, aria-invalid, focus, 400, 401, 429 countdown, 403 retry once, 500 + correlation id | `LoginForm.test.tsx` | covered |
| LoginForm: success clears cache, goes to returnTo/landing, broadcasts | `LoginForm.test.tsx` (clears the cache..., unsafe returnTo, forbidden returnTo) | covered |
| LoginForm: password reset, no `variables` in MutationCache | `LoginForm.test.tsx` (password hygiene) | covered |
| Password never in the QUERY cache (success, 400, 500) as well | `LoginForm.test.tsx` "the password never reaches the query or mutation cache (%s)" | ADDED |
| XC-03 login loading state (busy form, disabled submit, no double request) | `LoginForm.test.tsx` "loading state: ..." | ADDED |
| XC-03 login validation / error / success / empty (n/a: nothing to list) | `LoginForm.test.tsx`; empty not applicable | covered |
| XC-03 shell: loading + error + retry | `AppShell.test.tsx` "shows FullPageSpinner ... ErrorState ... Try again" | covered |
| XC-03 shell: empty | `RequirePermission.test.tsx` "NoAccessPage", `states.test.tsx` NoAccessPage / EmptyState | covered |
| XC-03 shell: validation / success | validation lives on the login form; success = the shell renders (AppShell tests) | covered |
| §7.1 reject list (12 strings) incl. `//evil.example`, `/\evil.example`, `\/evil.example`, tab, newline, `/ /x`, ` /x`, https, javascript:, `%2F%2F`, `/login?x=1`, empty | `returnTo.test.ts` first `it.each` | covered |
| §7.1 accept list (`/outbound`, `/customer/plan?tab=1#top`, `/%2F%2Fevil.example`) | `returnTo.test.ts` accepts | covered |
| Extra attack strings: nbsp, NEL, DEL, NUL, CR, U+2028, trailing space, `/login#x`, `/login`, data:, relative, `\evil`, `///evil`, `HTTP://` | `returnTo.test.ts` "also rejects" | ADDED |
| Hostile returnTo end to end through `afterLoginTarget` (landing fallback; permitted vs forbidden) | `returnTo.test.ts` "afterLoginTarget with hostile returnTo values" ; `LoginForm.test.tsx` unsafe returnTo | ADDED / covered |
| session (a) bootstrap 401 -> null, no redirect on /login, no repeat | `session.test.tsx` (a) | covered |
| session (b) expiry on navigation, banner, cache empty | `session.test.tsx` (b) | covered |
| session (c) expiry on focus | `session.test.tsx` (c) | covered |
| session (d) BroadcastChannel logout clears + refetches; unknown messages ignored | `session.test.tsx`, `logout.test.tsx` cross-tab | covered |
| 401 never loops on /login | `apiClient.test.ts` "ignores requests made while on /login" + ADDED variant with query string/hash and concurrent calls | covered / ADDED |
| 401 yields exactly one redirect | `session.test.tsx` (b)(c) `exactly once`; `apiClient.test.ts` "several concurrent expired requests produce exactly one redirect" | covered / ADDED |
| 401 ignore list (login, logout, bootstrap) | `apiClient.test.ts` | covered |
| X-XSRF-TOKEN on POST from the current cookie, rotation picked up, absent on GET | `apiClient.test.ts` "sends the current cookie ... rotated by login", "is absent on GET" | covered |
| Header equals the CURRENT cookie after repeated rotations (non-login endpoint, encoded value) | `apiClient.test.ts` "equals the CURRENT cookie on every POST" | ADDED |
| X-Correlation-Id v4 with `crypto.randomUUID` undefined | `apiClient.test.ts` | covered |
| ProblemDetail -> ApiError, non-JSON error, Retry-After, network failure, `applyProblemToForm` | `apiClient.test.ts` | covered |
| API 403: mutation toast with correlation id, cache unchanged; query 403 -> ForbiddenPage, no toast | `apiForbidden.test.tsx` | covered |
| API 403 toast has no stack / internal text even if the body carries them | `apiForbidden.test.tsx` "the toast never leaks a stack trace ..." | ADDED |
| 500 login error: generic text + correlation id, no internal text | `LoginForm.test.tsx` 500 | covered |
| RouteErrorPage shows no stack | `states.test.tsx` | covered |
| logout 204/401 clear + broadcast + redirect; 500/network toast, still signed in | `logout.test.tsx` | covered |
| AppShell: skip link first tab stop, focus to h1, title, drawer + Esc + focus return, collapse persisted, axe | `AppShell.test.tsx`, `navFilter.test.tsx` (collapsed rail) | covered |
| states: Loading/Empty/Error roles, labels, retry, correlation id | `states.test.tsx` | covered |
| NFR-OBS: no console error/warn in the whole run (React act(), key, nesting warnings) | `src/test/setup.ts` guard fails any test that logs `console.error`/`console.warn` unless it installs its own spy (only the two RouteErrorPage tests do). The full suite is green with the guard on, so no such warning exists today | ADDED |
| NFR-OBS: frontend performance metrics | deferred to slice 06 by design (OQ 10) | n/a |
| e2e auth-nav, forbidden, session, responsive-a11y | `e2e/*.spec.ts` | covered (not run in this pass) |

## Backend E2

| Plan item | Test | Status |
|---|---|---|
| E2.1 SPA routes -> 200 HTML + no-cache; `/api/**` ProblemDetail 404; actuator/error not forwarded; `/missing.js` 404; assets immutable | `SpaForwardIT` (5 tests) | covered |
| E2.3 defaults unchanged | `LoginRateLimiterTest.unsetPropertiesKeepTheApprovedDefaults` | covered |
| E2.3 custom L1 applied | `LoginRateLimiterTest.configuredIpLimitIsApplied` | covered |
| E2.3 custom L2 (max failures, block duration) and L3 threshold; non-positive rejected | `LoginRateLimiterTest.configuredPairLimit...`, `configuredUsernameAnomaly...`, `nonPositiveLimitsAreRejected` | ADDED |
| E2.5 prod+e2e fails with a clear message | `ProfileGuardIT` (3 tests) | covered |
| E2.2 Maven `frontend` profile (fails without dist; JAR has static/index.html) | no automated test (build-level) | gap, build check |
| E2.4 e2e profile run has no 429 | covered only by an e2e run | e2e-gap (needs a full run) |
| E2.6 `check-frontend.sh` steps | exercised by every run; no dedicated test | gap, tooling |

## e2e gaps (not added)
- No browser-level check that the 403 toast hides internals or that a 401 on `/login` does not loop; both are covered at unit level only.
- Per-role nav is e2e-tested with the real seed users (`auth-nav.spec`); no new e2e needed.
