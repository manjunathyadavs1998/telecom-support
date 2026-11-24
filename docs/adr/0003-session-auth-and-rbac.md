# ADR 0003 — Server-side sessions and permission-based RBAC

- Status: Accepted · Date: 2026-11-24

## Context
The SPA and the API share one origin (ADR 0001). The BRD requires least privilege, server-side authorization (BR-10), session controls and auditability. JWTs kept in browser storage are open to XSS theft and are hard to revoke.

## Decision
- Spring Security form-style JSON login (`POST /api/v1/auth/login`) creates a **server session** stored with **Spring Session JDBC** in MySQL. Cookie settings: `HttpOnly`, `SameSite=Lax`, `Secure` in prod, 30-minute idle timeout.
- CSRF protection uses `CookieCsrfTokenRepository` (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header).
- Passwords use BCrypt. Login attempts are rate-limited per IP + username.
- Tables: `roles`, `permissions`, `role_permissions`, `user_roles`. Authorities are the BRD §8 permission strings, and endpoints use `@PreAuthorize("hasAuthority('…')")`.
- Record-level scope (own account, team queue, verified customer) is enforced in services.

## Consequences
- Logout and admin revocation take effect at once (the session row is deleted).
- The frontend never handles tokens. It reads `/api/v1/auth/me` for roles and permissions so it can render navigation.
