"""Minimal fixed-width COBOL copybook reader for the LICSYS legacy files.

Derives each record layout from copybooks/*.cpy (level 05 elementary items with
PIC X(n) / 9(n)), so the loader and parity harness never hard-code offsets.
"""
from __future__ import annotations

import dataclasses
import datetime as dt
import pathlib
import re
from typing import Dict, Iterator, List, Optional

_FIELD_RE = re.compile(r"^\s*(\d{2})\s+([A-Z0-9-]+)\s+PIC\s+([X9])(?:\((\d+)\))?\s*\.", re.IGNORECASE)


@dataclasses.dataclass(frozen=True)
class Field:
    name: str
    kind: str  # "X" alphanumeric, "9" unsigned numeric
    offset: int
    length: int


@dataclasses.dataclass(frozen=True)
class Layout:
    name: str
    fields: List[Field]

    @property
    def length(self) -> int:
        return sum(f.length for f in self.fields)

    def parse(self, line: str) -> Dict[str, str]:
        """Slices one record into {FIELD-NAME: raw text}. Short lines are space padded."""
        rec = line.rstrip("\r\n").ljust(self.length)
        return {f.name: rec[f.offset:f.offset + f.length] for f in self.fields}


def parse_copybook(text: str, name: str = "") -> Layout:
    fields: List[Field] = []
    offset = 0
    for raw in text.splitlines():
        line = raw[6:] if len(raw) > 6 and raw[6:7] in (" ", "*") and raw[:6].strip() == "" else raw
        if line.lstrip().startswith("*"):
            continue
        m = _FIELD_RE.match(line)
        if not m:
            continue
        _level, fname, kind, size = m.groups()
        length = int(size) if size else 1
        fields.append(Field(fname.upper(), kind.upper(), offset, length))
        offset += length
    if not fields:
        raise ValueError(f"no PIC fields found in copybook {name!r}")
    return Layout(name, fields)


def load_layout(path: pathlib.Path) -> Layout:
    return parse_copybook(path.read_text(), path.stem)


def read_records(path: pathlib.Path, layout: Layout) -> Iterator[Dict[str, str]]:
    if not path.exists():
        return
    with path.open(encoding="ascii", errors="replace") as fh:
        for line in fh:
            if line.strip():
                yield layout.parse(line)


def num(value: str) -> Optional[int]:
    v = value.strip()
    return int(v) if v.isdigit() else None


def iso_date(value: str) -> Optional[str]:
    """YYYYMMDD -> YYYY-MM-DD, or None if not a real calendar date."""
    v = value.strip()
    if len(v) != 8 or not v.isdigit():
        return None
    try:
        return dt.date(int(v[:4]), int(v[4:6]), int(v[6:])).isoformat()
    except ValueError:
        return None
