import unittest

import _paths
import loader

DATA = _paths.REPO / "data"


class DriverParsing(unittest.TestCase):
    def test_sample_drivers(self):
        drivers, rejected = loader.parse_drivers(DATA)
        self.assertEqual(len(drivers), 13)
        self.assertEqual(rejected, [])
        isla = next(d for d in drivers if d["driverId"] == "D0000009")
        self.assertEqual(isla, {"driverId": "D0000009", "name": "ISLA MORGAN", "dob": "1992-04-11", "licenceClass": "OP",
                                "classIssuedDate": "2014-03-01", "p1IssuedDate": "2011-02-25", "suspendedDays": 0, "demeritPoints": 6})

    def test_invalid_records_rejected_with_reasons(self):
        drivers, rejected = loader.parse_drivers(_paths.CASES / "06-invalid-records" / "data")
        reasons = {r.key: r.reason for r in rejected}
        self.assertIn("unknown licence class 'XX'", reasons["V0000001"])
        self.assertIn("invalid class issue date", reasons["V0000002"])
        self.assertIn("duplicate", reasons["V0000009"])
        self.assertIn("p1", reasons["V0000008"])
        self.assertEqual(len({d["driverId"] for d in drivers}), len(drivers))


class OffenceParsing(unittest.TestCase):
    def test_status_p_is_already_posted_and_sorted(self):
        offences, rejected = loader.parse_offences(DATA)
        self.assertEqual(rejected, [])
        self.assertEqual(len(offences), 13)
        self.assertEqual(sum(o["alreadyPosted"] for o in offences), 7)
        keys = [(o["driverId"], o["date"]) for o in offences]
        self.assertEqual(keys, sorted(keys))
        self.assertEqual(offences[0], {"driverId": "D0000006", "date": "2025-11-02", "code": "SP11", "offenceClass": "SP", "basePoints": 3, "alreadyPosted": True})

    def test_out_of_order_file_is_sorted_and_unknowns_skipped(self):
        data = _paths.CASES / "06-invalid-records" / "data"
        drivers, _ = loader.parse_drivers(data)
        offences, rejected = loader.parse_offences(data, known_drivers={d["driverId"] for d in drivers})
        keys = [(o["driverId"], o["date"]) for o in offences]
        self.assertEqual(keys, sorted(keys))
        self.assertTrue(any("no master record" in r.reason for r in rejected))
        self.assertTrue(any("unknown status" in r.reason for r in rejected))


class VehicleParsing(unittest.TestCase):
    def test_sample_vehicles(self):
        v = loader.parse_vehicles(DATA)
        self.assertEqual(len(v), 10)
        self.assertEqual(v[0], {"plate": "123ABC", "ownerId": "D0000006", "vehicleType": "CAR", "cylinders": 4, "concession": False, "termMonths": 12})
        self.assertEqual(v[4]["vehicleType"], "MOTORCYCLE")
        self.assertTrue(v[4]["concession"])
        self.assertEqual(v[5]["vehicleType"], "TRAILER")
        self.assertEqual(v[8]["vehicleType"], "BU")  # unknown types passed through for the service to reject


class BaseUrls(unittest.TestCase):
    def test_defaults_and_overrides(self):
        urls = loader.parse_base_urls("licence-service=http://x:1/,demerit=http://y:2")
        self.assertEqual(urls["licence"], "http://x:1")
        self.assertEqual(urls["demerit"], "http://y:2")
        self.assertEqual(urls["registration"], "http://localhost:8083")

    def test_bad_entry(self):
        with self.assertRaises(ValueError):
            loader.parse_base_urls("billing=http://z")


if __name__ == "__main__":
    unittest.main()
