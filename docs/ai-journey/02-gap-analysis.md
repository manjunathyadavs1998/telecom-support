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

## 5. Dry-run results (slice 01, 2026-11-24)

| Run | Result | Cost |
|---|---|---|
| `ba-planner` plan-tasks | `docs/slices/01-foundation/status.md`: 9 ordered backend tasks (B1–B9). Each maps to a named test in the design's test plan; all 10 BRD IDs are covered | ~64k tokens, 6 tool calls, 1.5 min. The main session read only a 15-line summary |
| `reviewer` design review | `docs/slices/01-foundation/review.md`: 20 findings (2 High, 11 Medium, 7 Low) | ~82k tokens, 11 tool calls, 3 min |
| Triage (main session) | 19 accepted. 1 rejected as a false positive: the reviewer claimed the design wasn't approved without checking git | — |
| `ba-planner` revision | design revision 2 addresses #2–#20, goes back to **Proposed**, and needs the user's re-approval | — |

What this showed:
- **G5 is confirmed.** The author (the main session) missed a real High: per-account lockout let anyone lock out any customer
  by guessing their mobile number. It also missed CSRF token rotation, an idempotency transaction race and a first-of-day race
  in the reference generator. An independent read-only reviewer with a narrow brief caught all of them before any code existed,
  which is the cheapest point to fix them.
- **Reviewers can hallucinate process state.** Finding #1 asserted facts (the repo isn't a git repo; the design isn't merged)
  without checking. Fix: `reviewer.md` rule 5 now makes it verify process state with git. The main session still triages
  every finding; findings are input, not orders.
- **Agents created mid-session aren't available straight away.** The first spawn failed with "agent type not found", so the dry run
  used general-purpose agents that were told to follow `.claude/agents/<name>.md` (same prompt, but tool restrictions weren't enforced).
  A few minutes later Claude Code picked the files up without a restart, and later tasks use the real subagents.
- **Changing an approved design re-opens the gate.** The revision sets `Status: Proposed` and unticks "Approved" in
  `status.md`. Building waits for the user to re-approve.
