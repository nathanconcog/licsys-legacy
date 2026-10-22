#!/usr/bin/env bash
# End-to-end demo against LocalStack: register the legacy drivers, feed tonight's demerit postings,
# run the progression sweep for 2026-10-20 and show the events that reached activity-inbox.
# Needs LocalStack (container name in $LOCALSTACK, default licsys-localstack) and the service on :8081.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${BASE:-http://localhost:8081}
LS=${LOCALSTACK:-licsys-localstack}
aws_() { docker exec "$LS" awslocal --region ap-southeast-2 "$@"; }
say() { printf '\n$ %s\n' "$*"; }
ACTIVITY=$(aws_ sqs get-queue-url --queue-name activity-inbox --query QueueUrl --output text)

say curl -s $BASE/actuator/health; curl -s "$BASE/actuator/health"; echo
say curl -s -X POST $BASE/api/admin/reset; curl -s -X POST "$BASE/api/admin/reset"; echo
aws_ sqs purge-queue --queue-url "$ACTIVITY"

say demo/register-legacy.py ../../data/DRVMAST.dat; demo/register-legacy.py ../../data/DRVMAST.dat "$BASE"

# What demerit-service publishes after posting tonight's offences (policy totals, BR-05 same-class rule).
say "aws events put-events ... DemeritPointsChanged (tonight's postings)"
for p in D0000006:8:12:OP D0000008:5:4:P1 D0000009:10:12:OP D0000010:11:12:OP D0000011:3:12:OP D0000012:3:4:P2 D0000013:5:12:OP; do
  IFS=: read -r id pts lim cls <<<"$p"
  detail=$(printf '{\\"driverId\\":\\"%s\\",\\"points\\":%s,\\"limit\\":%s,\\"licenceClass\\":\\"%s\\"}' "$id" "$pts" "$lim" "$cls")
  aws_ events put-events --entries "[{\"EventBusName\":\"licsys-bus\",\"Source\":\"licsys.demerit\",\"DetailType\":\"DemeritPointsChanged\",\"Detail\":\"$detail\"}]" \
    --query 'FailedEntryCount' --output text | sed "s/^/  $id points=$pts failed=/"
done
for _ in $(seq 1 30); do
  [ "$(curl -s "$BASE/api/drivers/D0000008" | python3 -c 'import json,sys;print(json.load(sys.stdin)["demeritPoints"])')" = 5 ] && break
  sleep 0.5
done
say curl -s $BASE/api/drivers/D0000008; curl -s "$BASE/api/drivers/D0000008"; echo

say curl -s "$BASE/api/drivers/D0000004/progression?asOf=2026-10-20"
curl -s "$BASE/api/drivers/D0000004/progression?asOf=2026-10-20"; echo

say curl -s -X POST "$BASE/api/progression/run?runDate=2026-10-20"
curl -s -X POST "$BASE/api/progression/run?runDate=2026-10-20" | python3 -m json.tool

say curl -s $BASE/api/drivers  "(summary)"
curl -s "$BASE/api/drivers" | python3 -c "$(cat <<'PY'
import json, sys
for d in json.load(sys.stdin):
    h = d["history"][-1] if d["history"] else None
    moved = "  <- %s (%s)" % (h["fromClass"], h["rule"]) if h else ""
    print("%s %-20s %s class=%s pts=%2d susp=%3d%s" % (d["driverId"], d["name"], d["licenceClass"],
          d["classIssuedDate"], d["demeritPoints"], d["suspendedDays"], moved))
PY
)"

say "aws sqs receive-message --queue-url .../activity-inbox  (drained, grouped)"
sleep 2
python3 - "$LS" "$ACTIVITY" <<'PY'
import collections, json, subprocess, sys
ls, url = sys.argv[1:3]
seen, counts, progressed = set(), collections.Counter(), []
for _ in range(20):
    out = subprocess.run(["docker", "exec", ls, "awslocal", "--region", "ap-southeast-2", "sqs", "receive-message",
                          "--queue-url", url, "--max-number-of-messages", "10", "--wait-time-seconds", "1"],
                         capture_output=True, text=True, check=True).stdout
    msgs = json.loads(out).get("Messages", []) if out.strip() else []
    if not msgs:
        break
    for m in msgs:
        e = json.loads(m["Body"])
        if e["id"] in seen:
            continue
        seen.add(e["id"])
        counts[(e["source"], e["detail-type"])] += 1
        if e["detail-type"] == "LicenceProgressed":
            progressed.append(e["detail"])
        subprocess.run(["docker", "exec", ls, "awslocal", "--region", "ap-southeast-2", "sqs", "delete-message",
                        "--queue-url", url, "--receipt-handle", m["ReceiptHandle"]], check=True)
for (src, t), n in sorted(counts.items()):
    print(f"  {n:>2} x {t:<22} source={src}")
for d in progressed:
    print("  LicenceProgressed", json.dumps(d))
PY
