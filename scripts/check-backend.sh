#!/usr/bin/env bash
# Quiet backend check: runs `./mvnw verify` (unit + Testcontainers ITs), writes the full log to .logs/,
# and prints only a summary and the failing tests. Exit code = Maven's exit code.
# Usage: scripts/check-backend.sh [extra maven args, e.g. -Dit.test=AuthIT]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.logs"; mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/backend-verify.log"
PY=""; for c in python3 python; do "$c" -c "import sys" >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "[$(basename "$0" .sh)] FAIL: no working python3/python found"; exit 2; }

if [ ! -f "$ROOT/backend/pom.xml" ]; then
  echo "[check-backend] SKIP: backend/pom.xml not found (no backend code yet)"; exit 0
fi

cd "$ROOT/backend"
start=$(date +%s)
./mvnw -B -ntp verify "$@" >"$LOG" 2>&1
rc=$?
secs=$(( $(date +%s) - start ))

"$PY" - "$ROOT/backend/target" <<'EOF'
import sys, glob, os, xml.etree.ElementTree as ET
t = sys.argv[1]; tot = fail = err = skip = 0; bad = []
for f in glob.glob(os.path.join(t, "surefire-reports", "TEST-*.xml")) + glob.glob(os.path.join(t, "failsafe-reports", "TEST-*.xml")):
    r = ET.parse(f).getroot()
    tot += int(r.get("tests", 0)); fail += int(r.get("failures", 0)); err += int(r.get("errors", 0)); skip += int(r.get("skipped", 0))
    for tc in r.iter("testcase"):
        for kind in ("failure", "error"):
            e = tc.find(kind)
            if e is not None:
                msg = (e.get("message") or e.get("type") or "").strip().splitlines()
                bad.append(f"  {tc.get('classname','').split('.')[-1]}.{tc.get('name')}: {(msg[0] if msg else kind)[:160]}")
print(f"[check-backend] tests={tot} failed={fail} errors={err} skipped={skip}")
for b in bad[:20]: print(b)
if len(bad) > 20: print(f"  … {len(bad)-20} more (see log)")
EOF

if [ $rc -ne 0 ]; then
  echo "[check-backend] FAIL (exit $rc, ${secs}s). First build errors:"
  grep -E "^\[ERROR\]" "$LOG" | grep -vE "Tests run:|^\[ERROR\] *$|-> \[Help|Re-run Maven|full stack trace|To see the full|For more information|^\[ERROR\] Failures:|^\[ERROR\] Errors:" | head -15
  echo "[check-backend] full log: .logs/backend-verify.log"
else
  echo "[check-backend] PASS (${secs}s)"
fi
exit $rc
