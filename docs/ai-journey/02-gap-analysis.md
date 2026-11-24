# 02 — Gap analysis: why the first attempt drifted, and the Claude mechanisms that fix it

- Branch: `ai/02-agents-skills` · Date: 2026-11-24
- Input: the first attempt (history up to `247b8fe`), PR #3 (clean start + approved foundation design)

## 1. What went wrong in the first attempt

| # | Symptom | Root cause | Impact |
|---|---|---|---|
| G1 | Code was written before a design existed. PR #2 shipped the whole of Phase 1 (backend + frontend) in one go | Nothing required a design to come first. "Plan" lived only in chat | Decisions (error model, permissions, paths) were made inside code and were never reviewed |
| G2 | One long session did design, backend, frontend and tests | No task boundaries, and state was kept in the conversation | Context filled up and got summarized. Earlier decisions were lost or re-derived, which risked hallucination |
| G3 | The full BRD (`brd.md`, 458 lines) was re-read for most tasks | No way to fetch just one requirement | Thousands of tokens per task, mostly irrelevant |
| G4 | Raw Maven/npm output (hundreds of lines) landed in context | Tests were run directly | Context was spent on noise, and failures were hard to spot |
| G5 | The same session wrote, tested and approved its own code | No independent reviewer or tester | Blind spots: the author checks what they meant, not what they wrote |
| G6 | Five code-pattern skills were written before any pattern was proven | Skills were treated as up-front documentation | The skills encoded unreviewed choices and multiplied them |
| G7 | No record of where a slice stood | Progress lived in chat | A new session couldn't resume safely and had to re-derive it, again risking hallucination |
| G8 | Rules in CLAUDE.md were not enforced | Hooks dir empty, no ArchUnit test yet | Rules depend on the model remembering them |

## 2. Fixes (introduced in this branch)

| Gap | Mechanism | Where |
|---|---|---|
| G1 | **Gated workflow**: design → `Status: Approved` → build. It is a CLAUDE.md rule and the `slice-workflow` skill, and `merge-steward` refuses to open a PR when the design isn't approved | `CLAUDE.md`, `.claude/skills/slice-workflow`, `.claude/agents/merge-steward.md` |
| G2 | **Bounded tasks + subagents**: `ba-planner` splits a slice into tasks of ≤ ~5 files each. Each task runs in a fresh context (a subagent or a new session), and agents return ≤ 15-line summaries with details written to files | `.claude/agents/*`, `docs/slices/<slice>/status.md` |
| G3 | **`brd-section` skill**: a script prints only the requested IDs (e.g. `CUS-06 BR-03`) | `.claude/skills/brd-section` |
| G4 | **Quiet check scripts**: full logs go to `.logs/`, and only counts plus failing test names are printed | `scripts/check-backend.sh`, `scripts/check-frontend.sh`, `.claude/skills/run-checks` |
| G5 | **Separate roles**: `test-engineer` writes tests from the design's acceptance criteria (not from the code). `reviewer` is read-only and checks the diff against the design and the rules | `.claude/agents/test-engineer.md`, `.claude/agents/reviewer.md` |
| G6 | **Skills follow proven patterns**: a code-pattern skill is created only after the pattern is approved and green in a slice, starting with `slice/01-foundation-be` | `slice-workflow` skill, rule 7 |
| G7 | **Status file as the single source of progress**: each slice has a `status.md` with gates, a task checklist and a handoff note. Any session resumes from it | `.claude/skills/slice-workflow/status-template.md` |
| G8 | **Enforcement**: an ArchUnit `ArchitectureTest` (designed in slice 01) is the first step. Hooks are a later branch | `docs/design/01-foundation/backend.md` §8 |

## 3. Agent roster

| Agent | Model | Writes | Never |
|---|---|---|---|
| `ba-planner` | opus | `docs/design/**`, `docs/slices/**` | code, tests |
| `test-engineer` | sonnet | test sources only | production code, designs |
| `reviewer` | opus | `docs/slices/<slice>/review.md` | any other file |
| `merge-steward` | sonnet | PR body (via `gh`) | merge without the user's explicit "merge" |

Implementation stays in the main session: one task from `status.md` at a time.

## 4. Token budget rules (applied by all agents and skills)
1. Read the BRD through `brd-section` only, and read only the design files named in the task.
2. Run checks through the quiet scripts. Open `.logs/*` only to investigate a specific failure (use `grep`, not the whole file).
3. Agent replies are ≤ 15 lines. Anything longer goes in a file, and the reply gives its path.
4. Each task is one row in `status.md`. If a task needs more than ~5 files, split it before starting.
5. After a task completes, update `status.md` (the handoff note) before doing anything else.

## 5. Dry-run results
See §6, filled in after `ba-planner` and `reviewer` were run against slice 01.
