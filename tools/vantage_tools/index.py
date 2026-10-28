"""Compact worldwide index: cities and major peaks usable without a pack.

Format (little endian):
  magic  8 bytes  "VTIDX1\0\0"
  u32    version  (1)
  u32    count
  record:
    f32 lat, f32 lon, f32 ele_m, i32 population
    u8 kind, u8 name_len, name bytes (UTF-8)
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

MAGIC = b"VTIDX1\x00\x00"
VERSION = 1


@dataclass
class IndexEntry:
    lat: float
    lon: float
    ele: float
    pop: int
    kind: int
    name: str


def write(path: Path, entries: Iterable[IndexEntry]) -> int:
    rows = list(entries)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<II", VERSION, len(rows)))
        for e in rows:
            name = e.name.encode("utf-8")[:255]
            # Do not leave a split UTF-8 sequence at the end of the record.
            while name:
                try:
                    name.decode("utf-8")
                    break
                except UnicodeDecodeError:
                    name = name[:-1]
            f.write(struct.pack("<fffIBB", e.lat, e.lon, e.ele, int(e.pop),
                                int(e.kind), len(name)))
            f.write(name)
    return len(rows)


def read(path: Path) -> list[IndexEntry]:
    data = Path(path).read_bytes()
    if data[:8] != MAGIC:
        raise ValueError("not a Vantage world index")
    version, count = struct.unpack_from("<II", data, 8)
    if version != VERSION:
        raise ValueError(f"unsupported index version {version}")
    off = 16
    out: list[IndexEntry] = []
    for _ in range(count):
        lat, lon, ele, pop, kind, n = struct.unpack_from("<fffIBB", data, off)
        off += 18
        name = data[off:off + n].decode("utf-8", "replace")
        off += n
        out.append(IndexEntry(lat, lon, ele, pop, kind, name))
    return out
