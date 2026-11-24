#!/usr/bin/env bash
# Quiet design-doc check: lints every docs/design/**/openapi.yaml with Redocly and renders every mermaid block
# in docs/design/**/*.md with mermaid-cli (both run in Docker). Prints one line per file plus the first errors.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.logs"; mkdir -p "$LOG_DIR/mermaid"
PY=""; for c in python3 python; do "$c" -c "import sys" >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "[$(basename "$0" .sh)] FAIL: no working python3/python found"; exit 2; }
host() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
overall=0

for spec in $(cd "$ROOT" && find docs/design -name 'openapi.yaml' | sort); do
  log="$LOG_DIR/redocly-$(echo "$spec" | tr '/' '_').log"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(host "$ROOT/docs")":/docs node:22-alpine \
    sh -c "npx -y @redocly/cli@latest lint --format=stylish /$spec" >"$log" 2>&1
  rc=$?
  w=$(grep -cE "^\s+[0-9]+:[0-9]+\s+warning" "$log"); e=$(grep -cE "^\s+[0-9]+:[0-9]+\s+error" "$log")
  if [ $rc -eq 0 ]; then echo "[check-docs] $spec: PASS (warnings=$w)"; else
    overall=1; echo "[check-docs] $spec: FAIL (errors=$e warnings=$w)"; grep -E "error" "$log" | head -10; fi
done

rm -f "$LOG_DIR"/mermaid/*.mmd "$LOG_DIR"/mermaid/*.svg
"$PY" - "$ROOT" "$LOG_DIR/mermaid" <<'EOF'
import sys, re, pathlib
root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
for md in sorted((root / "docs" / "design").rglob("*.md")):
    for i, b in enumerate(re.findall(r"```mermaid\n(.*?)```", md.read_text(encoding="utf-8"), re.S)):
        name = str(md.relative_to(root)).replace("\\", "/").replace("/", "_") + f"__{i}.mmd"
        (out / name).write_text(b, encoding="utf-8")
EOF
for f in "$LOG_DIR"/mermaid/*.mmd; do
  [ -e "$f" ] || { echo "[check-docs] mermaid: none found"; break; }
  n=$(basename "$f")
  if MSYS_NO_PATHCONV=1 docker run --rm -v "$(host "$LOG_DIR/mermaid")":/data minlag/mermaid-cli \
       -i "/data/$n" -o "/data/${n%.mmd}.svg" >"$f.log" 2>&1; then
    echo "[check-docs] mermaid $n: PASS"
  else
    overall=1; echo "[check-docs] mermaid $n: FAIL"; grep -m2 -E "Error|Expecting" "$f.log" | cut -c1-200
  fi
done
exit $overall
