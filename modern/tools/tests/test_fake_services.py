"""Policy behaviour of the fake services, and the loader + parity end to end against them."""
import json
import pathlib
import shutil
import tempfile
import unittest

import _paths
import fake_services
import loader
import parity


class FakeServiceRules(unittest.TestCase):
    def test_br06_quotes(self):
        reg = fake_services.RegistrationService(fake_services.Bus())
        q = lambda **b: reg.handle("POST", "/api/renewals/quote", {}, dict({"plate": "X", "cylinders": 4, "concession": False, "termMonths": 12}, **b))  # noqa: E731
        self.assertEqual(q(vehicleType="CAR")[1]["total"], "415.50")
        self.assertEqual(q(vehicleType="CAR", cylinders=6, termMonths=6)[1]["total"], "319.96")
        self.assertEqual(q(vehicleType="TRAILER", cylinders=0, concession=True)[1]["total"], "120.95")
        self.assertEqual(q(vehicleType="BU")[0], 400)
        self.assertEqual(q(vehicleType="CAR", termMonths=9), (400, {"rule": "BR-06", "reason": "term 9 months is not 12, 6 or 3"}))

    def test_br03_uses_p2_date(self):
        drv = {"licenceClass": "P2", "classIssuedDate": "2025-06-15", "suspendedDays": 0, "demeritPoints": 1}
        res = fake_services.LicenceService.evaluate(drv, fake_services.d("2026-10-20"))
        self.assertFalse(res["eligible"])
        self.assertEqual((res["rule"], res["tenureDays"]), ("BR-03", 492))


class EndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fake = fake_services.FakeLicsys(ports=(0, 0, 0, 0), event_delay=0.05)
        cls.urls = loader.parse_base_urls(cls.fake.base_urls)

    @classmethod
    def tearDownClass(cls):
        cls.fake.stop()

    def test_loader_waits_for_projections(self):
        summary = loader.load(_paths.REPO / "data", self.urls, wait_timeout=10, log=lambda m: None)
        self.assertEqual((summary["drivers"], summary["offences"]), (13, 13))
        _, dm = loader.http("GET", f"{self.urls['demerit']}/api/drivers/D0000013/demerits")
        _, dr = loader.http("GET", f"{self.urls['licence']}/api/drivers/D0000013")
        self.assertEqual((dm["points"], dr["demeritPoints"]), (5, 5))
        self.assertEqual(sum(o["appliedPoints"] for o in dm["offences"]), 3)

    def test_parity_on_shipped_sample_with_golden_legacy(self):
        out = pathlib.Path(tempfile.mkdtemp()) / "parity.json"
        try:
            rc = parity.main(["--case", str(_paths.CASES / "01-shipped-sample"), "--out", str(out),
                              "--legacy-out", str(_paths.CASES / "01-shipped-sample" / "expected" / "out"),
                              "--base-urls", self.fake.base_urls, "--wait-timeout", "10"])
            report = json.loads(out.read_text())
        finally:
            shutil.rmtree(out.parent)
        self.assertEqual(rc, 0)
        self.assertEqual(set(report), {"generatedAt", "summary", "drivers", "renewals"})
        self.assertEqual(report["summary"], {"drivers": 13, "matched": 8, "expectedDifferences": 5, "unexpected": 0, "renewals": 10, "renewalMatched": 10})
        expected = {r["driverId"]: r["rule"] for r in report["drivers"] if r["status"] == "EXPECTED_DIFFERENCE"}
        self.assertEqual(expected, {"D0000004": "BR-03", "D0000005": "BR-03", "D0000012": "BR-03", "D0000009": "BR-05", "D0000013": "BR-05"})
        row = report["drivers"][0]
        self.assertEqual(set(row), {"driverId", "name", "legacy", "modern", "status", "rule", "reason"})
        self.assertEqual(set(row["legacy"]), {"licenceClass", "points", "suspensionNotice"})
        self.assertEqual(set(report["renewals"][0]), {"plate", "legacyTotal", "modernTotal", "match"})

    @unittest.skipUnless(shutil.which("cobc"), "GnuCOBOL not installed")
    def test_parity_runs_cobol_batch(self):
        out = pathlib.Path(tempfile.mkdtemp()) / "parity.json"
        try:
            rc = parity.main(["--case", str(_paths.REPO), "--out", str(out), "--base-urls", self.fake.base_urls, "--wait-timeout", "10"])
            summary = json.loads(out.read_text())["summary"]
        finally:
            shutil.rmtree(out.parent)
        self.assertEqual(rc, 0)
        self.assertEqual(summary["expectedDifferences"], 5)


if __name__ == "__main__":
    unittest.main()
