#!/usr/bin/env bash
# LICSYS regression harness.
#
# Builds the batch programs, runs the nightly batch (run_nightly.sh) once per
# fixture case in tests/cases/<case>/data/, and compares every file the batch
# writes to out/, plus its console output, with the golden files in
# tests/cases/<case>/expected/.
#
# The golden files record what the code does today, not what policy says it
# should do. A diff means behaviour changed.
#
# Usage:
#   tests/run_tests.sh [--update] [case ...]
#
#   --update   overwrite the golden files with the current output
#   case       run only the named case directories (default: all)
#
# Requires bash and GnuCOBOL 3.x (cobc) only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CASES_DIR="$ROOT/tests/cases"
UPDATE=0
SELECTED=()

for arg in "$@"; do
  case "$arg" in
    --update) UPDATE=1 ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) SELECTED+=("${arg%/}") ;;
  esac
done

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL 3.x" >&2; exit 2; }
export LC_ALL=C

echo "== building ($(cobc --version | head -1))"
"$ROOT/build.sh"

if [ ${#SELECTED[@]} -eq 0 ]; then
  for d in "$CASES_DIR"/*/; do SELECTED+=("$(basename "$d")"); done
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
failed=()

for name in "${SELECTED[@]}"; do
  case_dir="$CASES_DIR/$name"
  if [ ! -d "$case_dir/data" ]; then
    echo "FAIL $name: no such case ($case_dir/data missing)"
    fail=$((fail + 1)); failed+=("$name"); continue
  fi

  run="$WORK/$name"
  mkdir -p "$run"
  cp "$ROOT/run_nightly.sh" "$run/"
  ln -s "$ROOT/bin" "$run/bin"
  cp -R "$case_dir/data" "$run/data"

  set +e
  (cd "$run" && ./run_nightly.sh) >"$run/console.log" 2>&1
  status=$?
  set -e
  echo "exit status: $status" >>"$run/console.log"
  mkdir -p "$run/out"

  expected="$case_dir/expected"
  if [ "$UPDATE" -eq 1 ]; then
    rm -rf "$expected"
    mkdir -p "$expected"
    cp -R "$run/out" "$expected/out"
    cp "$run/console.log" "$expected/console.log"
    echo "UPDATED $name"
    pass=$((pass + 1))
    continue
  fi

  if [ ! -d "$expected/out" ]; then
    echo "FAIL $name: no golden files (run with --update to record them)"
    fail=$((fail + 1)); failed+=("$name"); continue
  fi

  if diff -r -u "$expected/out" "$run/out" >"$run/diff.txt" 2>&1 \
     && diff -u "$expected/console.log" "$run/console.log" >>"$run/diff.txt" 2>&1; then
    echo "PASS $name"
    pass=$((pass + 1))
  else
    echo "FAIL $name"
    sed 's/^/    /' "$run/diff.txt"
    fail=$((fail + 1)); failed+=("$name")
  fi
done

echo "== $pass passed, $fail failed"
if [ "$fail" -gt 0 ]; then
  echo "failed: ${failed[*]}"
  exit 1
fi
