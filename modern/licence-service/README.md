# licence-service (LICSYS Next)

*Fictional demo system, not real government code.*

Driver Licensing bounded context, port **8081**. Implements BR-01 to BR-03 from
[`policy/BUSINESS-RULES.md`](../../policy/BUSINESS-RULES.md) and replaces the legacy `LICUPG` batch.
The contract is [`modern/ARCHITECTURE.md`](../ARCHITECTURE.md).

- **BR-01** classes `LN`, `P1`, `P2`, `OP`. Only P1 and P2 are progressed.
- **BR-02** P1 to P2: tenure of at least 365 days and fewer than 4 demerit points.
- **BR-03** P2 to OP: tenure of at least 730 days and fewer than 4 demerit points.
- Tenure is the number of days since `classIssuedDate` minus `suspendedDays`. Time on P1 does **not** count
  towards BR-03; legacy `LICUPG` counts from the P1 date, and this service deliberately does not.
- On progression `classIssuedDate` becomes the run date, `suspendedDays` resets to 0 and a history entry
  `{fromClass, toClass, effectiveDate, rule, reason}` is appended.
- `demeritPoints` is a projection updated from `DemeritPointsChanged` events on SQS `licence-inbox`.

## Run

Needs Java 17. Maven is provided by the wrapper (`./mvnw`).

```bash
# LocalStack with the LICSYS resources (from modern/)
docker compose up -d localstack
# the service, against LocalStack on localhost:4566
./mvnw spring-boot:run            # or: ./mvnw package && java -jar target/licence-service-0.1.0.jar
```

Or the whole stack: `docker compose up --build` from `modern/`.

| Env | Default |
|---|---|
| `AWS_ENDPOINT_URL` | `http://localhost:4566` |
| `AWS_REGION` | `ap-southeast-2` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` |

Set `LICSYS_AWS_ENABLED=false` to run without AWS (in-memory store, events only logged, no SQS consumer).

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/drivers` | Body shaped like `DriverRegistered` (plus optional `dob`). 201 new, 200 re-import. Publishes `DriverRegistered`. |
| `GET` | `/api/drivers`, `/api/drivers/{id}` | Driver with `history`. |
| `GET` | `/api/drivers/{id}/progression?asOf=YYYY-MM-DD` | Dry run: `{eligible, toClass, tenureDays, requiredDays, rule, reason}`. |
| `POST` | `/api/progression/run?runDate=YYYY-MM-DD` | Sweep: `{runDate, read, upgraded:[LicenceProgressed...]}`. Publishes `LicenceProgressed`. |
| `POST` | `/api/admin/reset` | Wipes `licence-drivers`. |
| `GET` | `/actuator/health` | |

Bad input returns `400 {rule, reason}` (`rule` is `BR-01` for an unknown class, otherwise `INPUT`);
an unknown driver returns `404 {rule: "NOT_FOUND", reason}`. CORS allows `http://localhost:5173`.

## Test

```bash
./mvnw verify
```

- `ProgressionPolicyTest`: every rule, exactly 365 / 364 and 730 / 729 days, suspended days either side of the
  boundary, suspended days exceeding tenure, issue date in the future, 3 vs 4+ points, leap-year spans,
  LN/OP untouched, P1 time not counting towards BR-03, reset on progression.
- `LicenceApiTest`: endpoints, event payloads, validation errors, projection updates, CORS, health
  (in-memory, no AWS needed).
- `LegacyFixtureTest`: runs `tests/cases/03-licupg-progression`, `04-licupg-leap-year` and `01-shipped-sample`
  through the policy (input `expected/out/DRVMAST.posted`, golden `expected/out/DRVMAST.new`) and asserts that
  the only differences from the COBOL goldens are P2 drivers that legacy LICUPG upgraded using the P1 date:

  | Case | Drivers that stay P2 under BR-03 (COBOL upgrades them) |
  |---|---|
  | `03-licupg-progression` | U0000008, U0000010, U0000013 |
  | `04-licupg-leap-year` | L0000003 |
  | `01-shipped-sample` | D0000004, D0000005, D0000012 |

  It is skipped if `../../tests/cases` is not present (e.g. inside the Docker build).

## End-to-end demo

With LocalStack (container `licsys-localstack`) and the service running:

```bash
demo/e2e.sh
```

It resets the table, registers the 13 drivers in `data/DRVMAST.dat` via `demo/register-legacy.py`, puts the
`DemeritPointsChanged` events that demerit-service would publish for tonight's postings on `licsys-bus`,
runs `POST /api/progression/run?runDate=2026-10-20` and drains `activity-inbox`. Expected: Alice P1 to P2,
Chloe and Grace P2 to OP, Henry stays P1 (5 points), Daniel, Emma and Liam stay P2.
