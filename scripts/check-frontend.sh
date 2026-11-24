#!/usr/bin/env bash
# Quiet frontend check. Runs npm steps inside a Linux node container, because Windows Application Control
# blocks unsigned native npm binaries (see CLAUDE.md). The repo root is mounted at /app (work dir
# /app/frontend) so gen:api:check can read docs/design; node_modules lives in a named Docker volume so host
# files and Linux binaries never mix. Full logs go to .logs/frontend-<step>.log; only step results and the
# first errors are printed.
# Usage: scripts/check-frontend.sh [steps…]   (default: ci typecheck lint test gen:api:check)
#   opt-in: install (npm install: the only way to create/update package-lock.json), build (→ frontend/dist),
#           e2e (Playwright image matching @playwright/test; E2E_BASE_URL, E2E_PASSWORD passed through)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.logs"; mkdir -p "$LOG_DIR"
IMAGE="${FRONTEND_IMAGE:-node:22-alpine}"
NM_VOLUME="${FRONTEND_NM_VOLUME:-telecom-frontend-node-modules}"
E2E_BASE_URL="${E2E_BASE_URL:-http://host.docker.internal:8080}"

if [ ! -f "$ROOT/frontend/package.json" ]; then
  echo "[check-frontend] SKIP: frontend/package.json not found (no frontend code yet)"; exit 0
fi

HOST_ROOT="$(cd "$ROOT" && (pwd -W 2>/dev/null || pwd))"
steps=("$@"); [ ${#steps[@]} -eq 0 ] && steps=(ci typecheck lint test gen:api:check)
overall=0
for step in "${steps[@]}"; do
  image="$IMAGE"; extra=()
  case "$step" in
    install) cmd="npm install --no-audit --no-fund" ;;
    ci) cmd="npm ci --no-audit --no-fund" ;;
    test) cmd="npm test -- --run" ;;
    e2e)
      pw=$(sed -nE 's/.*"@playwright\/test": *"[^0-9]*([0-9][0-9.]*)".*/\1/p' "$ROOT/frontend/package.json" | head -1)
      if [ -z "$pw" ]; then
        echo "[check-frontend] e2e: FAIL (no @playwright/test version in frontend/package.json)"; overall=1; continue
      fi
      image="mcr.microsoft.com/playwright:v${pw}-noble"
      extra=(-e "E2E_BASE_URL=$E2E_BASE_URL" -e E2E_PASSWORD --add-host=host.docker.internal:host-gateway)
      cmd="npm run e2e" ;;
    *) cmd="npm run $step" ;;
  esac
  log="$LOG_DIR/frontend-${step//:/-}.log"
  MSYS_NO_PATHCONV=1 docker run --rm -v "$HOST_ROOT:/app" -v "$NM_VOLUME:/app/frontend/node_modules" \
    -w /app/frontend "${extra[@]}" "$image" sh -c "$cmd" >"$log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    summary=$(grep -E "Tests? +[0-9]+ (passed|failed)|Test Files|[0-9]+ passed" "$log" | tail -2 | tr -s ' ' | tr '\n' ' ')
    echo "[check-frontend] $step: PASS ${summary}"
  else
    overall=$rc
    echo "[check-frontend] $step: FAIL (exit $rc). First errors:"
    grep -iE "error|fail|✗|×" "$log" | grep -vE "^npm (notice|warn)" | head -15 | cut -c1-200
    echo "[check-frontend] full log: .logs/$(basename "$log")"
    case "$step" in ci|install) break ;; esac
  fi
done
exit $overall
