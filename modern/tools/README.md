# LICSYS Next tools (fictional demo system)

Python 3, stdlib only.

| Tool | Purpose |
|---|---|
| `loader/loader.py` | Strangler loader: parses `DRVMAST.dat`, `OFFENCES.dat`, `VEHICLES.dat` using the layouts in `copybooks/*.cpy`, POSTs drivers, waits for demerit-service to know each driver, then POSTs offences (status `P` => `alreadyPosted=true`, sorted by driver/date). |
| `parity/parity.py` | Runs the COBOL batch on a temp copy of `--case <dir>/data`, resets the services, loads the same data, runs progression for `RUNDATE.dat` and compares each driver/vehicle. Writes `--out` (default `modern/ui/public/parity.json`), exits 1 on any `MISMATCH`. |
| `testing/fake_services.py` | Policy-correct in-memory stand-in for the four services (ports 8081-8084, async event delivery) used to test the loader and parity before the real services exist. |
| `tests/` | `python3 -m unittest discover -s modern/tools/tests` (parsers, classification, end to end against the stub). |

```bash
python3 modern/tools/testing/fake_services.py &          # or ./modern/demo.sh up
python3 modern/tools/loader/loader.py --data-dir data --base-urls licence=http://localhost:8081
python3 modern/tools/parity/parity.py --case . --out /tmp/parity.json
```

`--base-urls` takes `licence=URL,demerit=URL,registration=URL,notification=URL`; omitted services default to the contract ports on localhost.
`parity/examples/parity.stub.json` is the output of the stub run on the shipped sample.
