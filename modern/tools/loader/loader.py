#!/usr/bin/env python3
"""LICSYS strangler loader (fictional demo system).

Reads the legacy fixed-width files in --data-dir (layouts from copybooks/*.cpy)
and imports them into LICSYS Next through the public service APIs:

  1. POST every driver in DRVMAST.dat to licence-service /api/drivers.
  2. Wait until demerit-service knows each driver (DriverRegistered has flowed
     through licsys-bus -> demerit-inbox): GET /api/drivers/{id}/demerits != 404.
  3. POST OFFENCES.dat to demerit-service /api/offences sorted by driver/date.
     Status P (already posted by the legacy batch) => alreadyPosted=true.
  4. Wait until licence-service's demeritPoints projection has caught up
     (DemeritPointsChanged via licence-inbox), so a progression run sees the
     same points the legacy LICUPG step would.

Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import copybook  # noqa: E402

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
DEFAULT_COPYBOOKS = REPO_ROOT / "copybooks"
DEFAULT_BASE_URLS = {
    "licence": "http://localhost:8081",
    "demerit": "http://localhost:8082",
    "registration": "http://localhost:8083",
    "notification": "http://localhost:8084",
}
LICENCE_CLASSES = {"LN", "P1", "P2", "OP"}
VEHICLE_TYPES = {"CR": "CAR", "MC": "MOTORCYCLE", "TR": "TRAILER"}


# ---------------------------------------------------------------- parsing

class Rejected:
    def __init__(self, file: str, key: str, reason: str):
        self.file, self.key, self.reason = file, key, reason

    def __repr__(self) -> str:
        return f"{self.file} {self.key!r}: {self.reason}"


def _layouts(copybooks: pathlib.Path) -> Dict[str, copybook.Layout]:
    return {n: copybook.load_layout(copybooks / f"{n}.cpy") for n in ("DRVMAST", "OFFREC", "VEHREC")}


def parse_drivers(data_dir: pathlib.Path, copybooks: pathlib.Path = DEFAULT_COPYBOOKS) -> Tuple[List[Dict[str, Any]], List[Rejected]]:
    """DRVMAST.dat -> DriverRegistered-shaped bodies (+ dob)."""
    layout = _layouts(copybooks)["DRVMAST"]
    drivers: List[Dict[str, Any]] = []
    rejected: List[Rejected] = []
    seen = set()
    for r in copybook.read_records(data_dir / "DRVMAST.dat", layout):
        driver_id = r["DRV-ID"].strip()
        cls = r["DRV-LIC-CLASS"]
        if not driver_id:
            rejected.append(Rejected("DRVMAST", driver_id, "blank driver id"))
            continue
        if driver_id in seen:
            rejected.append(Rejected("DRVMAST", driver_id, "duplicate master record (first one kept)"))
            continue
        if cls not in LICENCE_CLASSES:
            rejected.append(Rejected("DRVMAST", driver_id, f"unknown licence class {cls!r} (BR-01)"))
            continue
        class_date = copybook.iso_date(r["DRV-CLASS-DATE"])
        if class_date is None:
            rejected.append(Rejected("DRVMAST", driver_id, f"invalid class issue date {r['DRV-CLASS-DATE']!r}"))
            continue
        points = copybook.num(r["DRV-DMR-PTS"])
        susp = copybook.num(r["DRV-SUSP-DAYS"])
        if points is None or susp is None:
            rejected.append(Rejected("DRVMAST", driver_id, "non-numeric points or suspended days"))
            continue
        seen.add(driver_id)
        drivers.append({
            "driverId": driver_id,
            "name": r["DRV-NAME"].strip(),
            "dob": copybook.iso_date(r["DRV-DOB"]),
            "licenceClass": cls,
            "classIssuedDate": class_date,
            "p1IssuedDate": copybook.iso_date(r["DRV-P1-DATE"]),
            "suspendedDays": susp,
            "demeritPoints": points,
        })
    return drivers, rejected


def parse_offences(data_dir: pathlib.Path, copybooks: pathlib.Path = DEFAULT_COPYBOOKS,
                   known_drivers: Optional[set] = None) -> Tuple[List[Dict[str, Any]], List[Rejected]]:
    """OFFENCES.dat -> /api/offences bodies, sorted by driver then date (stable)."""
    layout = _layouts(copybooks)["OFFREC"]
    offences: List[Dict[str, Any]] = []
    rejected: List[Rejected] = []
    for n, r in enumerate(copybook.read_records(data_dir / "OFFENCES.dat", layout), start=1):
        driver_id = r["OFF-DRV-ID"].strip()
        key = f"{driver_id}#{n}"
        date = copybook.iso_date(r["OFF-DATE"])
        base = copybook.num(r["OFF-BASE-PTS"])
        status = r["OFF-STATUS"]
        if date is None:
            rejected.append(Rejected("OFFENCES", key, f"invalid offence date {r['OFF-DATE']!r}"))
            continue
        if base is None:
            rejected.append(Rejected("OFFENCES", key, "non-numeric base points"))
            continue
        if status not in ("N", "P"):
            rejected.append(Rejected("OFFENCES", key, f"unknown status {status!r} (expected N or P)"))
            continue
        if known_drivers is not None and driver_id not in known_drivers:
            rejected.append(Rejected("OFFENCES", key, "no master record for driver"))
            continue
        offences.append({
            "driverId": driver_id,
            "date": date,
            "code": r["OFF-CODE"].strip(),
            "offenceClass": r["OFF-CLASS"].strip(),
            "basePoints": base,
            "alreadyPosted": status == "P",
        })
    offences.sort(key=lambda o: (o["driverId"], o["date"]))
    return offences, rejected


def parse_vehicles(data_dir: pathlib.Path, copybooks: pathlib.Path = DEFAULT_COPYBOOKS) -> List[Dict[str, Any]]:
    """VEHICLES.dat -> /api/renewals/quote bodies. Unknown types are passed through
    unchanged so registration-service gets to reject them (BR-06.6)."""
    layout = _layouts(copybooks)["VEHREC"]
    out = []
    for r in copybook.read_records(data_dir / "VEHICLES.dat", layout):
        raw_type = r["VEH-TYPE"]
        cyl = copybook.num(r["VEH-CYL"])
        term = copybook.num(r["VEH-TERM"])
        out.append({
            "plate": r["VEH-PLATE"].strip(),
            "ownerId": r["VEH-OWNER-ID"].strip(),
            "vehicleType": VEHICLE_TYPES.get(raw_type, raw_type.strip() or "UNKNOWN"),
            "cylinders": cyl if cyl is not None else 0,
            "concession": r["VEH-CONCESSION"] == "Y",
            "termMonths": term if term is not None else 0,
        })
    return out


# ---------------------------------------------------------------- HTTP

def parse_base_urls(spec: Optional[str]) -> Dict[str, str]:
    """'licence=http://h:8081,demerit=...' (service names may carry a -service suffix)."""
    urls = dict(DEFAULT_BASE_URLS)
    if spec:
        for part in spec.split(","):
            if not part.strip():
                continue
            name, _, url = part.partition("=")
            name = name.strip().removesuffix("-service")
            if name not in urls or not url:
                raise ValueError(f"bad --base-urls entry {part!r}; expected licence|demerit|registration|notification=URL")
            urls[name] = url.strip().rstrip("/")
    return urls


def http(method: str, url: str, body: Any = None, timeout: float = 10.0, retries: int = 5) -> Tuple[int, Any]:
    """JSON request; retries connection errors and 5xx with backoff. Returns (status, parsed body)."""
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    delay = 0.2
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, _decode(resp.read())
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == retries:
                return e.code, _decode(e.read())
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == retries:
                raise
        time.sleep(delay)
        delay = min(delay * 2, 3.0)
    raise AssertionError("unreachable")


def _decode(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return raw.decode(errors="replace")


def wait_until(predicate, timeout: float, interval: float = 0.25) -> bool:
    deadline = time.monotonic() + timeout
    while True:
        if predicate():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(interval)


# ---------------------------------------------------------------- load

class LoadError(RuntimeError):
    pass


def load(data_dir: pathlib.Path, base_urls: Dict[str, str], copybooks: pathlib.Path = DEFAULT_COPYBOOKS,
         wait_timeout: float = 60.0, renewals: bool = False, log=print) -> Dict[str, Any]:
    drivers, rejected = parse_drivers(data_dir, copybooks)
    offences, off_rejected = parse_offences(data_dir, copybooks, {d["driverId"] for d in drivers})
    rejected += off_rejected
    for r in rejected:
        log(f"  skip {r}")

    lic, dmr = base_urls["licence"], base_urls["demerit"]
    log(f"drivers: posting {len(drivers)} to {lic}/api/drivers")
    for d in drivers:
        status, body = http("POST", f"{lic}/api/drivers", d)
        if status >= 300:
            raise LoadError(f"POST /api/drivers {d['driverId']} -> {status} {body}")

    log(f"drivers: waiting for demerit-service to know all {len(drivers)}")
    for d in drivers:
        url = f"{dmr}/api/drivers/{d['driverId']}/demerits"
        if not wait_until(lambda: http("GET", url)[0] == 200, wait_timeout):
            raise LoadError(f"demerit-service never saw DriverRegistered for {d['driverId']} ({wait_timeout}s)")

    log(f"offences: posting {len(offences)} to {dmr}/api/offences "
        f"({sum(o['alreadyPosted'] for o in offences)} already posted)")
    for o in offences:
        status, body = http("POST", f"{dmr}/api/offences", o)
        if status == 404:  # projection lag on a single driver: retry until known
            ok = wait_until(lambda: http("POST", f"{dmr}/api/offences", o)[0] < 300, wait_timeout)
            if not ok:
                raise LoadError(f"POST /api/offences {o['driverId']} {o['date']} kept returning 404")
        elif status >= 300:
            raise LoadError(f"POST /api/offences {o['driverId']} {o['date']} -> {status} {body}")

    log("projection: waiting for licence-service demeritPoints to match demerit-service")
    for d in drivers:
        did = d["driverId"]

        def caught_up() -> bool:
            s1, dm = http("GET", f"{dmr}/api/drivers/{did}/demerits")
            s2, dr = http("GET", f"{lic}/api/drivers/{did}")
            return s1 == 200 and s2 == 200 and dm.get("points") == dr.get("demeritPoints")
        if not wait_until(caught_up, wait_timeout):
            raise LoadError(f"licence-service demeritPoints for {did} never matched demerit-service ({wait_timeout}s)")

    stored = 0
    if renewals:
        reg = base_urls["registration"]
        for v in parse_vehicles(data_dir, copybooks):
            status, _ = http("POST", f"{reg}/api/renewals", _quote_body(v))
            stored += status < 300
        log(f"renewals: stored {stored}")

    summary = {"drivers": len(drivers), "offences": len(offences), "renewals": stored,
               "rejected": [repr(r) for r in rejected]}
    log(f"loaded {len(drivers)} drivers, {len(offences)} offences, skipped {len(rejected)} records")
    return summary


def _quote_body(v: Dict[str, Any]) -> Dict[str, Any]:
    return {k: v[k] for k in ("plate", "vehicleType", "cylinders", "concession", "termMonths")}


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", type=pathlib.Path, default=REPO_ROOT / "data", help="dir with DRVMAST.dat, OFFENCES.dat, VEHICLES.dat")
    ap.add_argument("--base-urls", help="licence=URL,demerit=URL,registration=URL,notification=URL (defaults: localhost:8081-8084)")
    ap.add_argument("--copybooks", type=pathlib.Path, default=DEFAULT_COPYBOOKS)
    ap.add_argument("--wait-timeout", type=float, default=60.0, help="seconds to wait for each eventual-consistency step")
    ap.add_argument("--renewals", action="store_true", help="also POST VEHICLES.dat to /api/renewals")
    ap.add_argument("--dry-run", action="store_true", help="parse and print what would be sent, no HTTP")
    args = ap.parse_args(argv)

    if args.dry_run:
        drivers, rej = parse_drivers(args.data_dir, args.copybooks)
        offences, rej2 = parse_offences(args.data_dir, args.copybooks, {d["driverId"] for d in drivers})
        print(json.dumps({"drivers": drivers, "offences": offences,
                          "vehicles": parse_vehicles(args.data_dir, args.copybooks),
                          "rejected": [repr(r) for r in rej + rej2]}, indent=2))
        return 0
    try:
        load(args.data_dir, parse_base_urls(args.base_urls), args.copybooks, args.wait_timeout, args.renewals)
    except (LoadError, OSError, ValueError) as e:
        print(f"loader: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
