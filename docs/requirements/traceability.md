# Traceability — BRD ID → design → code → tests

Updated with every design and build branch. Columns stay empty until the branch that fills them is merged.

Status: `Designed` (design approved) → `Built` (code + tests merged) → `Verified` (QA evidence).

## Slice 01 — Foundation

Code paths are relative to `backend/src/main/java/com/telecom/support/` unless they start with `db/` (resources). Tests: `backend/src/test/java`.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| §2 | Four roles, role-based experiences | 01-foundation/backend.md §3 | `db/migration/V2__identity_rbac_audit.sql`, `identity/UserRepository` | PermissionMatrixIT, SchemaIT | Built |
| §8 | Permission matrix | 01-foundation/backend.md §3 | `V2__…sql` (matrix seed), `identity/Permissions` | PermissionMatrixIT (25 probes × 7 users) | Built |
| BR-10 | UI controls backed by server-side authorization | 01-foundation/backend.md §4–5; frontend.md §2, §4 (Proposed) | `identity/SecurityConfig` (`@EnableMethodSecurity`), `@PreAuthorize` probes | PermissionMatrixIT, AuthIT | Built (BE) · Proposed (FE) |
| XC-01 | Each role sees only its navigation | 01-foundation/frontend.md §2, §10 (rev 2) | | | Proposed |
| XC-03 | Loading, success, empty, validation, failure states | 01-foundation/frontend.md §3, §4, §6 | | | Proposed |
| XC-07 | Payment, profile update, case transition, reactivation succeed only when the backend confirms | 01-foundation/frontend.md §5 (no optimistic mutations) | | | Proposed |
| XC-08 | Keyboard-operable; tested at desktop/tablet/mobile | 01-foundation/frontend.md §8, §10 | | | Proposed |
| NFR-A11Y | Semantic controls, keyboard, visible focus, contrast, accessible validation | 01-foundation/frontend.md §3, §6, §8 | | | Proposed |
| NFR-RESP | Desktop-first agent workspaces, responsive tablet/mobile | 01-foundation/frontend.md §8 | | | Proposed |
| XC-02 | Restricted routes denied; APIs enforce authz | 01-foundation/backend.md §4; frontend.md §2, §3, §7, §10 (Proposed); backend.md §12 E2.1 (Proposed) | `identity/SecurityConfig`, `common/error/ProblemDetailFactory` | PermissionMatrixIT, AuthContractIT | Built (BE) · Proposed (FE) |
| BR-03 | Changes record actor, time, old/new, reason | 00-overview §6; 01-foundation/backend.md §7 | `audit/AuditService`, `audit/AuditRepository`, V2 triggers | AuditTrailIT | Built |
| XC-06 | High-risk actions produce audit events | 00-overview §6 | `audit/AuditService`, `audit/DeniedAccessRecorder` | AuditTrailIT | Built |
| BR-01 | Unique, non-reusable reference IDs | 00-overview §8 | `common/reference/ReferenceGenerator` | ReferenceGeneratorIT | Built |
| NFR-SEC | Secure auth, sessions, least privilege | 01-foundation/backend.md §4; ADR 0003; frontend.md §7, §7.1 (Proposed); backend.md §12 E2.3–E2.5 (Proposed) | `identity/AuthService`, `LoginRateLimiter`, `SessionPolicyFilter`, `common/web/TrustedProxyFilter`, `application-prod.yml` | AuthIT, AuthContractIT, AuthServiceTest, LoginRateLimiterTest, ClientIpIT, ProdCookieIT | Built (BE) · Proposed (FE) |
| NFR-REL | Idempotent create/payment/reactivation | 00-overview §7 | `common/idempotency/IdempotencyService`, `IdempotencyPurgeJob` | IdempotencyServiceIT | Built |
| NFR-OBS | Correlation ids, no secrets in logs | 00-overview §5; frontend.md §4, §7, OQ 8, OQ 10 (Proposed) | `common/web/CorrelationIdFilter`, `common/error/GlobalExceptionHandler` | ErrorModelIT, ProblemShapeIT, AuthContractIT (no secrets in logs) | Built (BE) · Proposed (FE) |
| NFR-MAINT | Feature modules, typed contracts | 00-overview §1, §3; ADR 0001; frontend.md §1, §7, §8, §9 (Proposed); backend.md §12 E2.2, E2.6 (Proposed) | package-by-feature, `common.security` ports | ArchitectureTest (no JPA, module repos, no cycles) | Built (BE) · Proposed (FE) |
