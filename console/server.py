#!/usr/bin/env python3
"""LICSYS operator console: a green-screen enquiry front end over the COBOL nightly batch.

Reads the same flat files the batch uses (data/ and out/). No dependencies beyond Python 3.
    python3 console/server.py [port]
"""
import json
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
PAGE = Path(__file__).resolve().parent / "index.html"
CLASS_NAMES = {"LN": "LEARNER", "P1": "PROVISIONAL P1", "P2": "PROVISIONAL P2", "OP": "OPEN"}
LIMITS = {"LN": 4, "P1": 4, "P2": 4, "OP": 12}


def parse_master(path):
    drivers = {}
    if not path.exists():
        return drivers
    for line in path.read_text().splitlines():
        if len(line) < 60:
            continue
        drivers[line[0:8]] = {
            "id": line[0:8],
            "name": line[8:28].strip(),
            "dob": line[28:36],
            "cls": line[36:38],
            "class_date": line[38:46],
            "p1_date": line[46:54],
            "points": int(line[54:56]),
            "susp_days": int(line[56:60]),
        }
    return drivers


def parse_offences():
    offences = []
    for line in (ROOT / "data/OFFENCES.dat").read_text().splitlines():
        if len(line) < 25:
            continue
        offences.append({
            "drv": line[0:8],
            "date": line[8:16],
            "code": line[16:20],
            "cls": line[20:22],
            "base": int(line[22:24]),
            "status": line[24:25],
        })
    return offences


def parse_report(name, key_cols):
    path = ROOT / "out" / name
    rows = {}
    if not path.exists():
        return rows
    for line in path.read_text().splitlines()[1:]:
        parts = line.split()
        if parts:
            rows.setdefault(parts[0], []).append(parts)
    return rows


def driver_view(drv_id):
    before = parse_master(ROOT / "data/DRVMAST.dat").get(drv_id)
    if not before:
        return None
    after = parse_master(ROOT / "out/DRVMAST.new").get(drv_id)
    postings = {(p[1], p[2]): p for p in parse_report("POSTINGS.rpt", 0).get(drv_id, [])}
    notice = drv_id in parse_report("NOTICES.rpt", 0)
    upgrades = parse_report("UPGRADES.rpt", 0).get(drv_id, [])
    offences = []
    for o in parse_offences():
        if o["drv"] != drv_id:
            continue
        p = postings.get((o["date"], o["code"]))
        o["applied"] = int(p[5]) if p else None
        o["double"] = (p[6] == "YES") if p else None
        offences.append(o)
    cur = after or before
    return {
        "before": before,
        "after": after,
        "class_name": CLASS_NAMES.get(cur["cls"], cur["cls"]),
        "limit": LIMITS.get(cur["cls"]),
        "offences": offences,
        "notice": notice,
        "upgrade": {"from": upgrades[0][-4], "to": upgrades[0][-3]} if upgrades else None,
        "batch_run": after is not None,
    }


def run_nightly():
    out = subprocess.run(
        "./build.sh && ./run_nightly.sh", shell=True, cwd=ROOT,
        capture_output=True, text=True,
    )
    return {"ok": out.returncode == 0, "log": (out.stdout + out.stderr).strip()}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.path = urlsplit(self.path).path
        if self.path in ("/", "/index.html"):
            return self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
        if self.path == "/api/drivers":
            master = parse_master(ROOT / "data/DRVMAST.dat")
            return self._send(200, [{"id": d["id"], "name": d["name"], "cls": d["cls"]} for d in master.values()])
        if self.path.startswith("/api/driver/"):
            view = driver_view(self.path.rsplit("/", 1)[-1].upper())
            return self._send(200 if view else 404, view or {"error": "DRIVER NOT ON FILE"})
        if self.path == "/api/rundate":
            return self._send(200, {"rundate": (ROOT / "data/RUNDATE.dat").read_text().strip()})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/api/run-nightly":
            return self._send(200, run_nightly())
        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"LICSYS console on http://localhost:{port}")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
