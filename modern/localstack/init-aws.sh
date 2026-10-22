#!/usr/bin/env bash
# Creates LICSYS Next AWS resources in LocalStack (runs as a LocalStack ready hook).
set -euo pipefail
R=ap-southeast-2
aws_() { awslocal --region "$R" "$@"; }
aws_ events create-event-bus --name licsys-bus >/dev/null
for t in licence-drivers:driverId demerit-ledger:driverId registration-renewals:renewalId notification-activity:eventId; do
  aws_ dynamodb create-table --table-name "${t%%:*}" --attribute-definitions AttributeName="${t##*:}",AttributeType=S \
    --key-schema AttributeName="${t##*:}",KeyType=HASH --billing-mode PAY_PER_REQUEST >/dev/null
done
route() { # queue pattern
  local q=$1 pattern=$2
  local url arn
  url=$(aws_ sqs create-queue --queue-name "$q" --query QueueUrl --output text)
  arn=$(aws_ sqs get-queue-attributes --queue-url "$url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  aws_ events put-rule --name "to-$q" --event-bus-name licsys-bus --event-pattern "$pattern" >/dev/null
  aws_ events put-targets --rule "to-$q" --event-bus-name licsys-bus --targets "Id=1,Arn=$arn" >/dev/null
}
route licence-inbox      '{"detail-type":["DemeritPointsChanged"]}'
route demerit-inbox      '{"detail-type":["DriverRegistered","LicenceProgressed"]}'
route notification-inbox '{"detail-type":["SuspensionThresholdReached","LicenceProgressed"]}'
route activity-inbox     '{"source":[{"prefix":"licsys."}]}'
echo "licsys resources ready"
