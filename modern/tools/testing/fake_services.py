#!/usr/bin/env python3
"""Fake LICSYS Next services for testing the loader and parity harness.

One process serving all four contract APIs on their own ports (default
8081-8084), each with its own in-memory store and an in-process "licsys-bus"
that delivers events to the other contexts asynchronously (after
--event-delay seconds), like EventBridge -> SQS would. Implements the
*policy* (policy/BUSINESS-RULES.md), not the COBOL behaviour.

Not a replacement for the real Spring Boot services; for tests only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import queue
import re
import sys
import threading
import time
import uuid
from decimal import ROUND_HALF_UP, Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse

LIMITS = {"LN": 4, "P1": 4, "P2": 4, "OP": 12}
DOUBLE_CLASSES = {"SP", "MP", "SB", "HL"}
CENT = Decimal("0.01")
TERM_FACTOR = {12: Decimal("1.00"), 6: Decimal("0.52"), 3: Decimal("0.27")}
TIF = Decimal("26.35")
ADMIN_FEE = Decimal("8.40")


def d(s: str) -> dt.date:
    return dt.date.fromisoformat(s)


def money(v: Decimal) -> str:
    return str(v.quantize(CENT, ROUND_HALF_UP))


class Bus:
    """In-process licsys-bus. Subscribers get events on a worker thread after `delay`."""

    def __init__(self, delay: float = 0.0):
        self.delay = delay
        self.subs: List[Tuple[Callable[[str, str], bool], Callable[[Dict[str, Any]], None]]] = []
        self.q: "queue.Queue[Tuple[float, Dict[str, Any]]]" = queue.Queue()
        self.pending = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._run, daemon=True).start()

    def subscribe(self, match: Callable[[str, str], bool], handler: Callable[[Dict[str, Any]], None]) -> None:
        self.subs.append((match, handler))

    def publish(self, source: str, detail_type: str, detail: Dict[str, Any]) -> None:
        ev = {"eventId": str(uuid.uuid4()), "time": dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds"),
              "source": source, "detailType": detail_type, "detail": detail}
        with self.lock:
            self.pending += 1
        self.q.put((time.monotonic() + self.delay, ev))

    def _run(self) -> None:
        while True:
            due, ev = self.q.get()
            wait = due - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            for match, handler in self.subs:
                if match(ev["source"], ev["detailType"]):
                    try:
                        handler(ev)
                    except Exception as e:  # a real consumer would retry then DLQ
                        print(f"fake-bus: handler error {e!r}", file=sys.stderr)
            with self.lock:
                self.pending -= 1


class HttpError(Exception):
    def __init__(self, status: int, body: Any):
        self.status, self.body = status, body


Route = Tuple[str, "re.Pattern[str]", Callable[..., Any]]


class Service:
    name = "service"

    def __init__(self, bus: Bus):
        self.bus = bus
        self.lock = threading.RLock()
        self.routes: List[Route] = []
        self.route("GET", r"/actuator/health", lambda q, b: {"status": "UP"})
        self.route("POST", r"/api/admin/reset", self._reset)
        self.reset()

    def route(self, method: str, pattern: str, fn: Callable[..., Any]) -> None:
        self.routes.append((method, re.compile(f"^{pattern}$"), fn))

    def _reset(self, q, b):
        with self.lock:
            self.reset()
        return {"reset": True, "service": self.name}

    def reset(self) -> None:
        raise NotImplementedError

    def handle(self, method: str, path: str, query: Dict[str, str], body: Any) -> Tuple[int, Any]:
        for m, pat, fn in self.routes:
            match = pat.match(path)
            if m == method and match:
                try:
                    with self.lock:
                        result = fn(query, body, *match.groups())
                    return (201 if method == "POST" and isinstance(result, dict) and result.pop("_created", False) else 200), result
                except HttpError as e:
                    return e.status, e.body
        return 404, {"error": f"no route {method} {path}"}


class LicenceService(Service):
    name = "licence-service"
    source = "licsys.licence"

    def __init__(self, bus: Bus):
        super().__init__(bus)
        self.route("POST", r"/api/drivers", self.register)
        self.route("GET", r"/api/drivers", lambda q, b: [self.drivers[k] for k in sorted(self.drivers)])
        self.route("GET", r"/api/drivers/([A-Z0-9]+)", lambda q, b, i: self._get(i))
        self.route("GET", r"/api/drivers/([A-Z0-9]+)/progression", self.dry_run)
        self.route("POST", r"/api/progression/run", self.run)
        bus.subscribe(lambda s, t: t == "DemeritPointsChanged", self.on_points)

    def reset(self) -> None:
        self.drivers: Dict[str, Dict[str, Any]] = {}

    def _get(self, driver_id: str) -> Dict[str, Any]:
        if driver_id not in self.drivers:
            raise HttpError(404, {"error": f"unknown driver {driver_id}"})
        return self.drivers[driver_id]

    def register(self, q, b):
        required = ["driverId", "name", "licenceClass", "classIssuedDate", "suspendedDays", "demeritPoints"]
        missing = [k for k in required if b.get(k) is None]
        if missing or b["licenceClass"] not in LIMITS:
            raise HttpError(400, {"rule": "BR-01", "reason": f"invalid driver: missing {missing}" if missing else f"unknown licence class {b['licenceClass']}"})
        drv = {k: b.get(k) for k in ["driverId", "name", "dob", "licenceClass", "classIssuedDate", "p1IssuedDate", "suspendedDays", "demeritPoints"]}
        drv["history"] = []
        self.drivers[drv["driverId"]] = drv
        self.bus.publish(self.source, "DriverRegistered", {k: drv[k] for k in required[:4] + ["p1IssuedDate", "suspendedDays", "demeritPoints"]})
        return dict(drv, _created=True)

    def on_points(self, ev):
        det = ev["detail"]
        with self.lock:
            if det["driverId"] in self.drivers:
                self.drivers[det["driverId"]]["demeritPoints"] = det["points"]

    @staticmethod
    def evaluate(drv: Dict[str, Any], as_of: dt.date) -> Dict[str, Any]:
        cls = drv["licenceClass"]
        if cls not in ("P1", "P2"):
            return {"eligible": False, "toClass": None, "tenureDays": None, "requiredDays": None, "rule": "BR-01",
                    "reason": f"{cls} licences do not progress on the nightly run"}
        rule, to, required = ("BR-02", "P2", 365) if cls == "P1" else ("BR-03", "OP", 730)
        tenure = (as_of - d(drv["classIssuedDate"])).days - int(drv["suspendedDays"])
        pts = int(drv["demeritPoints"])
        if tenure < required:
            reason = f"held {cls} for {tenure} days (from {drv['classIssuedDate']}, minus {drv['suspendedDays']} suspended); needs {required}"
            ok = False
        elif pts >= 4:
            reason, ok = f"has {pts} demerit points; must have fewer than 4", False
        else:
            reason, ok = f"held {cls} for {tenure} days (>= {required}) with {pts} points (< 4)", True
        return {"eligible": ok, "toClass": to, "tenureDays": tenure, "requiredDays": required, "rule": rule, "reason": reason}

    def dry_run(self, q, b, driver_id):
        return self.evaluate(self._get(driver_id), d(q.get("asOf") or dt.date.today().isoformat()))

    def run(self, q, b):
        try:
            run_date = d(q["runDate"])
        except (KeyError, ValueError):
            raise HttpError(400, {"rule": "BR-02", "reason": "runDate=YYYY-MM-DD is required"})
        upgraded = []
        for driver_id in sorted(self.drivers):
            drv = self.drivers[driver_id]
            res = self.evaluate(drv, run_date)
            if not res["eligible"]:
                continue
            frm = drv["licenceClass"]
            entry = {"fromClass": frm, "toClass": res["toClass"], "effectiveDate": run_date.isoformat(), "rule": res["rule"], "reason": res["reason"]}
            drv.update(licenceClass=res["toClass"], classIssuedDate=run_date.isoformat(), suspendedDays=0)
            drv["history"].append(entry)
            event = {"driverId": driver_id, "name": drv["name"], "fromClass": frm, "toClass": res["toClass"],
                     "effectiveDate": run_date.isoformat(), "tenureDays": res["tenureDays"], "rule": res["rule"], "reason": res["reason"]}
            self.bus.publish(self.source, "LicenceProgressed", event)
            upgraded.append(event)
        return {"runDate": run_date.isoformat(), "read": len(self.drivers), "upgraded": upgraded}


class DemeritService(Service):
    name = "demerit-service"
    source = "licsys.demerit"

    def __init__(self, bus: Bus):
        super().__init__(bus)
        self.route("POST", r"/api/offences", self.record)
        self.route("GET", r"/api/drivers/([A-Z0-9]+)/demerits", lambda q, b, i: self._view(i))
        bus.subscribe(lambda s, t: t in ("DriverRegistered", "LicenceProgressed"), self.on_driver)

    def reset(self) -> None:
        self.ledger: Dict[str, Dict[str, Any]] = {}

    def on_driver(self, ev):
        det = ev["detail"]
        with self.lock:
            if ev["detailType"] == "DriverRegistered":
                self.ledger[det["driverId"]] = {"driverId": det["driverId"], "name": det["name"], "licenceClass": det["licenceClass"],
                                                "points": int(det["demeritPoints"]), "offences": [], "suspensionNotice": False}
                self._refresh_notice(self.ledger[det["driverId"]])
            elif det["driverId"] in self.ledger:
                self.ledger[det["driverId"]]["licenceClass"] = det["toClass"]
                self._refresh_notice(self.ledger[det["driverId"]])

    @staticmethod
    def _refresh_notice(led: Dict[str, Any]) -> None:
        led["suspensionNotice"] = led["points"] >= LIMITS[led["licenceClass"]]

    def _led(self, driver_id: str) -> Dict[str, Any]:
        if driver_id not in self.ledger:
            raise HttpError(404, {"error": f"unknown driver {driver_id}"})
        return self.ledger[driver_id]

    def _view(self, driver_id: str) -> Dict[str, Any]:
        led = self._led(driver_id)
        return {"driverId": driver_id, "licenceClass": led["licenceClass"], "points": led["points"],
                "limit": LIMITS[led["licenceClass"]], "offences": led["offences"], "suspensionNotice": led["suspensionNotice"]}

    def record(self, q, b):
        led = self._led(b.get("driverId", ""))
        try:
            date, cls, base = d(b["date"]), b["offenceClass"], int(b["basePoints"])
        except (KeyError, ValueError, TypeError):
            raise HttpError(400, {"rule": "BR-05", "reason": "date, offenceClass and basePoints are required"})
        posted = bool(b.get("alreadyPosted"))
        prior = next((o for o in reversed(led["offences"]) if o["offenceClass"] == cls and 0 <= (date - d(o["date"])).days <= 365), None)
        doubled = (not posted) and cls in DOUBLE_CLASSES and prior is not None
        applied = 0 if posted else base * (2 if doubled else 1)
        if posted:
            reason = "imported from legacy as already posted; points already in the master total"
        elif doubled:
            reason = f"{cls} offence within 365 days of a previous {cls} offence on {prior['date']}: double points"
        elif cls in DOUBLE_CLASSES:
            reason = f"no previous {cls} offence in the 365 days before {date}: normal points"
        else:
            reason = f"{cls} offences are never doubled"
        off = {"driverId": led["driverId"], "offenceId": str(uuid.uuid4()), "date": date.isoformat(), "code": b.get("code", ""),
               "offenceClass": cls, "basePoints": base, "appliedPoints": applied, "doubled": doubled, "rule": "BR-05", "reason": reason}
        led["offences"].append(off)
        led["points"] += applied
        was = led["suspensionNotice"]
        self._refresh_notice(led)
        limit = LIMITS[led["licenceClass"]]
        self.bus.publish(self.source, "OffenceRecorded", off)
        self.bus.publish(self.source, "DemeritPointsChanged", {"driverId": led["driverId"], "points": led["points"], "limit": limit, "licenceClass": led["licenceClass"]})
        if led["suspensionNotice"] and not was:
            self.bus.publish(self.source, "SuspensionThresholdReached", {
                "driverId": led["driverId"], "name": led["name"], "points": led["points"], "limit": limit, "licenceClass": led["licenceClass"],
                "rule": "BR-04", "reason": f"{led['points']} points is at or over the {led['licenceClass']} limit of {limit}"})
        return dict(off, totalPoints=led["points"], limit=limit, suspensionNotice=led["suspensionNotice"], _created=True)


class RegistrationService(Service):
    name = "registration-service"
    source = "licsys.registration"
    BASE = {"TRAILER": Decimal("94.60"), "MOTORCYCLE": Decimal("118.25")}

    def __init__(self, bus: Bus):
        super().__init__(bus)
        self.route("POST", r"/api/renewals/quote", lambda q, b: self.quote(b))
        self.route("POST", r"/api/renewals", self.renew)
        self.route("GET", r"/api/renewals", lambda q, b: list(self.renewals.values()))

    def reset(self) -> None:
        self.renewals: Dict[str, Dict[str, Any]] = {}

    @classmethod
    def base_fee(cls, vtype: str, cyl: int) -> Optional[Decimal]:
        if vtype in cls.BASE:
            return cls.BASE[vtype]
        if vtype != "CAR":
            return None
        return Decimal("312.40") if cyl <= 3 else Decimal("389.15") if cyl == 4 else Decimal("572.80") if cyl <= 6 else Decimal("780.55")

    def quote(self, b):
        vtype, term = b.get("vehicleType"), b.get("termMonths")
        base = self.base_fee(vtype, int(b.get("cylinders") or 0))
        if base is None:
            raise HttpError(400, {"rule": "BR-06", "reason": f"unknown vehicle type {vtype!r}"})
        if term not in TERM_FACTOR:
            raise HttpError(400, {"rule": "BR-06", "reason": f"term {term} months is not 12, 6 or 3"})
        f = TERM_FACTOR[term]
        lines = [{"label": "Base fee (annual)", "amount": money(base), "rule": "BR-06"}]
        fee = base
        if b.get("concession") and vtype != "TRAILER":
            fee = (fee * Decimal("0.5")).quantize(CENT, ROUND_HALF_UP)
            lines.append({"label": "Concession 50%", "amount": money(fee), "rule": "BR-06.1"})
        fee = (fee * f).quantize(CENT, ROUND_HALF_UP)
        lines.append({"label": f"Registration fee x {f} ({term} months)", "amount": money(fee), "rule": "BR-06.2"})
        tif = (TIF * f).quantize(CENT, ROUND_HALF_UP)
        lines.append({"label": "Traffic improvement fee", "amount": money(tif), "rule": "BR-06.3"})
        total = fee + tif
        if term < 12:
            lines.append({"label": "Administration fee", "amount": money(ADMIN_FEE), "rule": "BR-06.4"})
            total += ADMIN_FEE
        return {"total": money(total), "lines": lines}

    def renew(self, q, b):
        priced = self.quote(b)
        rid = str(uuid.uuid4())
        rec = {"renewalId": rid, "plate": b.get("plate"), "vehicleType": b.get("vehicleType"), "term": b.get("termMonths"), "total": priced["total"], "rule": "BR-06"}
        self.renewals[rid] = dict(rec, lines=priced["lines"])
        self.bus.publish(self.source, "RegistrationRenewed", rec)
        return dict(rec, _created=True)


class NotificationService(Service):
    name = "notification-service"
    source = "licsys.notification"

    def __init__(self, bus: Bus):
        super().__init__(bus)
        self.route("GET", r"/api/notices", lambda q, b: [n for n in self.notices if not q.get("driverId") or n["driverId"] == q["driverId"]])
        self.route("GET", r"/api/activity", lambda q, b: self.activity[::-1][: int(q.get("limit") or 100)])
        bus.subscribe(lambda s, t: t in ("SuspensionThresholdReached", "LicenceProgressed"), self.on_notify)
        bus.subscribe(lambda s, t: s.startswith("licsys."), self.on_activity)

    def reset(self) -> None:
        self.notices: List[Dict[str, Any]] = []
        self.activity: List[Dict[str, Any]] = []

    def on_notify(self, ev):
        det = ev["detail"]
        with self.lock:
            if ev["detailType"] == "SuspensionThresholdReached":
                if any(n["driverId"] == det["driverId"] and n["type"] == "SUSPENSION" for n in self.notices):
                    return
                ntype, text = "SUSPENSION", f"{det['name']}: {det['reason']}"
            else:
                ntype, text = "UPGRADE", f"{det['name']}: {det['fromClass']} -> {det['toClass']} from {det['effectiveDate']}"
            notice = {"noticeId": str(uuid.uuid4()), "driverId": det["driverId"], "type": ntype, "text": text,
                      "issuedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}
            self.notices.append(notice)
        self.bus.publish(self.source, "NoticeIssued", notice)

    def on_activity(self, ev):
        with self.lock:
            self.activity.append(ev)


def make_handler(svc: Service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # quiet
            pass

        def _send(self, status: int, body: Any) -> None:
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Access-Control-Allow-Origin", "http://localhost:5173")
            self.end_headers()
            self.wfile.write(raw)

        def _dispatch(self, method: str) -> None:
            url = urlparse(self.path)
            query = {k: v[0] for k, v in parse_qs(url.query).items()}
            length = int(self.headers.get("Content-Length") or 0)
            body = None
            if length:
                try:
                    body = json.loads(self.rfile.read(length))
                except ValueError:
                    return self._send(400, {"error": "invalid JSON"})
            self._send(*svc.handle(method, url.path.rstrip("/") or "/", query, body if body is not None else {}))

        def do_GET(self):
            self._dispatch("GET")

        def do_POST(self):
            self._dispatch("POST")

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "http://localhost:5173")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Content-Length", "0")
            self.end_headers()

    return Handler


class FakeLicsys:
    """Starts all four fake services. `ports` = (licence, demerit, registration, notification); 0 = any free port."""

    def __init__(self, ports=(8081, 8082, 8083, 8084), event_delay: float = 0.0, host: str = "127.0.0.1"):
        self.bus = Bus(event_delay)
        self.services = [LicenceService(self.bus), DemeritService(self.bus), RegistrationService(self.bus), NotificationService(self.bus)]
        self.servers = [ThreadingHTTPServer((host, p), make_handler(s)) for s, p in zip(self.services, ports)]
        for srv in self.servers:
            srv.daemon_threads = True
            threading.Thread(target=srv.serve_forever, daemon=True).start()

    @property
    def base_urls(self) -> str:
        names = ["licence", "demerit", "registration", "notification"]
        return ",".join(f"{n}=http://127.0.0.1:{s.server_address[1]}" for n, s in zip(names, self.servers))

    def stop(self) -> None:
        for srv in self.servers:
            srv.shutdown()
            srv.server_close()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--ports", default="8081,8082,8083,8084", help="licence,demerit,registration,notification")
    ap.add_argument("--event-delay", type=float, default=0.3, help="seconds before an event reaches subscribers (simulates EventBridge -> SQS lag)")
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()
    fake = FakeLicsys(tuple(int(p) for p in args.ports.split(",")), args.event_delay, args.host)
    print(f"fake LICSYS Next services up: {fake.base_urls} (event delay {args.event_delay}s)", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        fake.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
