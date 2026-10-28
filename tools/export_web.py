#!/usr/bin/env python
"""Export a self-contained web build from a terrain pack.

The browser demo runs the same pack, the same 3,600-bin horizon and the same
label rules as the Android app. No server and no phone: open web/index.html.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools import geo  # noqa: E402
from vantage_tools.grid import compute_silhouette  # noqa: E402
from vantage_tools.pack import PackReader  # noqa: E402

VIEWPOINTS = [
    {
        "id": "gornergrat",
        "name": "Gornergrat",
        "subtitle": "Valais, Switzerland",
        "lat": 45.98331,
        "lon": 7.78470,
        "eye_height": 1.7,
        "heading": 265.0,
        "pitch": 4.0,
        "fov": 72.0,
        "note": "Looking west at the Matterhorn",
    },
    {
        "id": "gornergrat-ne",
        "name": "Gornergrat · NE",
        "subtitle": "Weisshorn and the Zinal ridge",
        "lat": 45.98331,
        "lon": 7.78470,
        "eye_height": 1.7,
        "heading": 327.0,
        "pitch": 1.0,
        "fov": 70.0,
        "note": "Looking north-east",
    },
    {
        "id": "zermatt",
        "name": "Zermatt",
        "subtitle": "Valley floor · 1,608 m",
        "lat": 46.02070,
        "lon": 7.74910,
        "eye_height": 1.7,
        "heading": 262.0,
        "pitch": 13.0,
        "fov": 75.0,
        "note": "Matterhorn above the town",
    },
    {
        "id": "cervinia",
        "name": "Breuil-Cervinia",
        "subtitle": "Aosta Valley · 2,006 m",
        "lat": 45.93670,
        "lon": 7.63100,
        "eye_height": 1.7,
        "heading": 330.0,
        "pitch": 10.0,
        "fov": 78.0,
        "note": "The south face of the Matterhorn",
    },
]


def _round(values, digits=6):
    return [round(float(v), digits) for v in values]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path,
                    default=ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite")
    ap.add_argument("--out", type=Path, default=ROOT / "web/data.js")
    ap.add_argument("--json", type=Path, default=ROOT / "web/data.json")
    args = ap.parse_args()

    pack = PackReader(args.pack)
    places = pack.places()
    centre_lat, centre_lon = pack.manifest["center"]
    places = [p for p in places
              if geo.distance_m(centre_lat, centre_lon, p["lat"], p["lon"]) < 100_000]
    places.sort(key=lambda p: (
        {"peak": 0, "city": 1, "town": 2, "village": 3,
         "hamlet": 4, "water": 5, "landmark": 6}.get(p["kind"], 9),
        -float(p.get("rank") or 0),
    ))
    for i, p in enumerate(places):
        p["id"] = i
    print(f"{len(places)} places")

    viewpoints = []
    for vp in VIEWPOINTS:
        terrain = pack.terrain(vp["lat"], vp["lon"], 30.0,
                               float(pack.manifest.get("radius_km", 80)))
        ground = terrain.sample(vp["lat"], vp["lon"])
        if not math.isfinite(ground):
            print(f"skip {vp['id']}: outside pack")
            continue
        eye = ground + vp["eye_height"]
        full = compute_silhouette(terrain, vp["lat"], vp["lon"], vp["eye_height"],
                                  bins=3600, max_range=80_000)
        mid = compute_silhouette(terrain, vp["lat"], vp["lon"], vp["eye_height"],
                                 bins=3600, max_range=40_000)
        near = compute_silhouette(terrain, vp["lat"], vp["lon"], vp["eye_height"],
                                  bins=3600, max_range=13_000)
        viewpoints.append({
            **vp,
            "ground": round(ground, 1),
            "eye": round(eye, 1),
            "silhouette": {
                "full": _round(full.elev, 6),
                "mid": _round(mid.elev, 6),
                "near": _round(near.elev, 6),
            },
        })
        print(f"{vp['id']}: eye {eye:.0f} m, {len(full.elev)} bins")

    data = {
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "pack": {
            "id": pack.manifest.get("id"),
            "name": pack.manifest.get("name"),
            "radius_km": pack.manifest.get("radius_km"),
            "dem": pack.manifest.get("dem_source"),
            "dem_attribution": pack.manifest.get("dem_attribution"),
            "places_attribution": pack.manifest.get("places_attribution"),
            "quality": pack.manifest.get("quality"),
        },
        "places": [
            {
                "id": p["id"],
                "kind": p["kind"],
                "name": p["name"],
                "local": p.get("name_local") or p["name"],
                "lat": p["lat"],
                "lon": p["lon"],
                "ele": p.get("ele"),
                "pop": p.get("pop") or 0,
                "rank": p.get("rank") or 0,
                "wiki": p.get("wikidata") or "",
                "desc": p.get("desc") or "",
            }
            for p in places
        ],
        "viewpoints": viewpoints,
    }

    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("window.AR_HORIZON_DATA = " + json.dumps(data, ensure_ascii=False) + ";\n",
                        encoding="utf-8")
    print(f"wrote {args.out} ({args.out.stat().st_size / 1e6:.2f} MB) and {args.json}")
    print(f"{len(viewpoints)} viewpoints")


if __name__ == "__main__":
    main()
