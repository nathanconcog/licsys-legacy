# registration-service

*Fictional demo system. Not real government code.*

LICSYS Next **Vehicle Registration** bounded context (port 8083). It replaces the legacy `REGFEE` batch step
(`src/REGFEE.cbl`, `copybooks/VEHREC.cpy`) and implements **BR-06 Registration renewal fees** from
`policy/BUSINESS-RULES.md`, following the contract in `modern/ARCHITECTURE.md`.

Java 17, Spring Boot 3.3, Maven, AWS SDK for Java v2 (DynamoDB, EventBridge).

## Run

```bash
# whole stack on LocalStack (from modern/)
docker compose up --build localstack registration-service

# or locally against a running LocalStack (AWS_ENDPOINT_URL defaults to http://localhost:4566)
./mvnw spring-boot:run
```

| Env | Default |
|---|---|
| `AWS_ENDPOINT_URL` | `http://localhost:4566` |
| `AWS_REGION` | `ap-southeast-2` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` |

If Maven Central rate-limits your build host, pass a mirror to the Docker build:
`docker compose build --build-arg MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2 registration-service`
(locally: `MVNW_REPOURL=<mirror>` plus a `~/.m2/settings.xml` mirror).

## API

| Method | Path | What |
|---|---|---|
| `POST` | `/api/renewals/quote` | Price a renewal. Body `{plate, vehicleType: CAR\|MOTORCYCLE\|TRAILER, cylinders, concession, termMonths}`. Returns `{total, lines:[{label, amount, rule}], rule, reason}` or `400 {rule, reason}`. Nothing is stored. |
| `POST` | `/api/renewals` | Price, store in DynamoDB `registration-renewals` (pk `renewalId`) and publish `RegistrationRenewed`. `201` with the stored renewal. |
| `GET` | `/api/renewals` | All stored renewals, newest first. |
| `POST` | `/api/admin/reset` | Delete every item in `registration-renewals` (demo reset). |
| `GET` | `/actuator/health` | Health. |

CORS allows `http://localhost:5173` (the UI).

Stored renewal / `POST /api/renewals` response:
`{renewalId, plate, vehicleType, cylinders, concession, termMonths, total, lines, rule, reason, renewedAt}`.

Event on bus `licsys-bus`: `source` `licsys.registration`, `detail-type` `RegistrationRenewed`,
`detail` `{renewalId, plate, vehicleType, term, total, rule}` (`term` is the term in months, `total` a 2dp string).

### Quote lines

Every quote has five lines, all with `rule` `"BR-06"`; the label names the clause. The lines always add up to `total`.

| Line | Amount |
|---|---|
| Base fee (fee table) | annual base fee for the vehicle |
| Concession (rule 1) | `round(base x 0.5) - base`, or `0.00` (none requested, or a trailer) |
| Term factor (rule 2) | `round(concessionBase x factor) - concessionBase` (factor 1.00 / 0.52 / 0.27) |
| Traffic improvement fee (rule 3) | `round(26.35 x factor)` |
| Administration fee (rule 4) | `8.40` for 3 and 6 month terms, else `0.00` |

All arithmetic is `BigDecimal` with `HALF_UP` to the cent at each step (rule 5), the same order as `REGFEE`'s
`COMPUTE ... ROUNDED` statements, so `base + concession + term factor` equals REGFEE's `REG FEE` column.

### Rejections (`400 {rule, reason}`)

| Input | rule |
|---|---|
| `vehicleType` not exactly `CAR`, `MOTORCYCLE` or `TRAILER` (rule 6) | `BR-06` |
| `termMonths` not 12, 6 or 3, or missing (rule 2) | `BR-06` |
| `CAR` with `cylinders` missing or `0` (no fee-table band) | `BR-06` |
| negative `cylinders`; malformed JSON / wrong types; blank `plate` on `POST /api/renewals` | `INPUT` |

`cylinders` is ignored for motorcycles and trailers. A missing `concession` means no concession.

## Legacy `VEHICLES.dat` mapping

`licsys.registration.legacy.LegacyVehicleRecord` (and the same logic in `parity/parity.py`) maps one 21-byte
`VEHREC` line to a quote request. Short lines are space-padded and long lines truncated to 21 bytes, as a COBOL
line-sequential `READ ... INTO` does.

| Cols | COBOL field | API field |
|---|---|---|
| 1-6 | `VEH-PLATE X(6)` | `plate` (trimmed) |
| 7-14 | `VEH-OWNER-ID X(8)` | not part of the BR-06 API (dropped) |
| 15-16 | `VEH-TYPE X(2)` | `vehicleType`: `CR`=`CAR`, `MC`=`MOTORCYCLE`, `TR`=`TRAILER`; any other code (e.g. `BU`, lowercase `cr`, blank) is sent through trimmed so the service rejects it under rule 6 |
| 17-18 | `VEH-CYL 9(2)` | `cylinders`: the number if both bytes are digits, else `null` |
| 19-20 | `VEH-TERM 9(2)` | `termMonths`: the number if both bytes are digits, else `null` (rejected) |
| 21 | `VEH-CONCESSION X(1)` | `concession`: `true` only for `Y` (REGFEE treats `N`, `X`, blank and lowercase `y` as no concession) |

## Policy vs COBOL

Places where `REGFEE` accepts input the policy does not price. The service implements the policy:

- **D1 - car with 0 cylinders**: REGFEE's `WHEN VEH-CYL <= 3` prices it at $312.40. BR-06's table starts at 1
  cylinder, so the service rejects it (`tests/cases/05-regfee-fees` record `CR0012`).
- **D2 - car with a non-numeric cylinder field** (`AB`, blanks, ` 4`, `-4`, ...): GnuCOBOL compares the garbage
  `PIC 9(2)` field numerically and prices it in an arbitrary band (often 7+ at $780.55). The service rejects it.

Accepted by both, flagged for the policy owner:

- Concession flags other than `Y`/`N` (`X`, blank, `y`) silently mean "no concession"; BR-06 does not say how to
  validate the flag. The mapping keeps REGFEE's behaviour.
- Trailer and motorcycle cylinder counts are ignored (e.g. `TRCY99`, a trailer with 99 cylinders, is priced).

## Tests

```bash
./mvnw verify
```

- `FeeCalculatorTest`: every BR-06 rule - each car cylinder band boundary (1, 3, 4, 5, 6, 7, 8, 12, 99), 0 and
  missing cylinders, motorcycle and trailer, 12/6/3 month terms, every other term rejected, concession incl.
  trailer + concession, half-up at each step (a case where rounding once would differ), unknown types, lines sum to
  total.
- `LegacyVehicleRecordTest`: the mapping, incl. blank, short, long, non-numeric and lowercase records.
- `LegacyGoldenParityTest`: prices `tests/cases/{01,05,06,07}` in-process and compares every line with the COBOL
  golden `expected/out/REGFEES.rpt` (skipped if the repo's `tests/` folder is not reachable).
- `RenewalControllerTest`, `EventBridgePublisherTest`, `RegistrationServiceApplicationTest`: HTTP shapes, 400s,
  store + publish, CORS, health.

## Parity with COBOL REGFEE

```bash
modern/registration-service/parity.sh            # service must be running on :8083 (REGISTRATION_URL to override)
modern/registration-service/parity.sh --count 1000 --seed 7
```

Builds `REGFEE` with GnuCOBOL into `target/parity/`, runs it on `data/VEHICLES.dat`,
`tests/cases/05-regfee-fees`, `tests/cases/06-invalid-records` and a generated set (default 250 records, seed 2026,
about a third invalid: unknown/lowercase/blank types, bad cylinders, bad terms, odd concession flags, short, long
and blank lines), prices every record through `POST /api/renewals/quote`, and diffs registration fee, traffic
improvement fee, admin fee, total and priced/rejected status per record. It also reconciles the grand totals
(COBOL total - documented differences = service total). Exits 1 on any difference that is not D1 or D2.
Per-dataset reports and diffs are kept in `target/parity/<dataset>/`.
