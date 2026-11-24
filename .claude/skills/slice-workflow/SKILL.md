---
name: slice-workflow
description: The gated design-first delivery loop for one BRD slice (design → approve → build → test → review → PR → user merges), and how to resume a slice from its status file in a fresh session. Use when starting, continuing or asking "where are we" on any slice, or before writing any production code.
argument-hint: "<slice-id e.g. 01-foundation> [be|fe]"
---

## State lives in a file, not in chat
`docs/slices/<slice>/status.md` (template: `status-template.md` next to this file) is the single source of progress.
**Resume:** read only that file plus the design files it names. Don't re-derive state from git log or the conversation.

## Gate sequence (per layer: `be`, then `fe`)
| # | Step | Who | Output | Gate to pass |
|---|---|---|---|---|
| 1 | Design | `ba-planner` agent | `docs/design/<slice>/{backend,frontend}.md` (+ `openapi.yaml` for be) | — |
| 2 | Approve | **user** | `Status: Approved` in the design header | user says "approved" |
| 3 | Plan tasks | `ba-planner` | task table in `status.md` (each task ≤ ~5 files, has a "done when") | — |
| 4 | Build | main session, one task at a time | code | task's "done when" holds |
| 5 | Test | `test-engineer` agent | tests from the acceptance criteria, then `run-checks` green | all checks green |
| 6 | Review | `reviewer` agent | `docs/slices/<slice>/review.md` | no open High findings |
| 7 | PR | `merge-steward` agent | PR opened, gates listed in the body | — |
| 8 | Merge | **user** | merged to `main` | user says "merge" or merges it |

Branches: design-only work goes on `design/NN-<name>`. Building goes on `slice/NN-<name>-be` / `-fe`, cut from fresh `main` after the previous merge.

## Rules
1. No production code before step 2 for that layer. If the design turns out wrong mid-build, stop, update the design and its
   status, and ask the user to re-approve.
2. One task per context. Run a task in a subagent or a fresh session when the current context is already large.
3. After each task, tick it in `status.md` and rewrite the **Handoff** section (≤ 5 lines: what's done, what's next, any blockers).
4. Agents reply in ≤ 15 lines. Details go in files.
5. BRD text comes only from the `brd-section` skill. Check output comes only from the `run-checks` skill.
6. Never run `gh pr merge` without the user's explicit "merge".
7. When a code pattern is first approved and green (e.g. a JDBC endpoint, a Flyway migration, an RBAC check), capture it as
   a project skill in that same branch. Not before.
