"""Parity classification against the committed golden legacy outputs (no cobc needed)."""
import datetime as dt
import unittest

import _paths
import parity

SAMPLE = _paths.CASES / "01-shipped-sample"


class LegacyReports(unittest.TestCase):
    def test_read_golden_outputs(self):
        drivers, fees = parity.read_legacy(SAMPLE / "expected" / "out")
        self.assertEqual(len(drivers), 13)
        self.assertEqual(drivers["D0000009"], {"name": "ISLA MORGAN", "licenceClass": "OP", "points": 10, "suspensionNotice": False})
        self.assertFalse(drivers["D0000013"]["suspensionNotice"])
        self.assertEqual(fees["123ABC"], ["415.50"])
        self.assertEqual(fees["901YZA"], [None])


class Models(unittest.TestCase):
    def setUp(self):
        self.m = parity.models(SAMPLE, dt.date(2026, 10, 20))

    def test_legacy_model_reproduces_cobol(self):
        legacy, _ = parity.read_legacy(SAMPLE / "expected" / "out")
        for did, row in legacy.items():
            got = {k: self.m[did]["legacy"][k] for k in parity.FIELDS}
            self.assertEqual(got, {k: row[k] for k in parity.FIELDS}, did)

    def test_policy_model_matches_contract(self):
        self.assertEqual(self.m["D0000009"]["policy"]["points"], 10)
        self.assertFalse(self.m["D0000009"]["policy"]["suspensionNotice"])
        self.assertEqual(self.m["D0000013"]["policy"]["points"], 5)
        for did in ("D0000004", "D0000005", "D0000012"):
            self.assertEqual(self.m[did]["policy"]["licenceClass"], "P2", did)


class Classify(unittest.TestCase):
    def setUp(self):
        self.m = parity.models(SAMPLE, dt.date(2026, 10, 20))
        self.legacy, _ = parity.read_legacy(SAMPLE / "expected" / "out")

    def run_one(self, did, **modern):
        lg = self.legacy[did]
        md = dict({k: lg[k] for k in parity.FIELDS}, **modern)
        return parity.classify(did, lg, md, self.m[did])

    def test_match(self):
        self.assertEqual(self.run_one("D0000001"), ("MATCH", None, None))

    def test_br03_expected(self):
        status, rule, reason = self.run_one("D0000004", licenceClass="P2")
        self.assertEqual((status, rule), ("EXPECTED_DIFFERENCE", "BR-03"))
        self.assertIn("2025-06-15", reason)

    def test_br05_policy_now_matches_legacy(self):
        self.assertEqual(self.run_one("D0000009", points=10, suspensionNotice=False), ("MATCH", None, None))
        self.assertEqual(self.run_one("D0000013", points=5), ("MATCH", None, None))

    def test_wrong_policy_value_is_mismatch(self):
        self.assertEqual(self.run_one("D0000009", points=11)[0], "MISMATCH")
        self.assertEqual(self.run_one("D0000004", licenceClass="P1")[0], "MISMATCH")
        self.assertEqual(self.run_one("D0000001", points=3)[0], "MISMATCH")

    def test_missing_driver_is_mismatch(self):
        self.assertEqual(parity.classify("D0000001", self.legacy["D0000001"], None, None, "x")[0], "MISMATCH")


if __name__ == "__main__":
    unittest.main()
