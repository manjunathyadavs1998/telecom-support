#!/usr/bin/env bash
# Quiet frontend check. Runs npm ci, typecheck, lint and test inside a Linux node container,
# because Windows Application Control blocks unsigned native npm binaries (see CLAUDE.md).
# Full logs go to .logs/frontend-<step>.log; only the step results and the first errors are printed.
# Usage: scripts/check-frontend.sh [steps…]   (default: ci typecheck lint test)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.logs"; mkdir -p "$LOG_DIR"
IMAGE="${FRONTEND_IMAGE:-node:22-alpine}"

if [ ! -f "$ROOT/frontend/package.json" ]; then
  echo "[check-frontend] SKIP: frontend/package.json not found (no frontend code yet)"; exit 0
fi

HOST_DIR="$(cd "$ROOT/frontend" && (pwd -W 2>/dev/null || pwd))"
steps=("$@"); [ ${#steps[@]} -eq 0 ] && steps=(ci typecheck lint test)
overall=0
for step in "${steps[@]}"; do
  case "$step" in
    ci) cmd="npm ci --no-audit --no-fund" ;;
    test) cmd="npm test -- --run" ;;
    *) cmd="npm run $step" ;;
  esac
  log="$LOG_DIR/frontend-$step.log"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$HOST_DIR:/app" -w /app "$IMAGE" sh -c "$cmd" >"$log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    summary=$(grep -E "Tests? +[0-9]+ (passed|failed)|Test Files" "$log" | tail -2 | tr -s ' ' | tr '\n' ' ')
    echo "[check-frontend] $step: PASS ${summary}"
  else
    overall=$rc
    echo "[check-frontend] $step: FAIL (exit $rc). First errors:"
    grep -iE "error|fail|✗|×" "$log" | grep -vE "^npm (notice|warn)" | head -15 | cut -c1-200
    echo "[check-frontend] full log: .logs/frontend-$step.log"
    [ "$step" = ci ] && break
  fi
done
exit $overall
