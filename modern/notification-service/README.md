# notification-service (LICSYS Next)

*Fictional demo system. Not real government code.*

Notifications bounded context plus the system-wide activity feed. Port **8084**. Contract: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).
Java 17, Spring Boot 3.3, Maven, AWS SDK for Java v2.

## What it does
| Input | Behaviour |
|---|---|
| SQS `notification-inbox`: `SuspensionThresholdReached` | Issues a **SUSPENSION** notice (BR-04), at most one open notice per driver; publishes `NoticeIssued` |
| SQS `notification-inbox`: `LicenceProgressed` | Issues an **UPGRADE** notice (rule from the event, e.g. BR-02/BR-03), once per progression; publishes `NoticeIssued` |
| SQS `activity-inbox`: every `licsys.*` event | Stores it in DynamoDB `notification-activity` and pushes it to SSE clients |

Notice text is a letter modelled on the legacy `NOTICES.rpt` (`src/DMRPTS.cbl`) and `UPGRADES.rpt` (`src/LICUPG.cbl`) lines: driver id, upper-case name, class, points/limit or from/to/tenure/effective date, rule and reason.

Both queues are long-polled on dedicated threads (`licsys.sqs-wait-seconds`, default 1s). SQS returns as soon as a message arrives, so events reach the SSE stream in tens of milliseconds (measured ~25 ms from `PutEvents` to SSE `data:` on LocalStack).

## API
| Method | Path | Notes |
|---|---|---|
| GET | `/api/notices?driverId=` | Newest first; `driverId` optional. `[{noticeId, driverId, type, status, text, issuedAt, rule, reason, sourceEventId}]` |
| GET | `/api/activity?limit=100` | Newest first (by event `time`, then arrival). `[{eventId, time, source, detailType, detail}]` |
| GET | `/api/activity/stream` | SSE. Each event: `id:<eventId>`, `event:activity`, `data:<same JSON as /api/activity>`. Keep-alive comment every 15s |
| POST | `/api/admin/reset` | Clears notices and activity |
| GET | `/actuator/health` | |

CORS allows `http://localhost:5173` (including the SSE stream).

## Storage
Single table `notification-activity` (pk `eventId`, the only table this service owns):
- activity items: `eventId` = EventBridge event id, `kind=ACTIVITY` (redeliveries are dropped);
- notice items: `eventId` = `NOTICE#SUSPENSION#<driverId>` or `NOTICE#UPGRADE#<driverId>#<toClass>#<effectiveDate>`, `kind=NOTICE`. Written with `attribute_not_exists(eventId)`, so idempotency holds across SQS redelivery and multiple instances.

## Run
```bash
# LocalStack with the contract resources
docker compose -f ../docker-compose.yml up -d localstack
mvn verify                       # unit + HTTP/SSE tests (no AWS needed)
mvn spring-boot:run              # or: java -jar target/notification-service.jar
```
Config via env: `AWS_ENDPOINT_URL` (default `http://localhost:4566`), `AWS_REGION` (`ap-southeast-2`), `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (`test`).

Docker: `docker build -t licsys-notification-service .` (multi-stage on `eclipse-temurin:17`), or `docker compose up --build notification-service` from `modern/`.

## Demo
```bash
curl -N http://localhost:8084/api/activity/stream &   # live feed
./demo.sh    # puts one event of every contract type (needs awslocal + jq), shows notices and activity
```
`demo.sh` sends `SuspensionThresholdReached` twice for D0000008; only one notice is issued.
