import pathlib
import sys

TOOLS = pathlib.Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
CASES = REPO / "tests" / "cases"
for sub in ("loader", "parity", "testing"):
    sys.path.insert(0, str(TOOLS / sub))
