#!/usr/bin/env python3
"""BR-06 parity: legacy COBOL REGFEE vs registration-service.

  parity.py generate --count N --seed S OUT        write N random VEHICLES.dat records (valid and invalid)
  parity.py compare --name NAME --vehicles F --report R --url URL [--diffs OUT]
        price every record of F through POST {URL}/api/renewals/quote and diff it with REGFEE's report R
        (registration fee, traffic improvement fee, admin fee, total and priced/rejected status).

Legacy record -> API mapping (same as licsys.registration.legacy.LegacyVehicleRecord):
  cols 1-6 plate (trimmed), 7-14 owner (unused), 15-16 type CR/MC/TR -> CAR/MOTORCYCLE/TRAILER (other codes passed
  through trimmed, so BR-06 rule 6 rejects them), 17-18 cylinders (two digits else null), 19-20 termMonths (two digits
  else null), 21 concession ('Y' only). Lines are padded/truncated to 21 bytes like a COBOL READ INTO.

Documented policy-vs-COBOL differences (counted, not failures): a car whose cylinder field is 00 or not two digits.
REGFEE prices it (00 in the 1-3 band, garbage in an arbitrary band); BR-06 has no band for it, so the service rejects.
"""
import argparse
import json
import random
import re
import sys
import urllib.error
import urllib.request
from decimal import Decimal

TYPES = {"CR": "CAR", "MC": "MOTORCYCLE", "TR": "TRAILER"}
CENT = Decimal("0.01")


def read_records(path):
    with open(path, "rb") as f:
        data = f.read().decode("latin-1")
    lines = data.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [l.rstrip("\r") for l in lines]


def parse(line):
    rec = line.ljust(21)[:21]
    return {"plate": rec[0:6], "owner": rec[6:14], "type": rec[14:16], "cyl": rec[16:18], "term": rec[18:20],
            "conc": rec[20]}


def two_digits(s):
    return int(s) if re.fullmatch(r"[0-9]{2}", s) else None


def to_request(rec):
    return {"plate": rec["plate"].strip(), "vehicleType": TYPES.get(rec["type"], rec["type"].strip()),
            "cylinders": two_digits(rec["cyl"]), "concession": rec["conc"] == "Y",
            "termMonths": two_digits(rec["term"])}


def documented_difference(rec):
    if rec["type"] != "CR" or re.fullmatch(r"0[1-9]|[1-9][0-9]", rec["cyl"]):
        return None
    return "D1 car with 00 cylinders" if rec["cyl"] == "00" else "D2 car with non-numeric cylinders"


def money(s):
    return Decimal(s.replace(",", "")).quantize(CENT)


def parse_report(path):
    lines = read_records(path)
    details, trailer = lines[1:-1], lines[-1]
    out = []
    for l in details:
        if "*** REJECTED" in l:
            out.append(None)
        else:
            reg, tif, admin, total = (money(t) for t in l.split()[-4:])
            out.append((reg, tif, admin, total))
    m = re.search(r"PRICED\s*:\s*(\d+)\s+REJECTED\s*:\s*(\d+)\s+TOTAL DUE\s*:\s*([\d,]+\.\d\d)", trailer)
    return out, (int(m.group(1)), int(m.group(2)), money(m.group(3)))


def quote(url, body):
    req = urllib.request.Request(url + "/api/renewals/quote", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            q = json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 400:
            return None, json.load(e)
        raise
    amounts = [Decimal(l["amount"]) for l in q["lines"]]
    reg = sum(amounts[0:3], Decimal("0.00"))
    return (reg, amounts[3], amounts[4], Decimal(q["total"])), q


def fmt(v):
    return "REJECTED" if v is None else "reg %s tif %s admin %s total %s" % v


def compare(args):
    records = [parse(l) for l in read_records(args.vehicles)]
    cobol, (c_ok, c_rej, c_total) = parse_report(args.report)
    if len(cobol) != len(records):
        sys.exit("%s: %d records but %d report lines" % (args.name, len(records), len(cobol)))
    match, documented, unexpected = 0, [], []
    s_ok = s_rej = 0
    s_total = Decimal("0.00")
    documented_cobol_total = Decimal("0.00")
    for i, (rec, legacy) in enumerate(zip(records, cobol), 1):
        modern, raw = quote(args.url, to_request(rec))
        if modern is None:
            s_rej += 1
        else:
            s_ok += 1
            s_total += modern[3]
        line = "%s #%d [%s] COBOL %s | service %s" % (args.name, i, "".join(rec.values()), fmt(legacy),
                                                       fmt(modern) if modern else "400 " + json.dumps(raw))
        if legacy == modern:
            match += 1
        elif modern is None and documented_difference(rec):
            documented.append(documented_difference(rec) + ": " + line)
            documented_cobol_total += legacy[3]
        else:
            unexpected.append(line)
    if sum(1 for c in cobol if c) != c_ok or sum(c[3] for c in cobol if c) != c_total:
        unexpected.append("%s: COBOL trailer does not reconcile with its detail lines" % args.name)
    if c_total - documented_cobol_total != s_total:
        unexpected.append("%s: grand totals do not reconcile: COBOL %s - documented %s != service %s"
                          % (args.name, c_total, documented_cobol_total, s_total))
    if args.diffs:
        with open(args.diffs, "w") as f:
            f.write("\n".join(["# documented"] + documented + ["# unexpected"] + unexpected) + "\n")
    for d in documented:
        print("  documented  " + d)
    for u in unexpected:
        print("  UNEXPECTED  " + u)
    print("RESULT %s records=%d match=%d documented=%d unexpected=%d | COBOL priced=%d rejected=%d total=%s"
          " | service priced=%d rejected=%d total=%s"
          % (args.name, len(records), match, len(documented), len(unexpected), c_ok, c_rej, c_total, s_ok, s_rej,
             s_total))
    return 1 if unexpected else 0


def generate(args):
    rnd = random.Random(args.seed)
    pick = lambda opts: rnd.choices([o for o, _ in opts], [w for _, w in opts])[0]
    out = []
    for i in range(1, args.count + 1):
        plate = "R%05d" % i
        owner = "O%07d" % rnd.randint(1, 9999999)
        vtype = pick([("CR", 50), ("MC", 15), ("TR", 15), ("BU", 3), ("XX", 2), ("cr", 2), ("mc", 1), ("  ", 2),
                      ("C ", 1), ("TK", 1)])
        cyl = pick([("%02d" % rnd.randint(1, 16), 70), ("00", 6), ("99", 3), ("%02d" % rnd.randint(17, 98), 3),
                    ("  ", 3), ("AB", 2), (" 4", 2), ("4 ", 2), ("-4", 1), ("0X", 1)])
        term = pick([("12", 30), ("06", 25), ("03", 25), ("00", 2), ("01", 2), ("09", 2), ("18", 2), ("24", 2),
                     ("99", 1), ("  ", 2), (" 6", 2), ("3 ", 2), ("AB", 1)])
        conc = pick([("N", 55), ("Y", 35), ("X", 3), (" ", 3), ("y", 2), ("n", 2)])
        rec = plate + owner + vtype + cyl + term + conc
        shape = pick([("ok", 92), ("short", 4), ("long", 2), ("blank", 2)])
        if shape == "short":
            rec = rec[:rnd.randint(6, 20)]
        elif shape == "long":
            rec = rec + "EXTRA"
        elif shape == "blank":
            rec = ""
        out.append(rec)
    with open(args.out, "w", encoding="latin-1") as f:
        f.write("\n".join(out) + "\n")
    print("generated %d records (seed %d) -> %s" % (len(out), args.seed, args.out))
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--count", type=int, default=250)
    g.add_argument("--seed", type=int, default=2026)
    g.add_argument("out")
    c = sub.add_parser("compare")
    c.add_argument("--name", required=True)
    c.add_argument("--vehicles", required=True)
    c.add_argument("--report", required=True)
    c.add_argument("--url", required=True)
    c.add_argument("--diffs")
    args = p.parse_args()
    return generate(args) if args.cmd == "generate" else compare(args)


if __name__ == "__main__":
    sys.exit(main())
