# Slice 01-foundation (backend) — test coverage matrix

Gap pass against `docs/design/01-foundation/backend.md` §8 (+ §4–§7 security rules, §11 E1) and BRD NFR-SEC, NFR-OBS,
NFR-REL, BR-01, BR-03, BR-10, XC-02, XC-05, XC-06. "Added" = written in this pass. Result: 117 tests green after this pass; 135 after the code-review fixes (ProdCookieIT, LoginRateLimiterTest, AuthServiceTest and added cases).

## §8 test plan

| Plan item | Test | Status |
|---|---|---|
| Login ok → Me + permissions | `AuthIT.loginReturnsMeAndTheSessionSurvivesFurtherRequests` | covered |
| Identical 401 (unknown / bad pw / disabled) | `AuthIT.unknownUserBadPasswordAndDisabledGetIdentical401s` | covered |
| 21st/min → 429 + Retry-After | `AuthIT.l1…` (only range 1..60) ; exact `3`, then `2`, then freed: `AuthContractIT.l1RetryAfterIsTheExactWholeSecondsUntilATokenRefills` | covered + added (exact value) |
| L2 6th failure → 429 15 min then allowed | `AuthIT.l2FiveFailuresBlock…` (900 + free at 15 min); countdown 900/600/1/free: `AuthContractIT.l2RetryAfterCountsDownFrom900Seconds` | covered + added |
| Same user from other IP allowed; success clears pair | `AuthIT.l2…`, `AuthIT.successfulLoginClearsThePairCounter` | covered |
| L3 anomaly, correct password still works | `AuthIT.l3AnomalyOnThe51stFailure…` | covered |
| Session id changes on login | `AuthIT.loginChangesTheSessionId` | covered |
| Cookie flags SESSION HttpOnly/Lax/Secure | `AuthIT.sessionCookieIs…` ; plus Path, XSRF-TOKEN (not HttpOnly, Lax, Path=/, Secure over https) on csrf/login/logout: `AuthContractIT.cookieFlagsOnLoginAndLogoutOverPlainHttpAndHttps`, `csrfBootstrapSetsTheCookie…` | covered + added |
| Secure "under `prod`" | no `application-prod.yml` exists; Secure follows `request.isSecure()` only | **gap, see defects** |
| `/api/**` no session → 401, no Location | `AuthIT.apiWithoutSessionIs401WithoutRedirect`, `ErrorModelIT.chainUnauthenticated…` | covered |
| Anonymous `/me`, `/logout` → 401 problem; logout w/o CSRF → 403; forged SESSION → 401 | `AuthContractIT.anonymousMeAndLogoutAre401ProblemsWithoutRedirect`, `forgedOrUnknownSessionCookieIs401` | added |
| `/me` and logout for every role (no permission needed → no role may get 403) | `AuthContractIT.meAndLogoutAreOpenToEveryRoleOnceSignedIn` (7 role combos, 200/204, then 401) | added (a "forbidden role" case does not exist: §5 makes both session-only) |
| Logout with rotated token 204 / pre-login token 403 / old cookie 401 | `AuthIT.loginRotatesCsrfTokenAndLogoutNeedsTheRotatedOne` | covered |
| Idle expiry, 12 h absolute, DISABLED mid-session | `AuthIT.idleSessionExpires`, `sessionExpiresAfterTheAbsoluteLifetime`, `userDisabledMidSessionGets401` | covered |
| POST without CSRF → 403 | `AuthIT.loginWithoutCsrfIs403`, `ErrorModelIT.chainCsrfFailureIs403Problem` | covered |
| Logs: no password / session id | `AuthIT.logsContainNoPasswordOrSessionId` ; plus no full username (unknown and known), none on the throttle WARN path: `AuthContractIT.logsNeverContainThePasswordOrTheFullUsernameEvenWhenThrottled` | covered + added |
| `ClientIpIT` (E1: forged XFF ignored, trusted proxy honoured, `forward-headers-strategy=none`, filter order, invalid CIDR) | `ClientIpIT.*` (8 tests) | covered |
| `PermissionMatrixIT` (matrix = authorities = `/me`; probe 200 holder / 403 others / 401 anon) | `PermissionMatrixIT.*` | covered |
| `AuditTrailIT` (§7 rows, correlation id, survive rollback, masked username, URL + method 403 once, sampling, triggers) | `AuditTrailIT.*` | covered |
| `IdempotencyServiceIT` (all §6.1 cases) | `IdempotencyServiceIT.*` | covered |
| `ReferenceGeneratorIT` | `ReferenceGeneratorIT.*` | covered |
| `ErrorModelIT` (400 errors[], 405, 413, 415, 500, correlation id echo/replace, actuator) | `ErrorModelIT.*` | covered |
| `ArchitectureTest` (no JPA, repositories module-private) | `ArchitectureTest.*` | covered |
| Module cycle freedom | no rule possible: the codebase fails it | **defect, see below** |

## ProblemDetail shape per 00-overview §4 status (strict key set, type, instance, correlationId = header, no internals)

| Status / type | Test | Status |
|---|---|---|
| 400 validation (`errors[]`) | `ProblemShapeIT.validation400HasFieldErrors` | added |
| 400 bad-request | `ProblemShapeIT.badRequest400`, `AuthContractIT.badCredentialsAndValidationOnLoginKeepTheProblemShape` | added |
| 401 unauthenticated (chain, unknown path, login) | `ProblemShapeIT.unauthenticated401…`, `AuthContractIT.badCredentials…` | added |
| 403 forbidden (permission, CSRF) | `ProblemShapeIT.forbidden403ForPermissionAndForCsrf` | added |
| 404 not-found | `ProblemShapeIT.notFound404ForAnUnknownApiPathWhenSignedIn` | added |
| 405 | `ProblemShapeIT.methodNotAllowed405` (+ Allow in `ErrorModelIT`) | added |
| 409 conflict, 422 business-rule, 422 idempotency-mismatch (message not leaked) | `ProblemShapeIT.conflict409…`, `businessRule422…`, `idempotencyMismatch422` via test-only `ApiErrorProbeController` | added |
| 413, 415 | `ProblemShapeIT.payloadTooLarge413`, `unsupportedMediaType415` | added |
| 429 too-many-requests + Retry-After | `AuthContractIT.l1…`, `l2…` | added |
| 500 internal (no stack trace / message) | `ProblemShapeIT.internal500HasNoStackTraceOrMessage` | added |
| 503 dependency-unavailable | no producer in the app yet | not testable today |

## Production defects / design mismatches found

1. **Package cycles (real).** ArchUnit `slices().matching("com.telecom.support.(*)..").should().beFreeOfCycles()` fails with 3 cycles:
   `audit <-> identity` (`audit/DeniedAccessRecorder` -> `identity.CurrentUser`; `identity/AuthService`, `SecurityConfig` -> `audit.AuditService`/`DeniedAccessRecorder`),
   `audit <-> common` (`common/error/GlobalExceptionHandler` -> `audit.DeniedAccessRecorder`; `audit/AuditService` -> `common.web.ClientIp`/`CorrelationIdFilter`),
   and `audit -> identity -> common -> audit`. `ArchitectureTest` does not detect it. No cycle rule was added, because it cannot pass.
2. **Secure cookie under `prod`** (§4.3, §8): no `prod` profile or `server.servlet.session.cookie.secure` exists in `backend/src/main/resources`, so Secure is set only when the request is seen as HTTPS (works behind a trusted proxy that sends `X-Forwarded-Proto`, otherwise never). Untestable until a `prod` config exists.
