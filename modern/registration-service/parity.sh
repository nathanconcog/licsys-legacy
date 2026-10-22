#!/usr/bin/env bash
# BR-06 parity: runs legacy COBOL REGFEE and the running registration-service on the same VEHICLES.dat records
# and diffs every fee line and total.
#
# Usage: modern/registration-service/parity.sh [--count N] [--seed S]
#   REGISTRATION_URL  service base URL (default http://localhost:8083)
#   PARITY_OUT        where reports/diffs are kept (default modern/registration-service/target/parity)
#
# Datasets: data/VEHICLES.dat, tests/cases/05-regfee-fees, tests/cases/06-invalid-records and N (default 250)
# generated records including invalid ones. Exit 0 when the only differences are the documented
# policy-vs-COBOL cases (see parity/parity.py), 1 otherwise. Needs GnuCOBOL 3.x, python3 and curl.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
URL="${REGISTRATION_URL:-http://localhost:8083}"
OUT="${PARITY_OUT:-$HERE/target/parity}"
COUNT=250
SEED=2026
while [ $# -gt 0 ]; do
  case "$1" in
    --count) COUNT="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v cobc >/dev/null || { echo "cobc not found: install GnuCOBOL 3.x (apt-get install gnucobol)" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
curl -fsS "$URL/actuator/health" >/dev/null \
  || { echo "registration-service is not up at $URL (start it first, see README.md)" >&2; exit 2; }
export LC_ALL=C

rm -rf "$OUT"
mkdir -p "$OUT/bin"
echo "== building REGFEE ($(cobc --version | head -1))"
cobc -x -std=default -I "$ROOT/copybooks" -o "$OUT/bin/REGFEE" "$ROOT/src/REGFEE.cbl"

python3 "$HERE/parity/parity.py" generate --count "$COUNT" --seed "$SEED" "$OUT/random-VEHICLES.dat"

datasets=(
  "shipped-sample:$ROOT/data/VEHICLES.dat"
  "05-regfee-fees:$ROOT/tests/cases/05-regfee-fees/data/VEHICLES.dat"
  "06-invalid-records:$ROOT/tests/cases/06-invalid-records/data/VEHICLES.dat"
  "random-$COUNT-seed-$SEED:$OUT/random-VEHICLES.dat"
)

status=0
results=()
for ds in "${datasets[@]}"; do
  name="${ds%%:*}"
  file="${ds#*:}"
  run="$OUT/$name"
  mkdir -p "$run/data" "$run/out"
  cp "$file" "$run/data/VEHICLES.dat"
  (cd "$run" && "$OUT/bin/REGFEE") >"$run/console.log"
  echo "== $name: $(wc -l <"$run/data/VEHICLES.dat") records"
  set +e
  python3 "$HERE/parity/parity.py" compare --name "$name" --vehicles "$run/data/VEHICLES.dat" \
    --report "$run/out/REGFEES.rpt" --url "$URL" --diffs "$run/diffs.txt" | tee "$run/result.txt"
  rc=${PIPESTATUS[0]}
  set -e
  [ "$rc" -eq 0 ] || status=1
  results+=("$(grep '^RESULT' "$run/result.txt")")
done

echo
echo "== summary (reports in $OUT)"
printf '%s\n' "${results[@]}"
if [ "$status" -eq 0 ]; then
  echo "PARITY OK: 0 unexpected differences"
else
  echo "PARITY FAILED: unexpected differences above"
fi
exit "$status"
