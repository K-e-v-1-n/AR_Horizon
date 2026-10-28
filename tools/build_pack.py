#!/usr/bin/env python
"""Assemble a Vantage offline pack from cached DEM tiles and OSM places."""
from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools import dem, places as places_mod  # noqa: E402
from vantage_tools.pack import build_pack  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lat", type=float, required=True)
    ap.add_argument("--lon", type=float, required=True)
    ap.add_argument("--radius", type=float, default=80.0)
    ap.add_argument("--name", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--places", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--download-dem", action="store_true",
                    help="download missing Copernicus tiles first")
    args = ap.parse_args()

    if args.download_dem:
        r = args.radius / 111.0
        rlon = r / max(0.1, math.cos(math.radians(args.lat)))
        dem.download_for_bounds(args.lat - r, args.lon - rlon,
                                args.lat + r, args.lon + rlon)
    found = places_mod.read_json(args.places)
    build_pack(args.lat, args.lon, args.radius, args.name, args.id, args.out, found)
    print("done")


if __name__ == "__main__":
    main()
