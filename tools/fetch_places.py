#!/usr/bin/env python
"""Fetch and cache OSM places for a pack region."""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools import geo, places  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lat", type=float, required=True)
    ap.add_argument("--lon", type=float, required=True)
    ap.add_argument("--radius", type=float, default=85.0)
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "places" / "alps.json")
    ap.add_argument("--refresh", action="store_true")
    args = ap.parse_args()

    found = places.query(args.lat, args.lon, args.radius, refresh=args.refresh)
    found = [p for p in found if geo.distance_m(args.lat, args.lon, p.lat, p.lon) <= args.radius * 1000]
    found.sort(key=lambda p: p.rank, reverse=True)
    places.write_json(found, args.out)
    counts = Counter(p.kind for p in found)
    print(f"{len(found)} places -> {args.out}")
    for kind, n in counts.most_common():
        print(f"  {kind:9s} {n}")


if __name__ == "__main__":
    main()
