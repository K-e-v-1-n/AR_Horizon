#!/usr/bin/env python
"""Render the M0 golden panorama from a pack."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.render import render_panorama  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path,
                    default=ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite")
    ap.add_argument("--lat", type=float, default=45.98331)
    ap.add_argument("--lon", type=float, default=7.78470)
    ap.add_argument("--heading", type=float, default=265.0)
    ap.add_argument("--pitch", type=float, default=4.0)
    ap.add_argument("--fov", type=float, default=72.0)
    ap.add_argument("--eye", type=float, default=1.7)
    ap.add_argument("--width", type=int, default=2400)
    ap.add_argument("--height", type=int, default=1500)
    ap.add_argument("--labels", type=int, default=35)
    ap.add_argument("--title", default="Gornergrat")
    ap.add_argument("--subtitle", default="Valais, Switzerland · 3,104 m")
    ap.add_argument("--out", type=Path, default=ROOT / "docs/goldens/gornergrat.png")
    ap.add_argument("--meta", type=Path, default=ROOT / "docs/goldens/gornergrat.json")
    args = ap.parse_args()
    meta = render_panorama(args.pack, args.lat, args.lon, args.heading, args.out,
                           title=args.title, subtitle=args.subtitle,
                           width=args.width, height=args.height, hfov_deg=args.fov,
                           pitch_deg=args.pitch, eye_height=args.eye,
                           max_labels=args.labels, meta_out=args.meta)
    print(f"{args.out}  {len(meta['labels'])} labels")
    for label in meta["labels"][:12]:
        print(f"  {label['name']:28s} {label['az_deg']:6.1f}°  "
              f"{label['elev_deg']:5.1f}°  {label['dist_m'] / 1000:5.1f} km"
              f"{'  hidden' if label['hidden'] else ''}")


if __name__ == "__main__":
    main()
