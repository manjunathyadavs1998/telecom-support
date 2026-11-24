#!/usr/bin/env python3
"""Print only the requested parts of docs/brd/brd.md.

Usage: python brd_section.py ID [ID ...]
  CUS-06 / INB-04 / OUT-01 / BUS-02   whole requirement section (description + acceptance criteria)
  CUS-06.3                            one acceptance criterion
  BR-03 / XC-06 / NFR-SEC             one rule / cross-cutting criterion / NFR row
  §8  or  8                           whole numbered chapter (e.g. permission matrix)
  --list                              list all requirement IDs with titles (cheap index)
Images and blank lines are dropped to save tokens.
"""
import re
import sys
from pathlib import Path

BRD = Path(__file__).resolve().parents[3] / "docs" / "brd" / "brd.md"


def load():
    return BRD.read_text(encoding="utf-8").splitlines()


def block(lines, start, level):
    """Lines from heading at `start` until the next heading of same or higher level."""
    out = [lines[start]]
    for line in lines[start + 1:]:
        m = re.match(r"^(#+) ", line)
        if m and len(m.group(1)) <= level:
            break
        out.append(line)
    return out


def find(lines, key):
    key = key.strip().lstrip("§")
    if re.fullmatch(r"\d+", key):  # chapter
        for i, l in enumerate(lines):
            if re.match(rf"^## {key}\. ", l):
                return block(lines, i, 2)
    if re.fullmatch(r"(CUS|INB|OUT|BUS)-\d+", key):  # requirement section
        for i, l in enumerate(lines):
            if re.match(rf"^### {key} ", l):
                return block(lines, i, 3)
    if re.fullmatch(r"(CUS|INB|OUT|BUS)-\d+\.\d+|XC-\d+", key):  # list item
        return [l for l in lines if re.match(rf"^- {re.escape(key)} ", l)]
    if re.fullmatch(r"(BR|NFR)-[A-Z0-9]+", key):  # table row
        return [l for l in lines if l.startswith(f"| {key} |")]
    return []


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    lines = load()
    if argv[0] == "--list":
        for l in lines:
            if re.match(r"^### (CUS|INB|OUT|BUS)-\d+ ", l) or re.match(r"^## \d+\. ", l):
                print(l.lstrip("# "))
        return 0
    missing = 0
    for key in argv:
        found = [l for l in find(lines, key) if l.strip() and not l.startswith("![")]
        if found:
            print("\n".join(found))
            print()
        else:
            print(f"[brd-section] not found: {key}", file=sys.stderr)
            missing += 1
    return 1 if missing else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
