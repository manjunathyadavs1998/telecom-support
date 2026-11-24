# Telecom Customer Support

A **modular monolith** (React + Spring Boot + MySQL) for a postpaid telecom operator's customer support. Claude Code generates the code through a **design-first, gated** workflow: every slice is designed and approved before any code is written.

- Business requirements: [`docs/brd/brd.md`](docs/brd/brd.md) (converted from the original `.docx`)
- Architecture decisions: [`docs/adr/`](docs/adr/)
- Designs (the contract for every build branch): [`docs/design/`](docs/design/)
- Coverage: [`docs/requirements/traceability.md`](docs/requirements/traceability.md)

## Workflow

For every BRD delivery phase (slice):

```
backend design ──► approve ──► backend build ──► test ──► review ──► PR ──► merge
frontend design ─► approve ──► frontend build ─► test ──► review ──► PR ──► merge
```

No code is written without an approved design doc. Each step is its own branch cut from a fresh `main`, and a person approves every merge.

## Branches

| # | Branch | Purpose |
|---|---|---|
| 1 | `design/01-foundation` | Clean start + Phase 1 (Foundation) backend design |
| 2 | `ai/02-agents-skills` | Gap analysis; subagents (BA/planner, tester, reviewer, merge steward) and token-lean skills |
| 3+ | `slice/NN-<name>-be`, `slice/NN-<name>-fe` | One BRD phase per slice: 01 foundation, 02 customer, 03 inbound, 04 outbound, 05 business, 06 production readiness |

The first attempt (code written before any design) remains in git history up to commit `247b8fe`, for reference only.
