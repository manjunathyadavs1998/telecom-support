---
name: rbac-permission
description: Add or change a permission or role end to end (DB seed, Java constant, @PreAuthorize, frontend constant + nav gating, permission-matrix test). Use when a feature needs an authorization rule, when BRD §8 is referenced, or when a role should gain or lose a capability.
---

# RBAC permission recipe

The authorization model follows BRD §8: user → roles → permissions. Authorities are **permission strings**
(`case.assign`) plus `ROLE_<NAME>`. The UI may hide things, but **only the backend decides** (BR-10, XC-02).

## Touch points (keep all of them in sync)
1. **Migration**: roles, permissions and role grants are reference data that every environment needs. They were created in
   `V2__identity_rbac_audit.sql`. To change them, add a **new** versioned migration (flyway-migration skill) with
   `INSERT IGNORE INTO permissions …` and the `role_permissions` mapping. Demo *users* belong in `db/seed/R__01_identity.sql`.
2. **Java constant**: `backend/src/main/java/com/telecom/support/security/Permissions.java`, as `public static final String`.
3. **Endpoint**: `@PreAuthorize("hasAuthority(T(com.telecom.support.security.Permissions).NEW_ONE)")`.
4. **Frontend constant**: `frontend/src/permissions/permissions.ts`, with the same string.
5. **Navigation / actions**: add `permission` to the nav item in `frontend/src/app/navigation.ts` or wrap the action in `<Can permission=…>`.
6. **Route guard**: `<RequirePermission permission=…>` in `frontend/src/app/router.tsx`.
7. **Matrix test**: add the expectation to `backend/src/test/java/com/telecom/support/security/PermissionMatrixIT.java`.
   It asserts every role × permission cell from BRD §8, so a missing or extra grant fails the build.

## Role catalogue
| Role | Who |
|---|---|
| `CUSTOMER` | Self-service customer |
| `INBOUND_AGENT` | Inbound assistant |
| `REACTIVATION_APPROVER` | Extra role for inbound agents with "special permission" for temporary reactivation |
| `OUTBOUND_AGENT` | Outbound assistant |
| `OUTBOUND_SUPERVISOR` | Outbound lead with reassignment (`case.assign.any`) |
| `BUSINESS_ANALYST` | Business team: analytics, audit view, export (non-PII) |
| `BUSINESS_AUDITOR` | Business user with `reports.export.pii` |

## Checks
- Least privilege: grant the narrowest permission. Don't reuse a broad one because it's "close enough".
- Changing a grant changes the BRD §8 matrix. Record it in `docs/requirements/traceability.md` and mention it in the PR.
