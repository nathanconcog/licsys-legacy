#!/usr/bin/env bash
# Puts one sample event of every contract type onto licsys-bus and shows the result.
# Needs LocalStack (modern/localstack/init-aws.sh) and the service on :8084.
set -euo pipefail
R=ap-southeast-2
BASE=${BASE:-http://localhost:8084}
put() { # source detail-type detail
  awslocal --region "$R" events put-events --entries \
    "$(jq -nc --arg s "$1" --arg t "$2" --arg d "$3" '[{EventBusName:"licsys-bus",Source:$s,DetailType:$t,Detail:$d}]')" \
    --query 'FailedEntryCount' --output text | sed "s/^/put $2 failed=/"
}
curl -s -X POST "$BASE/api/admin/reset"; echo
put licsys.licence DriverRegistered '{"driverId":"D0000008","name":"Henry Walker","licenceClass":"P1","classIssuedDate":"2026-03-01","p1IssuedDate":"2026-03-01","suspendedDays":0,"demeritPoints":0}'
put licsys.licence LicenceProgressed '{"driverId":"D0000001","name":"Alice Nguyen","fromClass":"P1","toClass":"P2","effectiveDate":"2026-10-20","tenureDays":414,"rule":"BR-02","reason":"Held P1 for 414 days; at least 365 required"}'
put licsys.demerit OffenceRecorded '{"driverId":"D0000008","offenceId":"O-1","date":"2026-10-19","code":"SPD20","offenceClass":"SP","basePoints":3,"appliedPoints":3,"doubled":false,"rule":"BR-05","reason":"No previous SP offence within 365 days"}'
put licsys.demerit DemeritPointsChanged '{"driverId":"D0000008","points":5,"limit":4,"licenceClass":"P1"}'
put licsys.demerit SuspensionThresholdReached '{"driverId":"D0000008","name":"Henry Walker","points":5,"limit":4,"licenceClass":"P1","rule":"BR-04","reason":"5 points is at or over the P1 limit of 4"}'
put licsys.demerit SuspensionThresholdReached '{"driverId":"D0000008","name":"Henry Walker","points":7,"limit":4,"licenceClass":"P1","rule":"BR-04","reason":"7 points is at or over the P1 limit of 4"}'
put licsys.registration RegistrationRenewed '{"renewalId":"R-1","plate":"ABC123","vehicleType":"CAR","term":12,"total":"389.15","rule":"BR-06"}'
sleep 3
echo "--- GET /api/notices"; curl -s "$BASE/api/notices" | jq -c '.[] | {type, driverId, rule, status}'
echo "--- GET /api/notices?driverId=D0000008"; curl -s "$BASE/api/notices?driverId=D0000008" | jq -r '.[].text'
echo "--- GET /api/activity?limit=100"; curl -s "$BASE/api/activity?limit=100" | jq -c '.[] | {time, source, detailType}'
