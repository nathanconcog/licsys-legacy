# demerit-service (LICSYS Next)

*Fictional demo system. Not real government code.*

The Demerits & Offences bounded context, on port **8082**. It replaces the legacy `DMRPTS` batch
(`src/DMRPTS.cbl`) and implements the **policy** in `policy/BUSINESS-RULES.md`:

- **BR-05 double demerits.** An offence is doubled only when it is in class `SP`, `MP`, `SB` or `HL` **and** the
  driver has a previous offence **of the same class** dated 0 to 365 days before it. `DMRPTS` doubles after a prior
  offence of *any* class. That is the known legacy bug, and this service does not reproduce it.
- **BR-04 limits.** LN, P1 and P2 have a limit of 4 points and OP has 12. A driver at or over their limit gets
  `SuspensionThresholdReached`.

Every decision carries a `rule` and a plain-English `reason`, for example
`Not doubled: previous offence 10/01/2026 was SP, not MP (BR-05)`, `Doubled: previous SP offence on 01/12/2025 was
257 days earlier, within 365 days (BR-05)` or `5 points is at or over the P1 limit of 4: suspension notice (BR-04)`.

Contract: `modern/ARCHITECTURE.md`.

## Run

    mvn verify                                   # unit, web and legacy-fixture tests
    cd .. && docker compose up -d localstack     # creates licsys-bus, demerit-ledger and the queues
    mvn spring-boot:run                          # or: docker compose up --build demerit-service
    ./scripts/e2e-localstack.sh                  # end-to-end check against LocalStack

Configuration comes from env `AWS_ENDPOINT_URL` (default `http://localhost:4566`) and `AWS_REGION`
(default `ap-southeast-2`). Credentials default to `test`/`test`. Set `LICSYS_INBOX_ENABLED=false` to run
without the SQS consumer.

If Maven Central rate-limits you, build the image with a mirror:
`docker build --build-arg MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2 .`

## API

| Method | Path | |
|---|---|---|
| `POST` | `/api/offences` | Body: `{driverId, date, code, offenceClass, basePoints, alreadyPosted?}`. Returns the `OffenceRecorded` payload plus `{totalPoints, limit, suspensionNotice}`, and also `alreadyPosted`, `licenceClass`, `pendingRegistration` and `threshold:{rule, reason}`. The status is `201`, or `202` if the driver isn't registered yet (see below). Invalid input gets `400 {rule:"VALIDATION", field, reason}`. |
| `GET` | `/api/drivers/{id}/demerits` | Returns `{driverId, name, licenceClass, points, limit, offences:[...], suspensionNotice, pendingRegistration, rule, reason}`, or `404 {rule, reason}`. |
| `POST` | `/api/admin/reset` | Wipes `demerit-ledger`. |
| `GET` | `/actuator/health` | Health check. |

CORS allows `http://localhost:5173`.

Input validation:
- `driverId` must be a letter followed by 7 digits.
- `date` must be a real ISO date.
- `code` is 1 to 4 characters, using `A-Z` and `0-9`.
- `offenceClass` is 2 letters, `A-Z`.
- `basePoints` is a whole number from 1 to 99, matching the legacy `PIC 99`.

**Imports (`alreadyPosted=true`).** This is how legacy `P` records are loaded. The offence is stored as history,
so it counts as a prior offence for BR-05, but it has `appliedPoints: 0`. Its points are already in the starting
balance from `DriverRegistered.demeritPoints`. BR-04 is still checked and the events are still published, as the
contract says.

**No PIC 99 wrap.** Points are plain `int`s with overflow-checked arithmetic. A base of 50 doubles to 100 (the
COBOL gave 0), and a total of 95 + 6 is 101.

## Events

- **Publishes** to `licsys-bus` with source `licsys.demerit`: `OffenceRecorded`, `DemeritPointsChanged` and
  `SuspensionThresholdReached`. The payloads are exactly as in the contract.
- **Consumes** SQS `demerit-inbox`:
  - `DriverRegistered` gives the licence class, name and starting `demeritPoints`.
  - `LicenceProgressed` gives the new class and limit.
  - Bad messages (invalid JSON, unknown class) are logged and deleted. Messages that fail for other reasons go back
    to the queue and are retried.

### Event ordering

- **Offence before `DriverRegistered`.** The offence is accepted and BR-05 is applied, because it only needs this
  service's own history. The response is `202` with `pendingRegistration: true` and `limit: null`, and only
  `OffenceRecorded` is published. When `DriverRegistered` arrives, the held points are added to the starting
  balance, then BR-04 runs and `DemeritPointsChanged` (plus `SuspensionThresholdReached` if needed) is published.
  This is safe because licence-service's `demeritPoints` only changes through our `DemeritPointsChanged`, which is
  not sent for a pending driver.
- **`DriverRegistered` again for a known driver (replay or re-import).** The name and class are updated. The
  starting balance is taken again only if this service hasn't posted any offences yet. Otherwise the event's
  `demeritPoints` already includes our postings, and taking it again would double count.
- **`LicenceProgressed` before `DriverRegistered`.** The newer class wins. Classes are compared by
  `effectiveDate` against `classIssuedDate`.
- **A driver registered already at or over their limit.** `SuspensionThresholdReached` is published when the
  driver is registered. This matches DMRPTS, which lists such drivers in `NOTICES.rpt` even with no new offences.
- **Out-of-order offence dates.** Only offences dated on or before the new one count as previous offences.
  Points that were already applied are never recalculated.

## Storage

DynamoDB table `demerit-ledger`, with partition key `driverId`. There is one item per driver containing:
- `name`, `licenceClass`, `classEffectiveDate`, `registered`;
- `startingPoints`, `points`, `limit`;
- `offences`, a JSON list;
- `version` and `updatedAt`.

Writes are serialised per driver in the process and use a conditional `version` check, retried on conflict.
Events are published after the write succeeds. Production would want an outbox (DynamoDB Streams) so that a
failed publish can't be lost. Here it is logged and the request fails with 500.

## Tests

- **`DoubleDemeritPolicyTest` (BR-05).** Covers:
  - a gap of exactly 365 days (doubled) and of 366 days (not doubled);
  - the leap-day window;
  - a prior offence of a different eligible class;
  - an ineligible prior offence;
  - a same-class prior offence with other classes in between;
  - a same-day repeat;
  - a prior offence dated later than the new one;
  - each eligible class, and non-eligible classes;
  - doubling past 99.
- **`DemeritLimitPolicyTest` (BR-04).** Each class at limit - 1, at the limit and over it.
- **`DemeritServiceTest`.** Covers:
  - the Isla, Jack, Henry and Maya scenarios;
  - imports;
  - no wrap at 99;
  - event ordering;
  - re-registration;
  - progression;
  - optimistic-lock retry;
  - reset;
  - 15 invalid-input cases.
- **`LegacyFixtureTest`.** Replays `tests/cases/02-dmrpts-demerits` and `01-shipped-sample` against the COBOL
  goldens (`POSTINGS.rpt`, `DRVMAST.posted` and `NOTICES.rpt`), both with drivers registered first and with
  offences first. DMRPTS applies the BR-05 same-class rule, so the service must match the goldens exactly
  (e.g. case 01: D0000009 on 10 points with no notice, D0000013 on 5).
- **`DemeritControllerTest`.** Covers the REST shapes and status codes, validation 400s, 404, reset, CORS and health.
- **`DemeritInboxListenerTest`.** Checks the EventBridge-to-SQS envelope parsing and that bad messages are discarded.
