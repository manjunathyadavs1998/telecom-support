---
name: run-checks
description: Run backend, frontend or design-doc checks with token-lean output (counts + failing items only; full logs in .logs/). Use instead of calling mvnw, npm, redocly or mermaid-cli directly, whenever you need to know whether something is green.
argument-hint: "backend|frontend|docs|all"
---

| Need | Command | Prints |
|---|---|---|
| Backend build + all tests | `bash scripts/check-backend.sh` (add `-Dit.test=AuthIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` for one IT) | totals + failing tests |
| Frontend ci/typecheck/lint/test/gen:api:check | `bash scripts/check-frontend.sh [steps…]` (Docker; repo root read-only at `/app`, only `frontend/` writable, node_modules in a named volume). Default `ci typecheck lint test gen:api:check`; opt-in `install` (only way to create/update the lockfile), `build` (→ `frontend/dist`), `e2e` (Playwright image; `E2E_BASE_URL` default `http://host.docker.internal:8080`, `E2E_PASSWORD`) | one line per step + first errors |
| Packaged JAR with SPA | `bash scripts/check-frontend.sh build`, then `cd backend && ./mvnw -Pfrontend package` (fails if `frontend/dist/index.html` is missing) | Maven summary |
| Design docs | `bash scripts/check-docs.sh` | OpenAPI lint + mermaid render per file |

Rules:
- Docker must be running (Testcontainers, the node image, mermaid-cli).
- To investigate a failure, `grep` the named log in `.logs/` for the test or class name. Never `cat` the whole log.
- Report the result in ≤ 5 lines: PASS/FAIL, counts, failing names, log path.
