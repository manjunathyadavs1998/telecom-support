---
name: brd-section
description: Fetch only the BRD requirements you need (CUS-06, BR-03, XC-06, NFR-SEC, §8 …) instead of reading the whole docs/brd/brd.md. Use whenever a task, design or review mentions a BRD ID or needs requirement text.
argument-hint: "<ID> [ID ...] | --list"
---

Never read `docs/brd/brd.md` whole. Run:

```bash
python .claude/skills/brd-section/brd_section.py CUS-06 BR-03 XC-06   # exact text of these IDs
python .claude/skills/brd-section/brd_section.py --list                # index of sections (cheap)
python .claude/skills/brd-section/brd_section.py §8                    # a whole chapter, e.g. the permission matrix
```

Supported IDs: `CUS|INB|OUT|BUS-nn` (section), `CUS-nn.n` (one criterion), `BR-nn`, `XC-nn`, `NFR-XXX`, `§n` (chapter).
If an ID is reported as not found, run `--list` and then grep for it. Don't fall back to reading the whole file.
