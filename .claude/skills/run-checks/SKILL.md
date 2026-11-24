---
name: run-checks
description: Run backend, frontend or design-doc checks with token-lean output (counts + failing items only; full logs in .logs/). Use instead of calling mvnw, npm, redocly or mermaid-cli directly, whenever you need to know whether something is green.
argument-hint: "backend|frontend|docs|all"
---

| Need | Command | Prints |
|---|---|---|
| Backend build + all tests | `bash scripts/check-backend.sh` (add `-Dit.test=AuthIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` for one IT) | totals + failing tests |
| Frontend ci/typecheck/lint/test | `bash scripts/check-frontend.sh [ci typecheck lint test]` (runs in Docker; Windows blocks native npm binaries) | one line per step + first errors |
| Design docs | `bash scripts/check-docs.sh` | OpenAPI lint + mermaid render per file |

Rules:
- Docker must be running (Testcontainers, the node image, mermaid-cli).
- To investigate a failure, `grep` the named log in `.logs/` for the test or class name. Never `cat` the whole log.
- Report the result in ≤ 5 lines: PASS/FAIL, counts, failing names, log path.
