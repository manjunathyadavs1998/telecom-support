# Traceability — BRD ID → design → code → tests

Updated with every design and build branch. Columns stay empty until the branch that fills them is merged.

Status: `Designed` (design approved) → `Built` (code + tests merged) → `Verified` (QA evidence).

## Slice 01 — Foundation

| BRD ID | Requirement (short) | Design | Code | Tests | Status |
|---|---|---|---|---|---|
| §2 | Four roles, role-based experiences | 01-foundation/backend.md §3 | | PermissionMatrixIT | Proposed |
| §8 | Permission matrix | 01-foundation/backend.md §3 | | PermissionMatrixIT | Proposed |
| BR-10 | UI controls backed by server-side authorization | 01-foundation/backend.md §4–5 | | PermissionMatrixIT | Proposed |
| XC-01 | Each role sees only its navigation | 01-foundation/frontend.md (to do) | | | — |
| XC-02 | Restricted routes denied; APIs enforce authz | 01-foundation/backend.md §4 | | PermissionMatrixIT | Proposed |
| BR-03 | Changes record actor, time, old/new, reason | 00-overview §6; 01-foundation/backend.md §7 | | AuditTrailIT | Proposed |
| XC-06 | High-risk actions produce audit events | 00-overview §6 | | AuditTrailIT | Proposed |
| BR-01 | Unique, non-reusable reference IDs | 00-overview §8 | | ReferenceGeneratorIT | Proposed |
| NFR-SEC | Secure auth, sessions, least privilege | 01-foundation/backend.md §4; ADR 0003 | | AuthIT | Proposed |
| NFR-REL | Idempotent create/payment/reactivation | 00-overview §7 | | IdempotencyServiceIT | Proposed |
| NFR-OBS | Correlation ids, no secrets in logs | 00-overview §5 | | ErrorModelIT | Proposed |
| NFR-MAINT | Feature modules, typed contracts | 00-overview §1, §3; ADR 0001 | | ArchitectureTest | Proposed |
