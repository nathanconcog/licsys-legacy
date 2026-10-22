#!/usr/bin/env bash
# LICSYS Next demo driver (fictional demo system).
#   ./demo.sh up       docker compose up -d --build, wait for every /actuator/health
#   ./demo.sh load     strangler loader on ../data
#   ./demo.sh parity   COBOL batch vs LICSYS Next on ../data -> ui/public/parity.json
#   ./demo.sh reset    POST /api/admin/reset on every service
#   ./demo.sh down     docker compose down -v
#   ./demo.sh stub     run the fake services (modern/tools/testing) instead of docker
# BASE_URLS overrides the service URLs (licence=URL,demerit=URL,registration=URL,notification=URL).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
PORTS=(8081 8082 8083 8084)
BASE_ARGS=()
[ -n "${BASE_URLS:-}" ] && BASE_ARGS=(--base-urls "$BASE_URLS")

wait_healthy() {
  local deadline=$((SECONDS + ${HEALTH_TIMEOUT:-300}))
  for p in "${PORTS[@]}"; do
    until curl -fs "http://localhost:$p/actuator/health" >/dev/null; do
      [ $SECONDS -ge $deadline ] && { echo "service on :$p not healthy after ${HEALTH_TIMEOUT:-300}s" >&2; docker compose ps; exit 1; }
      sleep 2
    done
    echo "  :$p healthy"
  done
}

case "${1:-}" in
  up)
    docker compose up -d --build
    echo "waiting for services..."
    wait_healthy
    echo "LICSYS Next is up: UI http://localhost:5173"
    ;;
  load)   python3 tools/loader/loader.py --data-dir "$ROOT/data" "${BASE_ARGS[@]}" --renewals ;;
  parity) python3 tools/parity/parity.py --case "$ROOT" --out "${PARITY_OUT:-ui/public/parity.json}" "${BASE_ARGS[@]}" ;;
  reset)
    for p in "${PORTS[@]}"; do curl -fsS -X POST "http://localhost:$p/api/admin/reset" >/dev/null && echo "  :$p reset"; done
    ;;
  down)   docker compose down -v ;;
  stub)   exec python3 tools/testing/fake_services.py ;;
  *)      sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
