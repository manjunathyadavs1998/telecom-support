# Traceability — BRD ID → design → code → tests

Updated with every design and build branch. Columns stay empty until the branch that fills them is merged.

Status: `Designed` (design approved) → `Built` (code + tests merged) → `Verified` (QA evidence).

## Slice 01 — Foundation

Code paths are relative to `backend/src/main/java/com/telecom/support/` unless they start with `db/` (resources). Tests: `backend/src/test/java`.

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| §2 | Four roles, role-based experiences | 01-foundation/backend.md §3 | `db/migration/V2__identity_rbac_audit.sql`, `identity/UserRepository` | PermissionMatrixIT, SchemaIT | Built |
| §8 | Permission matrix | 01-foundation/backend.md §3 | `V2__…sql` (matrix seed), `identity/Permissions` | PermissionMatrixIT (25 probes × 7 users) | Built |
| BR-10 | UI controls backed by server-side authorization | 01-foundation/backend.md §4–5 | `identity/SecurityConfig` (`@EnableMethodSecurity`), `@PreAuthorize` probes | PermissionMatrixIT, AuthIT | Built |
| XC-01 | Each role sees only its navigation | 01-foundation/frontend.md (to do) | | | — |
| XC-02 | Restricted routes denied; APIs enforce authz | 01-foundation/backend.md §4 | `identity/SecurityConfig`, `common/error/ProblemDetailFactory` | PermissionMatrixIT, AuthContractIT | Built |
| BR-03 | Changes record actor, time, old/new, reason | 00-overview §6; 01-foundation/backend.md §7 | `audit/AuditService`, `audit/AuditRepository`, V2 triggers | AuditTrailIT | Built |
| XC-06 | High-risk actions produce audit events | 00-overview §6 | `audit/AuditService`, `audit/DeniedAccessRecorder` | AuditTrailIT | Built |
| BR-01 | Unique, non-reusable reference IDs | 00-overview §8 | `common/reference/ReferenceGenerator` | ReferenceGeneratorIT | Built |
| NFR-SEC | Secure auth, sessions, least privilege | 01-foundation/backend.md §4; ADR 0003 | `identity/AuthService`, `LoginRateLimiter`, `SessionPolicyFilter`, `common/web/TrustedProxyFilter`, `application-prod.yml` | AuthIT, AuthContractIT, AuthServiceTest, LoginRateLimiterTest, ClientIpIT, ProdCookieIT | Built |
| NFR-REL | Idempotent create/payment/reactivation | 00-overview §7 | `common/idempotency/IdempotencyService`, `IdempotencyPurgeJob` | IdempotencyServiceIT | Built |
| NFR-OBS | Correlation ids, no secrets in logs | 00-overview §5 | `common/web/CorrelationIdFilter`, `common/error/GlobalExceptionHandler` | ErrorModelIT, ProblemShapeIT, AuthContractIT (no secrets in logs) | Built |
| NFR-MAINT | Feature modules, typed contracts | 00-overview §1, §3; ADR 0001 | package-by-feature, `common.security` ports | ArchitectureTest (no JPA, module repos, no cycles) | Built |
