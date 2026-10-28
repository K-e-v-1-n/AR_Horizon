#!/usr/bin/env python
"""Export pack grids as .vtgrid for the portable C++ CLI cross-check."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.grid import write_vtgrid  # noqa: E402
from vantage_tools.pack import PackReader  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path,
                    default=ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite")
    ap.add_argument("--lat", type=float, default=45.98331)
    ap.add_argument("--lon", type=float, default=7.78470)
    ap.add_argument("--fine-radius", type=float, default=30.0)
    ap.add_argument("--coarse-radius", type=float, default=80.0)
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "goldens")
    args = ap.parse_args()
    pack = PackReader(args.pack)
    terrain = pack.terrain(args.lat, args.lon, args.fine_radius, args.coarse_radius)
    args.out.mkdir(parents=True, exist_ok=True)
    if terrain.fine:
        write_vtgrid(args.out / "gornergrat_fine.vtgrid", terrain.fine)
        print("fine", terrain.fine.data.shape, terrain.fine.lat_north, terrain.fine.lon_west)
    if terrain.coarse:
        write_vtgrid(args.out / "gornergrat_coarse.vtgrid", terrain.coarse)
        print("coarse", terrain.coarse.data.shape, terrain.coarse.lat_north, terrain.coarse.lon_west)


if __name__ == "__main__":
    main()
