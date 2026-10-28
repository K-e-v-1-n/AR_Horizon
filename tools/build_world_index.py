#!/usr/bin/env python
"""Build the bundled worldwide index (GeoNames cities + major peaks)."""
from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.index import IndexEntry, write  # noqa: E402

CITY_KIND = 1
TOWN_KIND = 2
PEAK_KIND = 0


def geonames_cities(cache: Path) -> list[IndexEntry]:
    cache.mkdir(parents=True, exist_ok=True)
    zip_path = cache / "cities15000.zip"
    if not zip_path.exists():
        url = "https://download.geonames.org/export/dump/cities15000.zip"
        print(f"downloading {url}")
        with requests.get(url, stream=True, timeout=180) as r:
            r.raise_for_status()
            with zip_path.open("wb") as f:
                for chunk in r.iter_content(1 << 20):
                    f.write(chunk)
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".txt"))
        text = z.read(name).decode("utf-8")
    out: list[IndexEntry] = []
    for line in text.splitlines():
        f = line.split("\t")
        if len(f) < 19:
            continue
        try:
            lat, lon = float(f[4]), float(f[5])
            pop = int(f[14] or 0)
        except ValueError:
            continue
        if pop < 15000:
            continue
        ele = -10000.0
        for idx in (15, 16):
            try:
                if f[idx]:
                    ele = float(f[idx])
                    break
            except (ValueError, IndexError):
                pass
        name = f[1].strip()
        kind = CITY_KIND if pop >= 100000 else TOWN_KIND
        out.append(IndexEntry(lat, lon, ele, pop, kind, name))
    return out


def peak_entries(alps: Path) -> list[IndexEntry]:
    peaks = json.loads((ROOT / "tools" / "world_peaks.json").read_text(encoding="utf-8"))
    out = [IndexEntry(p["lat"], p["lon"], float(p["ele"]), 0, PEAK_KIND, p["name"])
           for p in peaks]
    if alps.exists():
        for p in json.loads(alps.read_text(encoding="utf-8")):
            if p.get("kind") != "peak":
                continue
            ele = p.get("ele")
            try:
                ele = float(ele)
            except (TypeError, ValueError):
                continue
            if ele < 1200 or float(p.get("rank") or 0) < 0.4:
                continue
            out.append(IndexEntry(float(p["lat"]), float(p["lon"]), ele, 0,
                                  PEAK_KIND, p["name"]))
    # Deduplicate near-duplicate names within ~150 m.
    seen: set[tuple[str, int, int]] = set()
    unique = []
    for e in out:
        key = (e.name.casefold(), round(e.lat * 700), round(e.lon * 700))
        if key in seen:
            continue
        seen.add(key)
        unique.append(e)
    unique.sort(key=lambda e: (e.kind, -e.pop, e.name))
    return unique


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path,
                    default=ROOT / "android/app/src/main/assets/index/world.idx")
    ap.add_argument("--alps", type=Path, default=ROOT / "data/places/alps.json")
    args = ap.parse_args()
    cities = geonames_cities(ROOT / "data/cache/geonames")
    peaks = peak_entries(args.alps)
    entries = cities + peaks
    n = write(args.out, entries)
    size = args.out.stat().st_size / 1e6
    print(f"{n} entries ({len(cities)} cities/towns, {len(peaks)} peaks) -> {args.out} ({size:.2f} MB)")


if __name__ == "__main__":
    main()
