import unittest

import _paths
import copybook


class CopybookLayouts(unittest.TestCase):
    def layout(self, name):
        return copybook.load_layout(_paths.REPO / "copybooks" / f"{name}.cpy")

    def test_record_lengths_match_copybook_headers(self):
        self.assertEqual(self.layout("DRVMAST").length, 60)
        self.assertEqual(self.layout("OFFREC").length, 25)
        self.assertEqual(self.layout("VEHREC").length, 21)

    def test_offsets_and_88_levels_ignored(self):
        lay = self.layout("DRVMAST")
        names = [f.name for f in lay.fields]
        self.assertEqual(names, ["DRV-ID", "DRV-NAME", "DRV-DOB", "DRV-LIC-CLASS", "DRV-CLASS-DATE", "DRV-P1-DATE", "DRV-DMR-PTS", "DRV-SUSP-DAYS"])
        cls = next(f for f in lay.fields if f.name == "DRV-LIC-CLASS")
        self.assertEqual((cls.offset, cls.length, cls.kind), (36, 2, "X"))

    def test_parse_driver_record(self):
        r = self.layout("DRVMAST").parse("D0000007GRACE LIU           20050730P22024050120230410020120\n")
        self.assertEqual(r["DRV-ID"], "D0000007")
        self.assertEqual(r["DRV-NAME"].strip(), "GRACE LIU")
        self.assertEqual(r["DRV-LIC-CLASS"], "P2")
        self.assertEqual(copybook.num(r["DRV-DMR-PTS"]), 2)
        self.assertEqual(copybook.num(r["DRV-SUSP-DAYS"]), 120)

    def test_short_line_padded(self):
        r = self.layout("VEHREC").parse("123ABC")
        self.assertEqual(r["VEH-TYPE"], "  ")
        self.assertIsNone(copybook.num(r["VEH-CYL"]))

    def test_pic_without_length_is_one_byte(self):
        lay = copybook.parse_copybook("       01 R.\n           05  A  PIC X.\n           05  B  PIC 9(3).\n")
        self.assertEqual([(f.offset, f.length) for f in lay.fields], [(0, 1), (1, 3)])

    def test_iso_date(self):
        self.assertEqual(copybook.iso_date("20240229"), "2024-02-29")
        self.assertIsNone(copybook.iso_date("20230229"))
        self.assertIsNone(copybook.iso_date("00000000"))
        self.assertIsNone(copybook.iso_date("2024AB01"))


if __name__ == "__main__":
    unittest.main()
