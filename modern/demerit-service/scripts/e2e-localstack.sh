#!/usr/bin/env bash
# End-to-end check on LocalStack: publish DriverRegistered for every driver in data/DRVMAST.dat to licsys-bus,
# import data/OFFENCES.dat through POST /api/offences (status P => alreadyPosted=true), then check the
# expected policy results and the events that reached activity-inbox.
# Needs: LocalStack with modern/localstack/init-aws.sh applied, demerit-service on :8082, aws CLI, python3.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
API=${DEMERIT_URL:-http://localhost:8082}
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=ap-southeast-2
aws_() { aws --endpoint-url "${AWS_ENDPOINT_URL:-http://localhost:4566}" "$@"; }
ACTIVITY=$(aws_ sqs get-queue-url --queue-name activity-inbox --query QueueUrl --output text)

echo "== reset"
curl -sf -X POST "$API/api/admin/reset"; echo
aws_ sqs purge-queue --queue-url "$ACTIVITY"

echo "== publish DriverRegistered x $(wc -l < "$ROOT/data/DRVMAST.dat")"
python3 - "$ROOT/data/DRVMAST.dat" > /tmp/driver-events.json <<'PY'
import json, sys
def d(s): return None if s == "00000000" else f"{s[:4]}-{s[4:6]}-{s[6:]}"
entries = []
for l in open(sys.argv[1]):
    l = l.rstrip("\n")
    if len(l) < 60: continue
    detail = {"driverId": l[0:8], "name": l[8:28].strip(), "licenceClass": l[36:38], "classIssuedDate": d(l[38:46]),
              "p1IssuedDate": d(l[46:54]), "suspendedDays": int(l[56:60]), "demeritPoints": int(l[54:56])}
    entries.append({"EventBusName": "licsys-bus", "Source": "licsys.licence", "DetailType": "DriverRegistered",
                    "Detail": json.dumps(detail)})
print(json.dumps([entries[i:i + 10] for i in range(0, len(entries), 10)]))
PY
for i in $(python3 -c 'import json;print(" ".join(str(i) for i in range(len(json.load(open("/tmp/driver-events.json"))))))'); do
  python3 -c "import json;print(json.dumps(json.load(open('/tmp/driver-events.json'))[$i]))" > /tmp/batch.json
  aws_ events put-events --entries file:///tmp/batch.json --query FailedEntryCount --output text
done

echo "== wait for the demerit-inbox projection"
for _ in $(seq 1 30); do
  n=0
  for id in $(cut -c1-8 "$ROOT/data/DRVMAST.dat"); do
    curl -sf "$API/api/drivers/$id/demerits" >/dev/null && n=$((n + 1))
  done
  [ "$n" -eq "$(wc -l < "$ROOT/data/DRVMAST.dat")" ] && break
  sleep 1
done
echo "projection has $n drivers"

echo "== import data/OFFENCES.dat"
while IFS= read -r l; do
  [ ${#l} -ge 25 ] || continue
  posted=false; [ "${l:24:1}" = "P" ] && posted=true
  body=$(printf '{"driverId":"%s","date":"%s-%s-%s","code":"%s","offenceClass":"%s","basePoints":%d,"alreadyPosted":%s}' \
    "${l:0:8}" "${l:8:4}" "${l:12:2}" "${l:14:2}" "${l:16:4}" "${l:20:2}" "$((10#${l:22:2}))" "$posted")
  echo "POST $body"
  curl -sf -X POST "$API/api/offences" -H 'Content-Type: application/json' -d "$body" | python3 -c '
import json,sys; r=json.load(sys.stdin)
print("  -> %s applied=%s doubled=%s total=%s/%s notice=%s\n     %s\n     %s" % (r["offenceId"], r["appliedPoints"], r["doubled"],
      r["totalPoints"], r.get("limit"), r["suspensionNotice"], r["reason"], r["threshold"]["reason"]))'
done < "$ROOT/data/OFFENCES.dat"

echo "== check expected results"
python3 - "$API" <<'PY'
import json, sys, urllib.request
api = sys.argv[1]
expect = {"D0000009": (10, False), "D0000013": (5, False), "D0000010": (11, False), "D0000008": (5, True)}
ok = True
for driver, (points, notice) in expect.items():
    v = json.load(urllib.request.urlopen(f"{api}/api/drivers/{driver}/demerits"))
    good = v["points"] == points and v["suspensionNotice"] == notice
    ok &= good
    print(f"{'PASS' if good else 'FAIL'} {driver} {v['name']:<14} {v['licenceClass']} points={v['points']} "
          f"limit={v['limit']} suspensionNotice={v['suspensionNotice']}  ({v['reason']})")
sys.exit(0 if ok else 1)
PY

echo "== activity-inbox"
sleep 2
python3 - <<'PY'
import collections, json, os, subprocess
ep = os.environ.get("AWS_ENDPOINT_URL", "http://localhost:4566")
def aws(*a): return json.loads(subprocess.check_output(["aws", "--endpoint-url", ep, *a, "--output", "json"]) or "{}")
url = aws("sqs", "get-queue-url", "--queue-name", "activity-inbox")["QueueUrl"]
seen, counts, empty = set(), collections.Counter(), 0
suspensions = []
while empty < 2:
    msgs = aws("sqs", "receive-message", "--queue-url", url, "--max-number-of-messages", "10",
               "--wait-time-seconds", "1").get("Messages", [])
    if not msgs:
        empty += 1
        continue
    for m in msgs:
        e = json.loads(m["Body"])
        if e["id"] in seen: continue
        seen.add(e["id"])
        counts[(e["source"], e["detail-type"])] += 1
        if e["detail-type"] == "SuspensionThresholdReached": suspensions.append(e["detail"])
        aws("sqs", "delete-message", "--queue-url", url, "--receipt-handle", m["ReceiptHandle"])
for (src, t), n in sorted(counts.items()):
    print(f"{n:3d}  {src:<16} {t}")
for s in suspensions:
    print("SuspensionThresholdReached", json.dumps(s))
PY
