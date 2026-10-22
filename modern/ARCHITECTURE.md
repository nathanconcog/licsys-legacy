# LICSYS Next: target architecture and contracts

*Fictional demo system. Not real government code.* `policy/BUSINESS-RULES.md` is the source of truth for every rule. The COBOL in `src/` is the legacy reference.

The new system implements the **policy**, not the COBOL bugs. The parity harness expects exactly these differences from the legacy batch:

- D0000004, D0000005 and D0000012 stay P2 (BR-03 counts time from the P2 date).

Legacy DMRPTS applies the BR-05 same-class rule, so D0000009 (10 points, no notice) and D0000013 (5 points) match.

## Layout (all under `modern/`)
| Path | What | Port |
|---|---|---|
| `licence-service/` | Driver Licensing context (BR-01 to BR-03) | 8081 |
| `demerit-service/` | Demerits & Offences context (BR-04, BR-05) | 8082 |
| `registration-service/` | Vehicle Registration context (BR-06) | 8083 |
| `notification-service/` | Notifications plus the activity feed of all domain events | 8084 |
| `ui/` | LICSYS Next web app (React, Vite, TypeScript) | 5173 |
| `infra/` | AWS CDK app (TypeScript) describing the production deployment | n/a |
| `tools/loader/` | Strangler loader that reads legacy `data/*.dat` into the services | n/a |
| `tools/parity/` | Runs the COBOL batch and the new system on the same data and compares the results | n/a |
| `localstack/init-aws.sh` | Creates the AWS resources below in LocalStack | 4566 |
| `docker-compose.yml` | LocalStack plus all four services and the UI | |

## Conventions
- **Services:** Java 17, Spring Boot 3.3.x and Maven. Each service is its own Maven project with its own `pom.xml`, `mvn verify` runs the tests, and each has a `Dockerfile` that uses a multi-stage build on `eclipse-temurin:17`. Use AWS SDK for Java v2.
- **AWS access:** the endpoint comes from env `AWS_ENDPOINT_URL` (default `http://localhost:4566`). Region is `ap-southeast-2`, with credentials `test`/`test`.
- **Persistence:** each service owns its own DynamoDB table and never reads another service's table.
- **JSON shapes:**
  - Dates are ISO `YYYY-MM-DD`.
  - Money is a decimal string with 2 places, e.g. `"389.15"`.
  - Driver IDs keep their legacy form, e.g. `D0000009`.
- **Rule references:** every decision a service makes carries a `rule` (e.g. `"BR-05"`) and a plain-English `reason`, so the UI can show "Why?".
- **Endpoints every service exposes:**
  - `GET /actuator/health`;
  - `POST /api/admin/reset`, which wipes the service's table and is for demo resets;
  - CORS allowing `http://localhost:5173`.
- **Testing:** unit tests cover every rule, including the edge cases in `tests/cases/`, e.g. exactly 365 and 730 days, suspended days and invalid input.

## AWS resources (created by `localstack/init-aws.sh` and mirrored in `infra/`)
- **Event bus:** EventBridge bus `licsys-bus`. Every event has `source` `licsys.<service>` (e.g. `licsys.demerit`), `detail-type` set to the event name and `detail` as below.
- **DynamoDB tables:** `licence-drivers` (pk `driverId`), `demerit-ledger` (pk `driverId`), `registration-renewals` (pk `renewalId`) and `notification-activity` (pk `eventId`).
- **SQS queues, each fed by an EventBridge rule on `licsys-bus`:**

  | Queue | Receives |
  |---|---|
  | `licence-inbox` | `DemeritPointsChanged` |
  | `demerit-inbox` | `DriverRegistered`, `LicenceProgressed` |
  | `notification-inbox` | `SuspensionThresholdReached`, `LicenceProgressed` |
  | `activity-inbox` | all events from `licsys-bus` |

## Domain events (`detail` payloads)
```
DriverRegistered            {driverId, name, licenceClass, classIssuedDate, p1IssuedDate, suspendedDays, demeritPoints}   source licsys.licence
LicenceProgressed           {driverId, name, fromClass, toClass, effectiveDate, tenureDays, rule, reason}                 source licsys.licence
OffenceRecorded             {driverId, offenceId, date, code, offenceClass, basePoints, appliedPoints, doubled, rule, reason} source licsys.demerit
DemeritPointsChanged        {driverId, points, limit, licenceClass}                                                       source licsys.demerit
SuspensionThresholdReached  {driverId, name, points, limit, licenceClass, rule, reason}                                   source licsys.demerit
RegistrationRenewed         {renewalId, plate, vehicleType, term, total, rule}                                            source licsys.registration
NoticeIssued                {noticeId, driverId, type: SUSPENSION|UPGRADE, text, issuedAt}                               source licsys.notification
```
Classes are `LN`, `P1`, `P2` and `OP`. Offence classes eligible for double points are `SP`, `MP`, `SB` and `HL`, as defined in `copybooks/OFFREC.cpy`.

## REST APIs
**licence-service :8081**
- `POST /api/drivers`: register or import a driver from a body shaped like `DriverRegistered`. Publishes `DriverRegistered`.
- `GET /api/drivers` and `GET /api/drivers/{id}`: return `{driverId, name, dob, licenceClass, classIssuedDate, p1IssuedDate, suspendedDays, demeritPoints, history:[{fromClass,toClass,effectiveDate,rule,reason}]}`. `demeritPoints` is a projection kept up to date from `DemeritPointsChanged`.
- `GET /api/drivers/{id}/progression?asOf=YYYY-MM-DD`: a dry run returning `{eligible, toClass, tenureDays, requiredDays, rule, reason}`.
- `POST /api/progression/run?runDate=YYYY-MM-DD`: the sweep that replaces the LICUPG batch. It progresses every eligible driver, publishes `LicenceProgressed` and returns `{runDate, read, upgraded:[...]}`. In AWS, EventBridge Scheduler calls this nightly.

**demerit-service :8082**
- `POST /api/offences`: body `{driverId, date, code, offenceClass, basePoints, alreadyPosted?:bool}`.
  - It applies BR-05 against the driver's offence history (`alreadyPosted=true` loads history without adding points, which is how legacy `P` records are imported), then checks BR-04.
  - It publishes `OffenceRecorded`, `DemeritPointsChanged` and, if the driver is at or over their limit, `SuspensionThresholdReached`.
  - It returns the `OffenceRecorded` payload plus `{totalPoints, limit, suspensionNotice}`.
- `GET /api/drivers/{id}/demerits`: returns `{driverId, licenceClass, points, limit, offences:[OffenceRecorded...], suspensionNotice}`.

**registration-service :8083**
- `POST /api/renewals/quote`: body `{plate, vehicleType: CAR|MOTORCYCLE|TRAILER, cylinders, concession: bool, termMonths}`. Returns either `{total, lines:[{label, amount, rule}]}` or `400 {rule, reason}`. Uses `BigDecimal` with HALF_UP rounding at each step, exactly as in BR-06.
- `POST /api/renewals`: prices and stores a renewal, then publishes `RegistrationRenewed`. `GET /api/renewals` lists them.

**notification-service :8084**
- Consumes `notification-inbox` and issues notices, which it publishes as `NoticeIssued`.
  - Suspension notices go only to drivers who don't already have an open one.
  - Upgrade notices go out on `LicenceProgressed`.
- `GET /api/notices?driverId=` lists notices.
- Consumes `activity-inbox` and stores every event.
  - `GET /api/activity?limit=100` returns the newest events first, as `[{eventId, time, source, detailType, detail}]`.
  - `GET /api/activity/stream` is an SSE stream of the same events.
