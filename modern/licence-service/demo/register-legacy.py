#!/usr/bin/env python3
"""Registers every driver in a legacy DRVMAST file with licence-service (POST /api/drivers).

usage: demo/register-legacy.py [../../data/DRVMAST.dat] [http://localhost:8081]
"""
import json
import sys
import urllib.request

path = sys.argv[1] if len(sys.argv) > 1 else "../../data/DRVMAST.dat"
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8081"


def iso(ymd):
    return None if ymd == "00000000" else f"{ymd[:4]}-{ymd[4:6]}-{ymd[6:]}"


with open(path) as f:
    for line in f:
        r = line.rstrip("\n").ljust(60)
        if not r.strip():
            continue
        body = {
            "driverId": r[0:8].strip(),
            "name": r[8:28].strip(),
            "dob": iso(r[28:36]),
            "licenceClass": r[36:38],
            "classIssuedDate": iso(r[38:46]),
            "p1IssuedDate": iso(r[46:54]),
            "demeritPoints": int(r[54:56]),
            "suspendedDays": int(r[56:60]),
        }
        req = urllib.request.Request(f"{base}/api/drivers", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req) as resp:
            d = json.load(resp)
            print(f"{resp.status} {d['driverId']} {d['name']:<20} {d['licenceClass']} "
                  f"class={d['classIssuedDate']} pts={d['demeritPoints']} susp={d['suspendedDays']}")
