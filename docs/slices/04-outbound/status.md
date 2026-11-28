# Slice 04-outbound — status

| Gate | Backend | Frontend |
|---|---|---|
| Design | ☑ `docs/design/04-outbound/backend.md` + `openapi.yaml` — **Approved** rev 1 (branch `design/04-outbound`; design review skipped by the user) | ☐ `docs/design/04-outbound/frontend.md` — to design (may run in parallel with the BE build, own worktree) |
| Approved by user | ☑ 2026-11-28 (all OQ defaults) | ☐ |
| Tasks planned | ☑ B1–B13 confirmed 2026-11-28 | ☐ |
| Built | ☐ branch `slice/04-outbound-be` | ☐ branch `slice/04-outbound-fe` |
| Checks green | ☐ | ☐ |
| Review clean (no open High) | ☐ `review.md` | ☐ `review-frontend.md` |
| PR | ☐ #… | ☐ #… |
| Merged (by user) | ☐ | ☐ |

## Tasks — backend (refs are `backend.md` sections / rules / endpoints)

Paths: main code under `backend/src/main/java/com/telecom/support/`, migrations/seed under `backend/src/main/resources/db/`, tests under
`backend/src/test/…`. **Tests are deferred** (user decision 2026-11-27, CLAUDE.md): each task is done when it compiles,
`ArchitectureTest` passes (`bash scripts/check-backend.sh`) and its named manual check passes on the dev JAR (`curl` with a dev
session, or SQL on the dev DB). The §9 tests (incl. the forbidden-role case of every endpoint) are listed in
`docs/testing/test-plan.md` §3.4 by B13 and written when the deferral ends. No new permission, no new module edge.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| B1 | V11 schema + seed fixes | `db/migration/V11__outbound.sql`, `db/seed/R__02_customer.sql`, `db/seed/R__02_customer_e2e.sql`, test-data `R__test_users_02_customer.sql` (RESOLVED/CLOSED rows set `resolved_at`, `closed_at`, `closure_type`) | §2 | OUT-05.3, OUT-06.1, NFR-INTEG | dev JAR starts on a **fresh** dev DB and on the existing one (Flyway V11 applied, seeds load, CHECKs hold); `check-backend.sh` green | ☑ (+ `SchemaIT`, `SchemaV3toV6IT` expectations: V10/V11 in the migration list, V11 columns in the reference-unique copy) |
| B2 | Dev + test seed for the outbound queue | `db/seed/R__04_outbound.sql`, test-data `R__test_users_04_outbound.sql` | §2 | OUT-01..06 | dev DB has the §2 rows: pool cases, OUTBOUND_N/S owned cases, one overdue CRITICAL, RESOLVED 4 days ago, CLOSED 3 and 10 days ago, OUT001 bookmarks (one changed) | ☑ (test data on its own customer CUST-T-0010 + agents T-OUT-2, T-OUT-S; `SchemaIT`/`SchemaV3toV6IT` lists updated) |
| B3 | Cross-module APIs | `identity/UserDirectory.java` (`queueAgents`, `queueAgent`), `identity/UserRepository.java`, `customer/AccountLookup.java` (`caseContexts`, `accountIdsByLiveMsisdn`), `customer/AccountRepository.java` | §6.1 | OUT-01.1, OUT-02, OUT-03.2, NFR-MAINT | compile + `ArchitectureTest` green (edges unchanged, repositories module-private) | ☑ |
| B4 | Servicecase skeleton: scope, lifecycle, properties, problem reason | `servicecase/{OutboundScope,OutboundProperties,CaseLifecycle,CaseRuleException}.java`, `application.yml` (`app.outbound.*`) | §3 scope, §3.1, R8, R9, §6 | OUT-01.4, OUT-05.1, §14 #3/#7 | compile; `CaseLifecycle` encodes exactly the §3.1 table (reviewed against it); `CaseRuleException` renders `CaseProblem.reason` | ☑ |
| B5 | Queue summary, search, categories, agents O1–O4 | `servicecase/{QueueRepository,QueueSearchValidator,OutboundCaseQueryService,OutboundCaseController,CategoryRepository}.java` | R1, R2, R3, R13, §4 | OUT-01, OUT-02, XC-04/05, NFR-PERF | as OUT001: O1 returns counts + window; O2 `{}` returns the 3-month page with `filters.created` echoed; `createdFrom > createdTo` or > 366 days → 400; OUT003 does not see OUTBOUND_N-owned cases; SUP001 sees all; IN001 → 403 | ☐ |
| B6 | Case detail O5 + shared timeline names | `servicecase/{CaseRepository,CaseTimelineAssembler,OutboundCaseQueryService,OutboundCaseController,AgentCaseQueryService}.java` | R4, §6 | OUT-04, BR-04, NFR-PRIV | O5 as owner returns full `contactMsisdn`, as a team-mate `null` + masked; INTERNAL rows only with `case.note.internal`; other team's case → 404; 03 I10 still returns its timeline (ASSIGNED values as names) | ☐ |
| B7 | Atomic claim O6 | `servicecase/{CaseRepository,CaseActivityRepository,CaseCommandService,OutboundCaseController}.java` | R5, §3.2 | OUT-03.1, OUT-03.4, BR-03 | two parallel `curl` claims (OUT001, OUT002) on one pool case → one 200, one 409 `already_assigned`; SQL shows one ASSIGNED activity and one `CASE_ASSIGNED` audit row; owner retry → 200 no-op | ☐ |
| B8 | Reassign O7 | `servicecase/{CaseCommandService,CaseRepository,OutboundCaseController}.java` | R6, §3.2, §7 | OUT-03.2, OUT-03.3, BR-03 | SUP001 reassigns an OUT001 case to OUT003 → 200, team = OUTBOUND_S, audit has old/new owner + reason; OUT001 → 403; old `version` → 409 `stale_version`; disabled target → 422 `invalid_assignee` | ☐ |
| B9 | Status lifecycle O8 | `servicecase/{CaseCommandService,CaseRepository,CaseActivityRepository,CaseLifecycle,OutboundCaseController}.java` | R7–R9, §3.1, §3.2 | OUT-05.1–05.5, BR-03, XC-07 | owner: → RESOLVED without remarks → 400, with → 200; CLOSED `WINDOW_ELAPSED` < 3 days → 422 `closure_condition_unmet`; seeded CLOSED 10 days → REOPENED 422 `reopen_window_expired`, 3 days → 200; non-owner OUT002 → 403; CREATED → IN_PROGRESS → 422 | ☐ |
| B10 | Notes and comments O9 | `servicecase/{CaseCommandService,CaseActivityRepository,OutboundCaseController}.java` | R10 | OUT-04.1, OUT-04.3, BR-04, NFR-REL | INTERNAL note → 201; same `Idempotency-Key` replay → same body, one row; different body → 422 `idempotency-mismatch`; PUBLIC by a non-owner agent → 403; PUBLIC on CLOSED → 422 `case_closed`; `cases.updated_at` bumped | ☐ |
| B11 | Auto-close job | `servicecase/{CaseAutoCloseJob,CaseRepository}.java`, `common/ProfileGuard.java`, `application.yml` (`auto-close-cron`) | R8, §3.2 | OUT-05.4 | seeded RESOLVED-4-days case becomes CLOSED `WINDOW_ELAPSED` within 5 min with one activity + audit (actor null); prod profile with `auto-close-cron: "-"` refuses to start | ☐ |
| B12 | Bookmarks O10–O13 | `servicecase/{BookmarkRepository,BookmarkService,OutboundBookmarkController,CaseCommandService,QueueRepository}.java` | R11, R12 | OUT-06.1–06.4 | PUT twice → 204, one row; O12 lists with `changedCount`; a note by OUT002 on OUT001's bookmarked case → `changed: true` for OUT001, O13 clears it; OUT001's own note does not flag; DELETE absent → 204; O2 rows show `bookmarked` | ☐ |
| B13 | Authz sweep, traceability, deferred-test entries | `docs/requirements/traceability.md`, `docs/testing/test-plan.md` (§3.4 rows for §9) | §4, §9 | BR-10, XC-02, all | every O1–O13 probed as CUSTOMER, IN001, BUS001 → 403 and anonymous → 401; OUT001 on O7 → 403; `check-backend.sh` green; slice 04 rows `Built` | ☐ |

## Tasks — frontend (refs are `frontend.md` sections)

To design (`ba-planner` frontend mode) after the backend design is approved; may run in parallel with the BE build.

| # | Task | Files (≤ ~5) | Design ref | BRD IDs | Done when | ☐/☑ |
|---|---|---|---|---|---|---|
| F1 | — | | | | | ☐ |

## Handoff (rewrite after every task, ≤ 5 lines)
- Done: backend design + openapi **Approved** 2026-11-28 with all OQ defaults, B1–B13 confirmed, traceability rows `Designed`.
- B1 done 2026-11-28 on `slice/04-outbound-be`: V11 applied on the existing dev DB (11 RESOLVED/CLOSED rows backfilled) and on a fresh DB (16 migrations, seeds load); `check-backend.sh` 611/611 after fixing 3 schema-test expectations (`SchemaV3toV6IT` had been missing V10 since 03 E1).
- B2 done 2026-11-28: dev seed 900401–900409 + OUT001 bookmarks verified on the dev DB; test data on CUST-T-0010 with T-OUT-2 (same team) and T-OUT-S (south); `check-backend.sh` 611/611.
- B3/B4 done: cross-module APIs (queue agents, case contexts, accounts by live MSISDN); scope, lifecycle table, properties, `CaseRuleException`; compile + ArchitectureTest 8/8.
- Next: B5.
- Blockers / open questions: none blocking; 18 OQs carry defaults (backend.md §10). Tests deferred (user, 2026-11-27).
