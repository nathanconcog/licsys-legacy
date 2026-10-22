#!/usr/bin/env python3
"""LICSYS parity harness (fictional demo system).

Runs the legacy COBOL nightly batch and LICSYS Next on the same input data and
compares the results:

  legacy:  ./build.sh, then run_nightly.sh in a temp copy with <case>/data
  modern:  POST /api/admin/reset on every service, load <case>/data with the
           strangler loader, POST /api/progression/run?runDate=<RUNDATE.dat>

Per driver it compares final licence class, demerit points and suspension
notice; per vehicle the renewal total from /api/renewals/quote. Each driver is
MATCH, EXPECTED_DIFFERENCE (a known COBOL bug listed in modern/ARCHITECTURE.md,
detected by re-deriving both the buggy and the policy result from the inputs)
or MISMATCH. Exits 1 on any MISMATCH or renewal mismatch.

Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Dict, List, Optional, Tuple

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / "loader"))
import copybook  # noqa: E402
import loader  # noqa: E402

DEFAULT_OUT = REPO_ROOT / "modern" / "ui" / "public" / "parity.json"
LIMITS = {"LN": 4, "P1": 4, "P2": 4, "OP": 12}
DOUBLE_CLASSES = {"SP", "MP", "SB", "HL"}


# ---------------------------------------------------------------- legacy side

def run_legacy(case_dir: pathlib.Path, repo_root: pathlib.Path = REPO_ROOT, keep: bool = False) -> pathlib.Path:
    """Builds the COBOL batch and runs run_nightly.sh against case_dir/data in a temp copy. Returns its out/ dir."""
    if not shutil.which("cobc"):
        raise RuntimeError("cobc not found: install GnuCOBOL 3.x (sudo apt-get install -y gnucobol) or pass --legacy-out")
    subprocess.run([str(repo_root / "build.sh")], check=True, stdout=subprocess.DEVNULL)
    work = pathlib.Path(tempfile.mkdtemp(prefix="licsys-parity-"))
    shutil.copy2(repo_root / "run_nightly.sh", work / "run_nightly.sh")
    shutil.copytree(repo_root / "bin", work / "bin")
    shutil.copytree(case_dir / "data", work / "data")
    proc = subprocess.run(["./run_nightly.sh"], cwd=work, capture_output=True, text=True, env=dict(os.environ, LC_ALL="C"))
    (work / "console.log").write_text(proc.stdout + proc.stderr + f"exit status: {proc.returncode}\n")
    if proc.returncode != 0:
        raise RuntimeError(f"legacy batch failed (exit {proc.returncode}); see {work}/console.log")
    if not keep:
        import atexit
        atexit.register(shutil.rmtree, work, True)
    return work / "out"


def read_legacy(out_dir: pathlib.Path, copybooks: pathlib.Path = loader.DEFAULT_COPYBOOKS) -> Tuple[Dict[str, Dict[str, Any]], Dict[str, List[Optional[str]]]]:
    """Parses DRVMAST.new, NOTICES.rpt and REGFEES.rpt. Returns (drivers by id, renewal totals by plate in file order)."""
    layout = copybook.load_layout(copybooks / "DRVMAST.cpy")
    drivers: Dict[str, Dict[str, Any]] = {}
    for r in copybook.read_records(out_dir / "DRVMAST.new", layout):
        did = r["DRV-ID"].strip()
        if did and did not in drivers:
            drivers[did] = {"name": r["DRV-NAME"].strip(), "licenceClass": r["DRV-LIC-CLASS"],
                            "points": copybook.num(r["DRV-DMR-PTS"]), "suspensionNotice": False}
    notices = out_dir / "NOTICES.rpt"
    if notices.exists():
        for line in notices.read_text().splitlines()[1:]:
            did = line[:8].strip()
            if did in drivers:
                drivers[did]["suspensionNotice"] = True
    fees: Dict[str, List[Optional[str]]] = {}
    rpt = out_dir / "REGFEES.rpt"
    if rpt.exists():
        for line in rpt.read_text().splitlines()[1:]:
            if line.startswith("RENEWALS PRICED") or not line.strip():
                continue
            plate = line[:6].strip()
            total = None if "REJECTED" in line else line.split()[-1].replace(",", "")
            fees.setdefault(plate, []).append(total)
    return drivers, fees


# ---------------------------------------------------------------- modern side

def run_modern(case_dir: pathlib.Path, urls: Dict[str, str], run_date: str, wait_timeout: float = 60.0, log=print) -> Dict[str, Any]:
    for name, base in urls.items():
        status, body = loader.http("POST", f"{base}/api/admin/reset")
        if status >= 300:
            raise RuntimeError(f"reset {name} -> {status} {body}")
    summary = loader.load(case_dir / "data", urls, wait_timeout=wait_timeout, log=lambda m: log(f"  loader: {m}"))
    status, run = loader.http("POST", f"{urls['licence']}/api/progression/run?runDate={run_date}", timeout=60)
    if status >= 300:
        raise RuntimeError(f"progression run -> {status} {run}")
    log(f"  progression run {run_date}: read {run.get('read')}, upgraded {len(run.get('upgraded', []))}")

    drivers: Dict[str, Dict[str, Any]] = {}
    status, listing = loader.http("GET", f"{urls['licence']}/api/drivers")
    for drv in listing if status == 200 else []:
        did = drv["driverId"]
        # LicenceProgressed reaches demerit-service asynchronously; wait for its class to catch up.
        def settled() -> bool:
            s, dm = loader.http("GET", f"{urls['demerit']}/api/drivers/{did}/demerits")
            if s == 200 and dm.get("licenceClass") == drv["licenceClass"]:
                drivers[did] = {"name": drv.get("name"), "licenceClass": drv["licenceClass"],
                                "points": dm.get("points"), "suspensionNotice": bool(dm.get("suspensionNotice"))}
                return True
            return False
        if not loader.wait_until(settled, wait_timeout):
            drivers[did] = {"name": drv.get("name"), "licenceClass": drv["licenceClass"], "points": None, "suspensionNotice": None}
    return {"drivers": drivers, "loader": summary, "run": run}


def quote_renewals(case_dir: pathlib.Path, urls: Dict[str, str]) -> List[Tuple[Dict[str, Any], Optional[str]]]:
    out = []
    for v in loader.parse_vehicles(case_dir / "data"):
        body = {k: v[k] for k in ("plate", "vehicleType", "cylinders", "concession", "termMonths")}
        status, res = loader.http("POST", f"{urls['registration']}/api/renewals/quote", body)
        out.append((v, res.get("total") if status == 200 and isinstance(res, dict) else None))
    return out


# ---------------------------------------------------------------- expected-difference detectors

def _date(s: str) -> dt.date:
    return dt.date.fromisoformat(s)


def _raw_offences(data_dir: pathlib.Path) -> List[Dict[str, str]]:
    layout = copybook.load_layout(loader.DEFAULT_COPYBOOKS / "OFFREC.cpy")
    return list(copybook.read_records(data_dir / "OFFENCES.dat", layout))


def model_points(start: int, offences: List[Dict[str, Any]], legacy_bug: bool) -> Tuple[int, List[str]]:
    """Points after posting tonight's offences.

    legacy_bug=True mirrors DMRPTS 2200-APPLY-POINTS: doubles an eligible offence if ANY prior offence (any class)
    is within 365 days. Otherwise BR-05: only a prior offence of the SAME class doubles.
    Returns (points, explanations of offences the two rules treat differently)."""
    pts, notes = start, []
    prior: List[Dict[str, Any]] = []
    for o in offences:
        if not o["alreadyPosted"]:
            dbl_any = next((p for p in prior if (_date(o["date"]) - _date(p["date"])).days <= 365), None)
            dbl_same = next((p for p in reversed(prior) if p["offenceClass"] == o["offenceClass"] and 0 <= (_date(o["date"]) - _date(p["date"])).days <= 365), None)
            eligible = o["offenceClass"] in DOUBLE_CLASSES
            doubled = eligible and (dbl_any is not None if legacy_bug else dbl_same is not None)
            pts += o["basePoints"] * (2 if doubled else 1)
            cross = next((p for p in prior if p["offenceClass"] != o["offenceClass"] and 0 <= (_date(o["date"]) - _date(p["date"])).days <= 365), None)
            if legacy_bug and eligible and dbl_same is None and cross is not None:
                dbl_any = cross
                notes.append(f"{o['offenceClass']} {o['code']} on {o['date']} was doubled by COBOL because of a "
                             f"{dbl_any['offenceClass']} offence on {dbl_any['date']}")
        prior.append(o)
    return pts, notes


def model_class(drv: Dict[str, Any], points: int, run_date: dt.date, legacy_bug: bool) -> Tuple[str, Optional[int]]:
    """Class after the progression run. legacy_bug=True mirrors LICUPG counting P2 tenure from DRV-P1-DATE."""
    cls = drv["licenceClass"]
    if cls == "P1":
        tenure = (run_date - _date(drv["classIssuedDate"])).days - drv["suspendedDays"]
        return ("P2" if tenure >= 365 and points < 4 else "P1"), tenure
    if cls == "P2":
        start = drv["p1IssuedDate"] if legacy_bug else drv["classIssuedDate"]
        if start is None:
            return "P2", None
        tenure = (run_date - _date(start)).days - drv["suspendedDays"]
        return ("OP" if tenure >= 730 and points < 4 else "P2"), tenure
    return cls, None


def models(case_dir: pathlib.Path, run_date: dt.date) -> Dict[str, Dict[str, Any]]:
    """Per driver: what the buggy COBOL should produce and what the policy says, derived from the inputs alone."""
    data = case_dir / "data"
    drivers, _ = loader.parse_drivers(data)
    policy_offs, _ = loader.parse_offences(data, known_drivers={d["driverId"] for d in drivers})
    # COBOL processes OFFENCES.dat in file order and keeps any status in the prior list.
    legacy_offs: Dict[str, List[Dict[str, Any]]] = {}
    for r in _raw_offences(data):
        date = copybook.iso_date(r["OFF-DATE"])
        base = copybook.num(r["OFF-BASE-PTS"])
        if date is None or base is None:
            continue
        legacy_offs.setdefault(r["OFF-DRV-ID"].strip(), []).append(
            {"date": date, "code": r["OFF-CODE"].strip(), "offenceClass": r["OFF-CLASS"].strip(), "basePoints": base,
             "alreadyPosted": r["OFF-STATUS"] != "N"})
    out = {}
    for drv in drivers:
        did = drv["driverId"]
        p_pts, _ = model_points(drv["demeritPoints"], [o for o in policy_offs if o["driverId"] == did], legacy_bug=False)
        l_pts, notes = model_points(drv["demeritPoints"], legacy_offs.get(did, []), legacy_bug=True)
        p_cls, p_ten = model_class(drv, p_pts, run_date, legacy_bug=False)
        l_cls, l_ten = model_class(drv, l_pts, run_date, legacy_bug=True)
        out[did] = {
            "driver": drv,
            "policy": {"licenceClass": p_cls, "points": p_pts, "suspensionNotice": p_pts >= LIMITS[drv["licenceClass"]], "tenure": p_ten},
            "legacy": {"licenceClass": l_cls, "points": l_pts, "suspensionNotice": l_pts >= LIMITS[drv["licenceClass"]], "tenure": l_ten},
            # class each bug alone would produce, for attributing a class difference to BR-03 vs BR-05
            "br03Only": model_class(drv, p_pts, run_date, legacy_bug=True)[0],
            "br05Only": model_class(drv, l_pts, run_date, legacy_bug=False)[0],
            "br05Notes": notes,
        }
    return out


FIELDS = ("licenceClass", "points", "suspensionNotice")


def classify(did: str, legacy: Optional[Dict[str, Any]], modern: Optional[Dict[str, Any]], model: Optional[Dict[str, Any]],
             rejected: Optional[str] = None) -> Tuple[str, Optional[str], Optional[str]]:
    """-> (status, rule, reason)."""
    if legacy is None or modern is None:
        side = "legacy batch output" if legacy is None else "LICSYS Next"
        why = f" (loader skipped it: {rejected})" if rejected else ""
        return "MISMATCH", None, f"driver missing from {side}{why}"
    diff = [f for f in FIELDS if legacy.get(f) != modern.get(f)]
    if not diff:
        return "MATCH", None, None
    if model is not None:
        explained = all(legacy.get(f) == model["legacy"][f] and modern.get(f) == model["policy"][f] for f in diff)
        if explained:
            rules, reasons = [], []
            drv, pol, leg = model["driver"], model["policy"], model["legacy"]
            if "licenceClass" in diff and model["br03Only"] != pol["licenceClass"]:
                rules.append("BR-03")
                reasons.append(
                    f"COBOL LICUPG counts P2 tenure from the P1 date {drv['p1IssuedDate']} ({leg['tenure']} days) and upgraded to "
                    f"{legacy['licenceClass']}; BR-03 counts from the P2 issue date {drv['classIssuedDate']} "
                    f"({pol['tenure']} days < 730), so the driver stays {modern['licenceClass']}")
            if model["br05Notes"] and ({"points", "suspensionNotice"} & set(diff) or ("licenceClass" in diff and model["br05Only"] != pol["licenceClass"])):
                rules.append("BR-05")
                notice = ""
                if "suspensionNotice" in diff:
                    notice = (f"; that pushes legacy over the {LIMITS[drv['licenceClass']]}-point limit and issues a suspension notice"
                              if legacy["suspensionNotice"] else "; policy issues a suspension notice")
                reasons.append(f"{'; '.join(model['br05Notes'])}. BR-05 only doubles a repeat "
                               f"offence of the same class: legacy {legacy['points']} points, policy {modern['points']}{notice}")
            covered = all(("BR-05" in rules) if f != "licenceClass" else bool(rules) for f in diff)
            if rules and covered:
                return "EXPECTED_DIFFERENCE", ", ".join(rules), ". ".join(reasons)
    return "MISMATCH", None, "; ".join(f"{f}: legacy {legacy.get(f)!r} vs modern {modern.get(f)!r}" for f in diff)


# ---------------------------------------------------------------- report

def build_report(case_dir: pathlib.Path, legacy_out: pathlib.Path, modern: Dict[str, Any],
                 renewals: List[Tuple[Dict[str, Any], Optional[str]]], run_date: str) -> Dict[str, Any]:
    legacy_drivers, legacy_fees = read_legacy(legacy_out)
    mdl = models(case_dir, _date(run_date))
    _, rejected = loader.parse_drivers(case_dir / "data")
    rej = {r.key: r.reason for r in rejected}
    ids = list(legacy_drivers) + [d for d in modern["drivers"] if d not in legacy_drivers]
    rows = []
    for did in ids:
        lg, md = legacy_drivers.get(did), modern["drivers"].get(did)
        status, rule, reason = classify(did, lg, md, mdl.get(did), rej.get(did))
        pick = lambda r: {f: r.get(f) for f in FIELDS} if r else None  # noqa: E731
        rows.append({"driverId": did, "name": (lg or md or {}).get("name"), "legacy": pick(lg), "modern": pick(md),
                     "status": status, "rule": rule, "reason": reason})
    ren_rows = []
    seen: Dict[str, int] = {}
    for v, modern_total in renewals:
        n = seen.get(v["plate"], 0)
        seen[v["plate"]] = n + 1
        lt = legacy_fees.get(v["plate"], [])
        legacy_total = lt[n] if n < len(lt) else None
        ren_rows.append({"plate": v["plate"], "legacyTotal": legacy_total, "modernTotal": modern_total, "match": legacy_total == modern_total})
    count = lambda s: sum(r["status"] == s for r in rows)  # noqa: E731
    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "summary": {"drivers": len(rows), "matched": count("MATCH"), "expectedDifferences": count("EXPECTED_DIFFERENCE"),
                    "unexpected": count("MISMATCH"), "renewals": len(ren_rows), "renewalMatched": sum(r["match"] for r in ren_rows)},
        "drivers": rows,
        "renewals": ren_rows,
    }


def render_table(report: Dict[str, Any]) -> str:
    def cell(r, side):
        v = r[side]
        if not v:
            return "-"
        return f"{v['licenceClass']} {v['points'] if v['points'] is not None else '?':>2}pt {'NOTICE' if v['suspensionNotice'] else '':6}"
    out = [f"{'DRIVER':9} {'NAME':20} {'LEGACY':16} {'MODERN':16} {'STATUS':20} RULE"]
    for r in report["drivers"]:
        out.append(f"{r['driverId']:9} {(r['name'] or '')[:20]:20} {cell(r, 'legacy'):16} {cell(r, 'modern'):16} {r['status']:20} {r['rule'] or ''}")
        if r["status"] != "MATCH" and r["reason"]:
            out.append(f"{'':10}-> {r['reason']}")
    out.append("")
    out.append(f"{'PLATE':7} {'LEGACY':>10} {'MODERN':>10}  MATCH")
    for r in report["renewals"]:
        out.append(f"{r['plate']:7} {r['legacyTotal'] or 'REJECTED':>10} {r['modernTotal'] or 'REJECTED':>10}  {'yes' if r['match'] else 'NO'}")
    s = report["summary"]
    out.append("")
    out.append(f"drivers {s['drivers']}: {s['matched']} match, {s['expectedDifferences']} expected differences, {s['unexpected']} unexpected; "
               f"renewals {s['renewalMatched']}/{s['renewals']} match")
    return "\n".join(out)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--case", type=pathlib.Path, required=True, help="directory containing data/ (e.g. . or tests/cases/01-shipped-sample)")
    ap.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT, help=f"JSON report path (default {DEFAULT_OUT.relative_to(REPO_ROOT)})")
    ap.add_argument("--base-urls", help="licence=URL,demerit=URL,registration=URL,notification=URL (defaults: localhost:8081-8084)")
    ap.add_argument("--legacy-out", type=pathlib.Path, help="use an existing legacy out/ dir instead of running the COBOL batch")
    ap.add_argument("--keep-temp", action="store_true", help="keep the temp dir the COBOL batch ran in")
    ap.add_argument("--wait-timeout", type=float, default=60.0)
    args = ap.parse_args(argv)

    case = args.case.resolve()
    if not (case / "data" / "RUNDATE.dat").exists():
        print(f"parity: {case}/data/RUNDATE.dat not found", file=sys.stderr)
        return 2
    run_date = copybook.iso_date((case / "data" / "RUNDATE.dat").read_text()[:8])
    if run_date is None:
        print("parity: RUNDATE.dat is not a valid YYYYMMDD date", file=sys.stderr)
        return 2
    try:
        urls = loader.parse_base_urls(args.base_urls)
        print(f"legacy: {'using ' + str(args.legacy_out) if args.legacy_out else 'building and running COBOL batch'}")
        legacy_out = args.legacy_out.resolve() if args.legacy_out else run_legacy(case, keep=args.keep_temp)
        print(f"modern: reset, load and run at {urls['licence']}")
        modern = run_modern(case, urls, run_date, args.wait_timeout)
        renewals = quote_renewals(case, urls)
    except (RuntimeError, loader.LoadError, OSError, subprocess.CalledProcessError, ValueError) as e:
        print(f"parity: {e}", file=sys.stderr)
        return 2
    report = build_report(case, legacy_out, modern, renewals, run_date)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print()
    print(render_table(report))
    print(f"\nwrote {args.out}")
    bad = report["summary"]["unexpected"] or report["summary"]["renewalMatched"] != report["summary"]["renewals"]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
