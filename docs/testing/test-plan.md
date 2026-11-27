# Test plan — Telecom Customer Support (deferred-testing execution plan)

Status: Draft · Owner: QA/test engineering · Basis: `main` at `d32a590` (slices 01, 02 backend built; 03 backend designed; 03 FE, 04, 05, 06 not designed).
Test code is **not** part of this document. Where a slice has no design yet, scenarios come from the BRD and must be re-checked against the design when it is approved.
Per CLAUDE.md, every protected endpoint needs a forbidden-role case before its task is Done; this plan is the backlog for the tests the user chose to defer.

## 1. Scope, strategy and levels

**In scope:** Spring Boot API (`/api/v1/**`), MySQL schema and Flyway migrations, scheduled jobs, external ports (payment gateway, provisioning, notification, verification-code sender), React SPA, the JAR that serves both.
**Out of scope:** the real third-party providers (stubbed by mock adapters; real adapters need contract tests when they exist), browsers other than Chromium until slice 06 decides.

Strategy: risk-based (money, identity, authorization, privacy first), test pyramid (many fast unit/IT, few e2e), design-as-oracle (assert the design's Test plan section, never the code), deterministic (fixed/mutable `Clock`, no sleeps, data created inside the test).

| # | Level | What it proves | Tooling | Runs |
|---|---|---|---|---|
| L1 | Unit | Pure logic: OTP codec, rate limiters, state-machine guards, mappers, mock adapters | JUnit 5, AssertJ, Mockito only for ports | PR |
| L2 | Integration (Testcontainers MySQL) | SQL, constraints, transactions, security chain, audit, idempotency, jobs; `*IT` extend `AbstractMySqlIT` | Spring Boot Test, MockMvc with real security, Testcontainers MySQL 8 | PR (sharded) |
| L3 | API contract | Responses and error shapes match `openapi.yaml`; generated FE types have no diff | OpenAPI validator in MockMvc tests (e.g. swagger-request-validator), `gen-api` diff check, ProblemDetail shape tests | PR |
| L4 | Frontend unit / component | Each of loading, empty, error, validation, success; permission gating; URL list params | Vitest, React Testing Library, MSW (handlers from openapi examples) | PR |
| L5 | E2E journeys | Role journeys against the real JAR + MySQL on the `e2e` profile | Playwright (3 viewport projects; setup project saves auth state) | Nightly, PR smoke subset |
| L6 | Accessibility | No serious/critical axe violations; keyboard path; focus; contrast | `@axe-core/playwright` in e2e, `vitest-axe` in RTL | PR (component), nightly (page) |
| L7 | Security | ASVS items in 4.2, authz matrix, dependency/image scanning | MockMvc matrix tests, OWASP ZAP baseline, Dependency-Check or Trivy, `npm audit`, gitleaks | PR (matrix, scans), nightly (ZAP) |
| L8 | Performance / load | P95 read API <= 500 ms, LCP <= 2.5 s, no regression | k6 (API), Lighthouse CI (LCP, budgets), MySQL slow-query log | Nightly + pre-release |
| L9 | Resilience of external ports | Timeouts, refusals, duplicates, out-of-order, recovery | Mock adapter fault modes in L2; Toxiproxy for real adapters later | PR (L2), pre-release (Toxiproxy) |
| L10 | Data / migration | Flyway forward from every tag, seed idempotence, constraints, rollback runbook, backup/restore | `SchemaV*IT`, Flyway validate, `mysqldump` restore drill | PR (schema), pre-release (restore) |
| L11 | UAT | Business acceptance of BRD criteria on staging with QA evidence (XC-09) | Scripted scenarios from section 3, sign-off sheet | Pre-release |

### 1.1 Environments and data

| Env | Profile / DB | Use | Data |
|---|---|---|---|
| Dev | `dev`, `telecom_support`, compose MySQL 8 (utf8mb4, UTC) | Manual, exploratory | Flyway + `db/seed` (7 seed users) |
| Test (IT) | Testcontainers MySQL, `AbstractMySqlIT` | L1-L3, L9, L10 | Flyway + `R__test_users_*.sql`; each test creates its own customers/cases (helpers such as `suspendAccount`) |
| E2E | `dev,e2e`, separate DB `telecom_support_e2e`; `E2eDatabaseReset` cleans and migrates at start; seeds `R__02_customer_e2e.sql`, `R__03_inbound_e2e.sql` | L5, L6 | Fresh DB per run; JAR restarted between runs (payment spec mutates state) |
| CI | Docker-capable runner; frontend tooling in a Linux container (Windows Application Control blocks native npm binaries) | All PR/nightly suites | As above |
| Staging | `prod`-like profile, adapter sandboxes, anonymised data | L8, L11, restore drill | Generated volume set (4.1); never production PII |

Rules: no shared mutable fixtures; fixed `Clock` (mutable test `Clock` for expiry and jobs); no `Thread.sleep` (drive jobs directly; bounded awaitility only for real async); no ordering dependence; e2e specs create what they mutate or use a dedicated seed customer; `prod` must refuse mock adapters (`ProfileGuard`, `ProdAdapterCheck`).

### 1.2 Entry and exit criteria

| Gate | Criteria |
|---|---|
| Entry (tests for a task) | Design Approved and merged; task row in `status.md`; seed/test users exist |
| Exit per task (DoD) | Tests assert the design; forbidden-role + anonymous case per protected endpoint; `scripts/check-*.sh` green; traceability row updated |
| Exit per slice | Section 3 catalogue for the slice green; no open Sev 1/2; matrix rows Covered or Deferred with an owner |
| Exit for release | Section 5.3 gate |

### 1.3 Defect severity and triage

| Sev | Definition | Examples | Response |
|---|---|---|---|
| 1 Critical | Cross-customer data exposure, auth bypass, money wrong/lost, missing audit on a high-risk action | IDOR, double charge, reactivation beyond 24 h | Stop release; fix within 1 day; regression test mandatory |
| 2 High | Core journey broken, no workaround; PII/secret in logs; limit bypass | OTP limit race, 500 on payment return | Fix before slice merge |
| 3 Medium | Wrong behaviour with workaround, wrong error type, serious a11y | Wrong ProblemDetail type, no focus ring | Fix before release |
| 4 Low | Cosmetic, copy, minor a11y | Spacing | Backlog |

Triage: reproduce with a failing test first; label with BRD ID and slice; production defects go to the slice owner and are never "fixed" by editing the test; a defect closes only with its regression test linked.

## 2. Coverage matrix (BRD IDs)

Measured against `main`. **Covered** = every built layer has tests and the design's test plan is met. **Partial** = built code tested but a layer (FE, e2e, later slice, NFR evidence) is missing. **Deferred** = consciously postponed. **Not started** = code or design does not exist yet. Priority: P0 money/identity/authz/privacy, P1 core journeys, P2 polish.
Levels: U unit, I integration, C contract, F frontend RTL, E e2e, A a11y, S security, P perf, R resilience, D data.

| ID | Requirement | Levels | Existing tests | Status | Pri |
|---|---|---|---|---|---|
| CUS-01 | Dashboard, own data only | I, F, E, A | CustomerPortalAuthzIT, CustomerIdorIT, CustomerProfileIT | Partial (no FE/e2e) | P1 |
| CUS-02 | Profile, verified contact change, audit | I, F, E, S | CustomerProfileIT, ContactVerificationIT | Partial (OTP UI) | P0 |
| CUS-03 | Plan summary, upgrade, confirmed change | I, F, E, R | SubscriptionPlanIT | Partial | P1 |
| CUS-04 | Promotions, confirmed claim | I, F, E | PromotionIT | Partial | P2 |
| CUS-05 | Request list, 3-month window, paging | I, F, E | CustomerCaseIT | Partial | P1 |
| CUS-06 | Create request, unique ref, no duplicates | I, F, E | CustomerCaseIT, CaseCategoryIT | Partial | P1 |
| CUS-06.3 | Optional attachment | I, S | none | Deferred (OQ 4) | P2 |
| CUS-07 | Payment history, receipt, reconciled | I, F, E | PaymentHistoryIT, PaymentReconciliationJobIT | Partial | P0 |
| CUS-08 | Make payment, hosted gateway, verification | I, R, F, E, S | PaymentInitiateIT, PaymentWebhookIT, PaymentTransitionIT, MockCheckoutIT, MockPaymentGatewayTest | Partial (e2e F22 deferred) | P0 |
| INB-01 | Lookup, verification, context, access log | I, S, F, E | none (design 03 s9: CustomerLookupIT, IdentityCheckIT, VerifiedCustomerAccessIT) | Not started | P0 |
| INB-02 | Track customer cases, internal notes hidden | I, F | none (AgentCaseQueryIT designed) | Not started | P1 |
| INB-03 | Raise request on behalf | I, F, E | none (AgentCaseCreateIT designed) | Not started | P1 |
| INB-04 | Temporary reactivation (24 h cap, prohibited reasons) | I, R, S, E | none (Reactivation*IT, NotificationDispatchIT designed) | Not started | P0 |
| OUT-01 | Case dashboard and queue | I, P, F, E | none | Not started | P1 |
| OUT-02 | Advanced filters | I, C, F | none | Not started | P1 |
| OUT-03 | Atomic claim, reassignment | I (race), S | none | Not started | P0 |
| OUT-04 | Case details and activity | I, F | none | Not started | P1 |
| OUT-05 | Status transitions, closure | I (state machine), F | none | Not started | P0 |
| OUT-06 | Bookmarks, follow-up | I, F | none | Not started | P2 |
| BUS-01 | Daily case dashboard | I, P, F | none | Not started | P1 |
| BUS-02 | Category and severity analysis | I, P, F | none | Not started | P2 |
| BUS-03 | Audit view and export | I, S, F | none | Not started | P0 |
| BR-01 | Unique, non-reusable reference | I | ReferenceGeneratorIT, CustomerCaseIT, PaymentInitiateIT | Covered | P0 |
| BR-02 | Account association and channel | I, D | SchemaV3toV6IT, CustomerCaseIT | Covered | P1 |
| BR-03 | Change records actor/time/old/new/reason | I | AuditTrailIT | Partial (assignment, status, reactivation pending) | P0 |
| BR-04 | Customer vs internal notes separate | I, F | SchemaV3toV6IT (schema only) | Partial | P0 |
| BR-05/06 | 3-month default, bounded filters, paging | I, F, E | CustomerCaseIT, PaymentHistoryIT | Partial (FE, later lists) | P1 |
| BR-07 | SLA configurable by category | I, D | CaseCategoryIT, SchemaV3toV6IT, CustomerCaseIT | Covered | P1 |
| BR-08 | Provider-confirmed payment/provisioning | I, R | PaymentTransitionIT, PaymentWebhookIT, SubscriptionPlanIT | Partial (provisioning in 03) | P0 |
| BR-09 | Case to work-item link | I | none | Not started (future) | P2 |
| BR-10 | Server-side authorization | I, S | PermissionMatrixIT, CustomerPortalAuthzIT | Partial (03-06 endpoints) | P0 |
| XC-01 | Role-specific navigation | F, E | navConfig.test, navFilter.test, auth-nav.spec | Partial | P1 |
| XC-02 | Restricted routes and APIs denied | F, I, E | RequirePermission.test, apiForbidden.test, forbidden.spec, authz ITs | Partial | P0 |
| XC-03 | Loading/empty/error/validation states | F | states.test, LoginForm.test | Partial (01 only) | P1 |
| XC-04 | Server paging, filters kept | I, F | CustomerCaseIT, PaymentHistoryIT | Partial (URL params FE) | P1 |
| XC-05 | Date filter validation, timezone | I, F | CustomerCaseIT, PaymentHistoryIT | Partial | P1 |
| XC-06 | Confirmed and audited high-risk actions | I, F | AuditTrailIT, ContactVerificationIT, SubscriptionPlanIT | Partial | P0 |
| XC-07 | Success only after backend confirms | I, F | PaymentWebhookIT, SubscriptionPlanIT | Partial | P0 |
| XC-08 | Keyboard and 3 breakpoints | A, E | AppShell.test, responsive-a11y.spec | Partial | P1 |
| XC-09 | Automated tests + QA evidence for critical flows | all, UAT | this plan | Not started | P0 |
| NFR-PERF | Budgets, lazy load, paging (P95 <= 500 ms, LCP <= 2.5 s) | P | none | Not started | P1 |
| NFR-SEC | Auth, sessions, validation, transport | S, I | AuthIT, AuthContractIT, ProdCookieIT, ClientIpIT, LoginRateLimiterTest | Partial (ZAP, TLS, deps) | P0 |
| NFR-PRIV | Minimise, mask, log access, retention | I, S | AuditTrailIT, ContactVerificationIT, log assertions | Partial (retention untested) | P0 |
| NFR-AVAIL | Graceful degradation | R | PaymentInitiateIT, PaymentReconciliationJobIT | Partial (provisioning/notify in 03) | P0 |
| NFR-REL | Idempotent create/payment/reactivation | I | IdempotencyServiceIT, PaymentInitiateIT, CustomerCaseIT | Partial | P0 |
| NFR-A11Y | Semantic, keyboard, focus, contrast | A | AppShell.test (vitest-axe), responsive-a11y.spec | Partial | P1 |
| NFR-RESP | Desktop-first, tablet/mobile | E | responsive-a11y.spec | Partial | P1 |
| NFR-OBS | Correlation ids, no secrets, metrics | I, S | ErrorModelIT, AuthIT/AuthContractIT log tests, apiClient.test | Partial (web-vitals, metrics) | P1 |
| NFR-MAINT | Modules, typed contracts | U | ArchitectureTest | Covered | P2 |
| NFR-INTEG | Ownership and transition validation | I | CustomerIdorIT, PaymentTransitionIT, SchemaV3toV6IT | Partial | P0 |

Tally: see 5.4. Other existing infrastructure tests: SchemaIT, SpaForwardIT, ProfileGuardIT/Test, CatalogServiceIT, CustomerScopeIT, E2eProfileIT, AbstractMySqlIT; FE: smoke, session, returnTo, logout, apiClient.

## 3. Per-slice test catalogue

Checks that apply to **every** slice are listed once; slices refer to them as CM-n.

| ID | Common check | Level |
|---|---|---|
| CM-1 | Per endpoint: allowed role 2xx; every other role (CUSTOMER, INBOUND_AGENT, OUTBOUND_AGENT, OUTBOUND_SUPERVISOR, BUSINESS_ANALYST, BUSINESS_AUDITOR) 403 `forbidden`; anonymous 401; mutating call without CSRF 403 | I, S |
| CM-2 | Route-coverage test: reflection over controllers fails if a handler lacks `@PreAuthorize` | U |
| CM-3 | IDOR: user A with a valid id of user B gets 404 (not 403) and nothing changes | I, S |
| CM-4 | Idempotency: same key + body replays the original (one row); same key + different body/path rejected per overview s7; key expiry | I |
| CM-5 | ProblemDetail: `type`, `status`, `instance`, `correlationId` equals `X-Correlation-Id`, `errors[]` on 400, no stack trace or SQL | I, C |
| CM-6 | Lists: default 3-month window, from > to rejected, window cap, size cap, sort whitelist (unknown key 400, injection inert), filters kept across pages; boundaries at Asia/Kolkata midnight | I |
| CM-7 | Audit: one append-only row per high-risk action with actor, correlation id, old/new, masked PII; UPDATE/DELETE rejected by triggers; survives business rollback | I |
| CM-8 | Logs: capture output; assert no password, OTP, session id, full MSISDN/email, card data | I, S |
| CM-9 | Contract: each response validates against `openapi.yaml`; regenerated FE types have no diff | C |
| CM-10 | FE page: loading, empty, error (ProblemDetail + correlation id), validation (field + summary, `aria-describedby`), success, 403 handling; no optimistic update | F |
| CM-11 | Viewports 1280x800, 768x1024, 390x844: no horizontal scroll, drawer/rail nav, tables become cards; keyboard-only path with visible focus; axe clean | E, A |

### 3.1 Slice 01 Foundation (identity, errors, audit, idempotency, shell) — mostly Covered

| Area | Scenarios | Existing | Gap / action | Pri |
|---|---|---|---|---|
| Auth | Login/logout/me per role; identical 401; session rotation; idle 30 min, absolute 12 h; disabled user mid-session; CSRF; cookie flags | AuthIT, AuthContractIT, ProdCookieIT | Re-run under `prod` profile behind TLS in staging (Secure flag) | P0 |
| Rate limits | L1 20/min per IP exact Retry-After; L2 5 fails -> 15 min; L3 anomaly; trusted-proxy XFF | AuthIT, LoginRateLimiterTest, ClientIpIT | Concurrency: 50 parallel bad logins count exactly | P0 |
| Permission matrix | Matrix = authorities = `/me`; 26 permissions x 7 users | PermissionMatrixIT | Re-run on every migration that adds a permission | P0 |
| Errors / correlation | 400/401/403/404/405/413/415/429/500 shapes; header echo/replace | ErrorModelIT, ProblemShapeIT | none | P1 |
| Audit | Rows, masked username, 403 once per user/path, sampling, append-only | AuditTrailIT | Retention/purge has no test | P1 |
| Idempotency / reference | Replay, mismatch, expiry, purge job; unique references | IdempotencyServiceIT, ReferenceGeneratorIT | 200-thread uniqueness soak | P1 |
| Architecture | No JPA, module edges, repositories private | ArchitectureTest | Cycle rule is a known defect (codebase fails it) | P2 |
| SPA | History fallback, `/api` 404 not rewritten | SpaForwardIT | none | P2 |
| FE shell | Nav by permission, guard, session expiry, logout, 403 toast, responsive | 10 RTL files, 4 specs | Login a11y; Playwright keyboard path | P1 |

### 3.2 Slice 02 Customer portal (BE built; FE not built)

| Area | Scenarios | Existing | Gap / action | Pri |
|---|---|---|---|---|
| Authz / IDOR | E1-E17 x anonymous, 6 staff roles, unlinked customer; customer id from principal; foreign subscription/invoice/payment/case ref | CustomerPortalAuthzIT, CustomerIdorIT | Add foreign id on promotion claim | P0 |
| Profile / OTP | Alias null vs missing; stale 409; CLOSED 422; OTP 5 attempts, expiry, resend cap; HMAC not plaintext; masked audit | CustomerProfileIT, ContactVerificationIT | Parallel wrong-confirm race (as `IdentityCheckIT` in 03) | P0 |
| Plan change | Quote mismatch, eligible list, charge rule (OQ 10), apply job confirms with provisioning, pending state, double submit | SubscriptionPlanIT | Provisioning outage stays PENDING and retries; duplicate apply | P0 |
| Promotions | Eligibility, claim once per subscription | PromotionIT | Concurrent double claim -> one | P1 |
| Cases | Categories, leaf-first SLA, 3-month default, filters, reference search, duplicate suppressed, replay | CustomerCaseIT, CaseCategoryIT | Internal notes never in customer DTO (BR-04) | P0 |
| Payment initiate | Invoice owned, OPEN only, amount server-side, replay, gateway refused 503 -> FAILED, timeout -> PENDING, EXPIRED re-check 409, no card data | PaymentInitiateIT | Parallel initiate on one invoice -> exactly one live payment | P0 |
| Webhook | Signature (401 before 400), same event replayed, out of order, unknown payment, amount mismatch, IP rate limit before 415 | PaymentWebhookIT, PaymentTransitionIT | Replay-flood and tamper fuzz | P0 |
| Reconciliation | Keyset-paged job, 50 s budget, gateway down, prod refuses disabled cron | PaymentReconciliationJobIT | Two instances concurrently -> no double transition | P0 |
| Receipt / history | Window, paging, reconciled flag, receipt only for SUCCESS | PaymentHistoryIT | none | P1 |
| Data | V3-V6 constraints, guarded keys | SchemaV3toV6IT, E2eProfileIT | Upgrade from a V2 snapshot with data | P1 |
| FE (F1-F22) | Dashboard cards independent states; profile + OTP dialog; plan-change/claim confirm dialogs; case list (URL params) and create form (no attachment); payment history, pay, return path, status, receipt; date-range filter | none | RTL per page (CM-10), MSW, vitest-axe | P0 |
| E2E | Customer journey x 3 viewports; payment serial; staff roles denied on `/customer/*` | forbidden.spec | F21 `customer-portal.spec`, F22 `payment.spec` deferred (5.2) | P0 |

### 3.3 Slice 03 Inbound workspace (BE designed; tests per design s9; FE design pending)

| Area | Scenarios | Test (design s9) | Pri |
|---|---|---|---|
| Schema | CHECKs (OTP/HMAC, PENDING expiry, KBA never PENDING, prohibited reasons, 24 h), open-request UK, outbox UK, SLA for every leaf x 4 severities | SchemaV7toV9IT | P0 |
| Lookup | POST search; masked logs; token only on hit; `[]` for TERMINATED/unknown; 31st/min 429; audit without full MSISDN | CustomerLookupIT | P0 |
| Identity checks | Unknown/expired/other agent's token -> identical 404; 8 parallel wrong OTP -> attempts exactly 5; 10 parallel OTP starts -> 5 rows + 5 x 429; 10 parallel wrong KBA -> 3 evaluated + 7 x 429; cleanup job | IdentityCheckIT | P0 |
| Verified scope | Verified routes without a check -> 403 `verification-required`; 30 min expiry; agent B cannot use A's check; `CUSTOMER_RECORD_ACCESSED` once under 5 parallel reads; replay after expiry -> 403; reflection coverage | VerifiedCustomerAccessIT, AgentRouteCoverageTest | P0 |
| Views | Profile without billing address; subscriptions; invoices (latest 12); payments window/paging | AgentCustomerViewIT | P1 |
| Cases | Filters/sort/owner; INTERNAL notes only with `case.note.internal`; other account 404; duplicates; customer view shows public status only | AgentCaseQueryIT | P0 |
| Create on behalf | Channel INBOUND, creator, contact; internal categories need permission; override 400/403; EMAIL needs verified email; foreign sub 404; CLOSED 422; replay + mismatch; refactor keeps CustomerCaseIT green | AgentCaseCreateIT, CaseCreationServiceIT | P1 |
| Eligibility | Every reason code; FRAUD/LEGAL/REGULATORY never overridable; `maxHours` | ReactivationEligibilityIT | P0 |
| Reactivation create | 201 + notify + audit; 25 h -> 400; > max -> 422; 2 agents parallel on one account -> 201 + 409; 1 agent 11 parallel -> 10 succeed; replay; provider REJECT 422, UNAVAILABLE 503 + retry, TIMEOUT 503 + retry 409 | ReactivationCreateIT | P0 |
| Reactivation jobs | Expiry -> EXPIRED; reinstated -> ENDED; reason becomes FRAUD -> EXPIRING -> EXPIRED; suspend fails 10x then succeeds -> EXPIRED (attention after 5, never terminal); late RESTORED after window -> EXPIRING -> EXPIRED | ReactivationJobsIT | P0 |
| Notifications | Outbox masked; 3 failures -> FAILED; two parallel runs -> one send per row; stale SENDING reset | NotificationDispatchIT | P1 |
| Authz / IDOR | I1-I15 x anonymous + 5 other roles (403 `forbidden`, not `verification-required`); INBOUND_AGENT without REACTIVATION_APPROVER 403 on I13/I14; ACCESS_DENIED once; foreign case/sub/check/lookup token -> 404, no change | InboundAuthzIT, InboundIdorIT | P0 |
| Prod guards | prod + mock adapter or `-` cron refused; prod with only mock adapters fails | ProfileGuardIT, ProdAdapterCheckIT | P0 |
| Cross-module | New module edges only, no cycles | ArchitectureTest, CrossModuleApiIT | P2 |
| Deferred | Slice 03 E1/E2 tests (user-deferred; list the exact items in `03-inbound/status.md` before starting) | to identify | P1 |
| FE (to design) | Masked search; OTP/KBA dialogs (remaining attempts, lockout); verified banner + timeout; case timeline with internal-note badge; create-on-behalf form; reactivation wizard (blocked reasons, confirm, countdown); denied view for non-approvers; 3 viewports, axe | after FE design | P0 |

### 3.4 Slice 04 Outbound workspace (OUT-01..06; no design yet)

| Area | Scenarios | Level | Pri |
|---|---|---|---|
| Authz | `case.read/assign/status.update/bookmark` per matrix; supervisor-only reassignment; customer/inbound/business 403; queue scope by team (OUT-01.4) | I (CM-1) | P0 |
| Queue | Counts (new, open, in progress, pending, resolved, closed, critical, overdue) equal list totals; paging, sort whitelist, combined filters (OUT-02); empty/error states | I, P, F | P1 |
| Claim | 20 parallel claims on one case -> one 200, 19 x 409; taking another agent's case 403; reassign records old/new owner, actor, time, reason and shows in activity (OUT-03) | I (race) | P0 |
| State machine | Table-driven over Created -> Assigned -> In Progress -> Pending -> Resolved -> Closed (+ Reopened by policy): every illegal pair rejected; reason where required; stale update 409; closure needs resolution; each transition audited (BR-03, OUT-05) | I | P0 |
| Activity | Customer vs internal notes separate (BR-04); append-only; ordering | I | P0 |
| Bookmarks | Per agent, idempotent add/remove, invisible to others; follow-up date validation | I, F | P2 |
| Jobs | SLA breach / overdue at the boundary with mutable clock; escalation | I | P1 |
| FE / E2E | Queue at 3 viewports; filters in URL; claim-conflict toast; status dialog confirm (XC-06); keyboard-only triage | F, E, A | P1 |

### 3.5 Slice 05 Business operations (BUS-01..03; no design yet)

| Area | Scenarios | Level | Pri |
|---|---|---|---|
| Authz | `analytics.read` and report/export permissions; analyst vs auditor; outbound sees operational view only; customer/inbound 403 | I (CM-1) | P0 |
| Aggregates | Daily counts and category x severity equal SQL ground truth on a seeded set; Asia/Kolkata midnight boundaries; empty day; no PII in payloads | I | P1 |
| Audit view | Filters date/category/status; lifecycle timestamps and audit history only per permission (BUS-03.2) | I | P0 |
| Export | Only authorized fields per role (BUS-03.3); CSV injection (`=`, `+`, `-`, `@` prefixes); row/date cap; streaming memory bound; export audited with actor, time, filter, type (BUS-03.4) | I, S, P | P0 |
| Performance | Aggregations P95 <= 500 ms on 1M cases; `EXPLAIN` shows indexes | P, D | P1 |
| FE | Charts with accessible table alternative, empty state, contrast, 3 viewports | F, A | P2 |

### 3.6 Slice 06 Production readiness (no design yet)

| Area | Scenarios | Level | Pri |
|---|---|---|---|
| Real adapters | Contract tests per adapter (payment, provisioning, notification, verification sender) against sandboxes/recorded stubs; `ProdAdapterCheck` passes only with real adapters; secrets from env | I, R | P0 |
| Security | Full 4.2 run; TLS, HSTS, CSP, cookie flags under prod; actuator `health` only | S | P0 |
| Performance | 4.1 load profile and Lighthouse budgets | P | P0 |
| Observability | Frontend error/web-vitals endpoint (deferred from slice 01 OQ 10): auth, rate limit, no PII, size cap; alerts fire | I, S | P1 |
| Ops | Backup/restore drill, Flyway upgrade on a production-sized copy, rollback runbook, jobs under 2 instances | D, R | P0 |
| Compliance | DPDP retention/deletion jobs, audit export, PCI scope (no card data stored), legal sign-off (BRD s12) | UAT | P0 |
| Accessibility | Full-site axe sweep + manual screen-reader pass on top 5 journeys; WCAG AA contrast | A | P1 |

## 4. Non-functional plan

### 4.1 Performance (NFR-PERF: P95 <= 500 ms ordinary reads excluding providers; LCP <= 2.5 s primary customer page)

| Item | Plan |
|---|---|
| Data volume | 100k customers, 1M cases, 3M payments, 5M audit rows (generated, anonymised) |
| Load profile (k6) | 60% customer reads, 15% agent lookups/reads, 10% case writes, 5% payment initiate (mock gateway 300 ms), 5% webhook, 5% analytics; ramp to 200 VUs, 30 min soak at 100 |
| Pass criteria | P95 <= 500 ms reads, P99 <= 1.5 s, errors < 0.5%, no 5xx at 2x load, DB pool < 80%, heap stable over soak |
| Frontend | Lighthouse CI on dashboard, case list, agent search on throttled Fast 4G: LCP <= 2.5 s; per-route bundle budget set from first measurement then ratcheted; lazy routes verified in bundle report |
| DB | Slow-query log > 200 ms reviewed after each run; `EXPLAIN` on filter/sort queries; missing indexes become migrations |
| Contention | Hot rows: payments on one invoice, claims on one case, reference generator at 200 threads, idempotency table at volume |

### 4.2 Security (NFR-SEC, NFR-PRIV; OWASP ASVS 4.0 areas relevant here)

| ASVS area | Checks | Level |
|---|---|---|
| V2 Authentication | Generic login errors; throttle L1-L3; no default creds or dev seeds in prod; adaptive password hash | I |
| V3 Session | Rotate on login; idle/absolute timeout; HttpOnly, Secure, SameSite; invalidated on disable/logout; no session id in URL/logs | I, S |
| V4 Access control | Full matrix (CM-1), IDOR (CM-3), verified-customer scope, vertical escalation, mass assignment (extra JSON fields ignored or 400) | I, S |
| V5 Validation | Bean validation on every body; injection on every sort/filter/search param; stored XSS round trips (case text, alias, notes) rendered escaped; CSV injection; 413/415 | I, F, S |
| V7 Errors/logging | No stack traces or secrets/PII/OTP/card data in logs (CM-8); correlation id; security events audited | I |
| V8 Data protection | PII masked in audit/outbox; no card data; encryption at rest/in transit verified in staging; retention jobs | I, UAT |
| V13 API | CSRF on mutating calls; CORS closed; method whitelist; rate limits on login, lookup (30/min), OTP start, webhook; webhook signature, replay and timestamp tolerance | I, S |
| V14 Config | Headers (CSP, HSTS, nosniff, frame-ancestors); actuator `health` only; prod refuses mocks; secrets only in env; no `MockCheckout` in prod | I, S |
| Supply chain | Dependency-Check/Trivy (Maven + image), `npm audit`, gitleaks, SBOM; fail PR on High with a fix available | CI |
| Dynamic | ZAP baseline nightly on e2e profile, authenticated scan per release; manual pen-test review before go-live | S |

### 4.3 Resilience of external ports (NFR-AVAIL, NFR-REL, BR-08)

| Port | Fault cases (mock fault modes; Toxiproxy for real adapters) | Expected |
|---|---|---|
| Payment gateway (initiate) | Refused, 5xx, timeout, slow, malformed body | FAILED or PENDING per design, 503 ProblemDetail, no double charge on retry, UI shows recoverable state |
| Payment webhook | Duplicate, out of order, delayed 24 h, bad/replayed signature, burst after outage | One transition, terminal states never regress, reconcile heals missing webhooks |
| Provisioning (plan apply, suspend/restore) | REJECT, UNAVAILABLE, TIMEOUT then success, provider reports other state | Backoff retry, no terminal give-up on suspend, EXPIRING/EXPIRED path, attention alert after 5 |
| Notification | Sender fails 3x; parallel dispatch | FAILED row, one send per row, originating request still succeeds |
| Verification-code sender | Down during OTP start | Clear error; attempt counters not wrongly consumed |
| DB | Connection drop mid-tx, deadlock retry, Flyway lock | No partial state; audit survives rollback by design |
| Process | Kill JAR during payment/job | Restart resumes via reconcile/jobs, idempotent |
| Clock | Mutable `Clock` around Asia/Kolkata midnight, UTC storage | Windows and expiries correct |

### 4.4 Data, backup/restore, migration, observability

| Area | Checks |
|---|---|
| Migration | Flyway `validate` in CI; migrate from a V1 snapshot with data to latest; merged migrations never edited (checksum guard); each migration has a documented rollback (forward-fix or restore) |
| Rollback drill | Restore pre-migration backup on staging and replay forward; record RTO |
| Backup/restore | Nightly dump/snapshot; restore to a fresh instance; app boots; row counts and audit triggers intact; RPO/RTO agreed in slice 06 |
| Seeds | Dev/e2e seeds idempotent, never active under `prod` |
| Observability | `X-Correlation-Id` on every response including 404/413; logs carry it; metrics for job last-run/failures, rate-limit rejections, gateway errors; alert fires in a fault-injection test; FE error report endpoint (slice 06) |

## 5. Execution roadmap

### 5.1 Phased backlog (risk order)

| Phase | Work | Effort | Exit |
|---|---|---|---|
| 0 Unblock | Fix e2e JAR hang (5.2); CI skeleton (section 6); OpenAPI-validation and ProblemDetail helpers | 3-4 d | PR pipeline green on `main` |
| 1 Slice 03 BE tests (P0) | Design s9 tests: IdentityCheckIT, VerifiedCustomerAccessIT, Reactivation*IT, InboundAuthzIT/IdorIT, schema, lookup, notify; deferred E1/E2 | 8-10 d | INB-01/04 Covered |
| 2 Slice 02 gaps (P0) | Concurrency (initiate on one invoice, OTP parallel confirm, reconcile x2, double claim), log sweep, BR-04 DTO test | 3 d | 3.2 gaps closed |
| 3 Slice 02 FE | RTL per page, MSW, vitest-axe; Playwright F21/F22 | 10 d | CUS-* Covered |
| 4 Security pass 1 | ASVS checks in MockMvc (injection, XSS round trip, headers, CSRF), dependency/secret scans, ZAP baseline | 5 d | No High findings |
| 5 Slice 03 FE + e2e | Per 3.3 FE row after design | 8 d | INB-* Covered |
| 6 Slices 04-05 | Tests written with each task, not deferred again | 10 d each | OUT-*, BUS-* Covered |
| 7 Non-functional | k6 profile + data generator, Lighthouse CI, Toxiproxy faults, restore drill | 8 d | 4.1 criteria met |
| 8 Release | Full a11y sweep, UAT evidence pack (XC-09), real-adapter contract tests, go-live checklist | 6 d | Gate 5.3 |

About 75 person-days; phases 1, 2, 4 first because they cover money, identity and authorization.

### 5.2 Known open items

| Item | Detail | Plan | Pri |
|---|---|---|---|
| Playwright F21 / F22 | `customer-portal.spec` and `payment.spec` are WIP; the JAR hangs under parallel load; payment spec must run serial on a fresh JAR | Reproduce with a thread dump (`jcmd <pid> Thread.print`) under 3-viewport parallelism; check Hikari/Tomcat thread exhaustion against session-JDBC and cron jobs; cap workers and pool sizes for `e2e`, wait on `/actuator/health`, add per-test timeouts; accept when green twice in a row (F22 criterion) | P0 |
| Slice 03 E1/E2 tests | Deferred by the user | Do in Phase 1; list the items in `03-inbound/status.md` first | P1 |
| `ProdAdapterCheck` | Passes only with real adapters, which do not exist yet | Keep negative test (mocks refused); add positive + contract tests in slice 06 | P0 |
| CUS-06.3 attachments | Deferred (OQ 4/5) | When designed: type/size allow-list, AV scan, authz, path traversal | P2 |
| Module cycle rule | `ArchitectureTest` cannot assert cycle freedom (codebase fails it) | Refactor or document an exemption | P2 |
| Frontend metrics | web-vitals deferred to slice 06 | Test with the reporting endpoint | P1 |

### 5.3 Release gate (slice 06) — all must be green

| # | Gate |
|---|---|
| G1 | Every P0 row in section 2 is Covered |
| G2 | Zero open Sev 1/2; Sev 3 triaged with an owner |
| G3 | Authz matrix (CM-1) and IDOR (CM-3) green for every endpoint of slices 01-05 |
| G4 | Payment and reactivation concurrency, webhook replay, reconciliation suites green 10 consecutive runs |
| G5 | Real adapters wired; `ProdAdapterCheck` passes; contract tests green |
| G6 | Load test meets 4.1; LCP <= 2.5 s; no soak leak |
| G7 | ZAP, dependency and secret scans free of High; ASVS list 4.2 signed off |
| G8 | Restore drill and migration rollback rehearsed within agreed RTO/RPO |
| G9 | Playwright at 3 viewports green on a fresh DB twice; axe has no serious/critical |
| G10 | UAT sign-off and QA evidence (XC-09); legal/compliance sign-off (BRD s12) |

### 5.4 Coverage tally (section 2, 50 rows)

| Status | Count | IDs |
|---|---|---|
| Covered | 4 | BR-01, BR-02, BR-07, NFR-MAINT |
| Partial | 29 | CUS-01..08; BR-03, 04, 05/06, 08, 10; XC-01..08; NFR-SEC, PRIV, AVAIL, REL, A11Y, RESP, OBS, INTEG |
| Deferred | 1 | CUS-06.3 (F21/F22 e2e and slice 03 E1/E2 are deferred work inside Partial / Not started rows) |
| Not started | 16 | INB-01..04, OUT-01..06, BUS-01..03, BR-09, XC-09, NFR-PERF |

## 6. CI pipeline proposal

Constraints: backend suite about 8 min (420+ tests, Testcontainers); frontend tooling in a Linux container; e2e needs a fresh JAR and DB.

| Stage | Suites | Trigger | Budget | Notes |
|---|---|---|---|---|
| S1 Fast checks | `check-docs.sh`, lint, type-check, FE unit/RTL + vitest-axe, backend unit (`*Test`), OpenAPI lint + type-gen diff, gitleaks | every PR | <= 4 min | Parallel jobs |
| S2 Backend IT | `check-backend.sh` (all `*IT`) split into 2-3 shards by class list; one MySQL container per shard | every PR | <= 8 min wall; 2-3 GB RAM per shard, `-Xmx1g`, tmpfs MySQL data dir | Cache `~/.m2`; ArchitectureTest first (fail fast) |
| S3 Security light | Dependency-Check/Trivy, `npm audit`; MockMvc authz matrix runs inside S2 | every PR | <= 5 min, parallel with S2 | Block on High with fix |
| S4 E2E smoke | Playwright setup + `auth-nav`, `forbidden`, one customer happy path; 1 viewport, 1 worker; fresh JAR + `telecom_support_e2e` | PRs touching FE or API contract | <= 8 min | Wait on `/actuator/health`; thread dump artifact on timeout |
| N1 Nightly e2e | Full Playwright, 3 viewports, serial payment project, axe, JAR restarted between runs, capped workers | nightly on `main` | <= 40 min | Traces and videos on failure |
| N2 Nightly security | ZAP baseline on e2e profile | nightly | <= 20 min | Report-only; triage within 2 days |
| N3 Nightly perf | k6 reduced profile (50 VUs, 10 min) + Lighthouse CI on seeded staging DB | nightly | <= 30 min | Warn at +20% vs baseline |
| R1 Pre-release | Full k6, Toxiproxy resilience, restore drill, adapter contract tests, full a11y sweep, flake check (suite x10) | release candidate | half a day | Required for gate 5.3 |

Policies: required PR checks are S1, S2, S3 (+ S4 when triggered); no quarantining flaky tests (fix or revert; never `@Disabled`); surefire/failsafe XML and Playwright reports kept as artifacts, `.logs/` kept on failure; JaCoCo and Vitest coverage are reported, but the gate is the section 2 matrix plus "no decrease on changed files".
