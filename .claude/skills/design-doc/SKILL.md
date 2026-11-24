---
name: design-doc
description: Compact, fixed-heading templates for slice design docs (backend.md, frontend.md). Use when writing or revising any file under docs/design/<slice>/.
argument-hint: "backend|frontend <slice-id>"
---

Copy the template next to this file (`backend-template.md` or `frontend-template.md`) into `docs/design/<slice>/` and fill it in.

Rules for a lean design:
- Prefer tables and diagrams over prose. Don't restate `docs/design/00-overview.md` conventions; link to them (`00-overview §4`).
- Every row names its BRD ID. Pull BRD text with the `brd-section` skill.
- A design is a contract for the build. Name tables, columns, endpoints, permissions, states, audit actions and tests concretely.
- Unresolved points go in **Open questions**, each with a default, so building isn't blocked.
- The header starts as `Status: **Proposed** (awaiting approval)`. Only the user's approval changes it to `**Approved**`.
- Before handing over, validate: `openapi.yaml` passes `npx @redocly/cli lint` and every mermaid block renders (see `run-checks`).
  Avoid `;` inside mermaid sequence messages.
- Aim for ≤ 250 lines per design file. If a slice needs more, split it into sub-slices.
