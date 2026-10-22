# LICSYS regression tests

Golden-file tests for the nightly batch (`DMRPTS`, `LICUPG`, `REGFEE`).

    tests/run_tests.sh              # build, run every case, diff against goldens
    tests/run_tests.sh 05-regfee-fees   # run one case
    tests/run_tests.sh --update     # re-record goldens from the current code

Needs bash and GnuCOBOL 3.x only. Goldens were recorded with cobc 3.1.2.

## How it works

For each `tests/cases/<case>/`:

1. `data/` holds the four batch inputs (`DRVMAST.dat`, `OFFENCES.dat`, `RUNDATE.dat`, `VEHICLES.dat`).
2. The harness copies them into a scratch directory with `run_nightly.sh` and the built `bin/`, then runs the batch exactly as ops would.
3. Every file written to `out/` is compared with `expected/out/` (missing or extra files also fail), and the console output plus exit status with `expected/console.log`.

**The goldens record what the code does today, not what `policy/BUSINESS-RULES.md` says it should do.** Several goldens deliberately lock in behaviour that looks wrong (see "Suspicious behaviour" in the PR that added this harness). If you fix one of those, the diff is expected: check it is exactly the change you intended, then re-record with `--update`.

## Cases

| Case | Covers |
|------|--------|
| `01-shipped-sample` | Frozen copy of the sample data in `data/`. |
| `02-dmrpts-demerits` | Double demerits: gap exactly 365 vs 366 days, same class, different eligible class (SP then MP), ineligible prior (GN then SP), ineligible current offence, two new offences in one run, same-day repeat, HL class, prior offences of a different driver, already-posted (`P`) offences not re-posted. Thresholds: OP at 11/12, P1/P2/LN at 3/4, over limit with no offences tonight. |
| `03-licupg-progression` | P1: exactly 365 / 364 days, suspended days tipping either side of 365, 3 vs 4 points, points pushed to 4 by tonight's posting, issue date in the future. P2: held exactly 730 days, held 400 days but P1 date 730 days ago, P1 date 729 days ago, suspended days either side of 730, 3 vs 4 points, suspended days exceeding tenure. LN and OP untouched. |
| `04-licupg-leap-year` | 365 / 730 / 729 / 364 day boundaries spanning 29 Feb 2024. |
| `05-regfee-fees` | Every car cylinder band (0, 1, 3, 4, 5, 6, 7, 8, 12), motorcycle, trailer; 12 / 6 / 3 month terms; concession `Y` on car, motorcycle and trailer; concession flag `X` and blank; rejects for unknown type (`BU`, `XX`, lowercase `cr`) and bad terms (0, 1, 9, 18, 24). |
| `06-invalid-records` | Unknown / lowercase licence class, zero issue dates, demerit total overflowing `PIC 9(2)`, doubled points overflowing `PIC 9(2)`, offence status other than `N`/`P`, offences out of date order, duplicate master records, offences for a driver with no master record, blank / short / non-numeric vehicle records, trailer with cylinders. |
| `07-empty-inputs` | All input files present but empty. |

## Adding a case

Create `tests/cases/<nn-name>/data/` with all four input files, run `tests/run_tests.sh --update <nn-name>`, review every file under `expected/` by hand, and commit both.
