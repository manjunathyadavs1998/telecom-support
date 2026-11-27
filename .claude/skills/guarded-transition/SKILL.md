---
name: guarded-transition
description: Recipe for a status machine with guarded transitions, an external provider call, retry jobs and an idempotent create endpoint (proven in slices 02 payment and 03 reactivation). Use when building any new stateful flow that a request thread, scheduled jobs and other instances can all touch.
---

Copy the shape of `reactivation/` (paths under `backend/src/main/java/com/telecom/support/`):

1. **Repository** (`ReactivationRepository`): one text-block `UPDATE … SET status=:to, …, version=version+1 WHERE id=:id AND status IN (:from)`
   per transition, returning `jdbc.update(...) == 1`. Retry bookkeeping is guarded by `version = :version` (one count per
   failure under parallel runs). Alert flags are guarded by `flag = FALSE` (set once). Due-row reads use keyset paging: `id > :after ORDER BY id LIMIT :limit`.
2. **Single writer** (`ReactivationTransitionService`): one `@Transactional` method per transition. Side effects (outbox
   `NotificationService.enqueue`, `AuditService.record` with from/to/source) run **only when 1 row changed**. Job actor = null.
   A decision that depends on another module's row locks it first in the same tx (`AccountLookup.lockForUpdate`).
3. **Provider calls run outside any tx**: commit PENDING first (tx1), call, then record the outcome through (2).
   Map the outcomes: rejected → FAILED, unavailable → FAILED + retry allowed, timeout/unknown → stays, reconciled later.
4. **Jobs** (`ReactivationReconcileJob`, `ReactivationExpiryJob`; payment: `PaymentReconciliationJob`): `@Scheduled(cron = "${app.x.y-cron:…}")`,
   `-` disables it in tests (`AbstractMySqlIT`) and `ProfileGuard` refuses `-` in prod. Page 100, stop at a run budget
   (package-private `runBudget`/`pageSize` test seams), and catch per row so one failure never stops a run. Never give up:
   backoff list + `next_attempt_at`, alert flag + ERROR log + FAILURE audit once.
5. **Idempotent endpoint** (`ReactivationController.create`): `idempotency.execute(userId, OPERATION, key,
   new KeyedRequest(pathVars, body), () -> ResponseEntity.status(201).body(service.create(...)))`. The key hash covers
   path variables and body. Scope (`VerifiedCustomer`/principal) is resolved before `execute`, so a replay re-checks it.
6. **Tests** (`reactivation/ReactivationJobsIT`, `ReactivationCreateIT`): insert rows directly, drive the mutable test
   clock (`InboundTestClock`) to each `next_attempt_at`, call the job's `run()`. Assert the backoff list, the alert once,
   and **parallel runs** (`CountDownLatch` + thread pool) → one audit row per transition and one counted attempt.

Don't: write a status outside the single writer, call a provider inside a tx, or add a terminal "give up" state.
