"""AR_Horizon pack service.

Small and boring on purpose: a catalogue over pre-built packs, immutable
manifests, and resumable downloads. The global 1° tile set lives outside this
repo; `assemble` is the seam where a worker farm plugs in.
"""
from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

ROOT = Path(__file__).resolve().parents[1]
PACKS = Path(os.environ.get("VANTAGE_PACKS", ROOT / "server" / "data" / "packs"))
BASE_URL = os.environ.get("VANTAGE_BASE_URL", "")

app = FastAPI(title="AR_Horizon pack service", version="0.1.0")


def _manifest(path: Path) -> dict:
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as conn:
        row = conn.execute("SELECT json FROM manifest WHERE id='pack'").fetchone()
    if not row:
        raise ValueError(f"{path.name}: manifest missing")
    data = json.loads(row[0])
    data["size_bytes"] = path.stat().st_size
    data["download"] = f"{BASE_URL}/v1/packs/{data['id']}/download"
    return data


def _packs() -> list[dict]:
    if not PACKS.exists():
        return []
    out = []
    for path in sorted(PACKS.glob("*.sqlite")):
        try:
            out.append(_manifest(path))
        except Exception:
            continue
    return out


@app.get("/health")
def health() -> dict:
    packs = _packs()
    return {"ok": True, "packs": len(packs),
            "bytes": sum(p.get("size_bytes", 0) for p in packs)}


@app.get("/v1/catalogue")
def catalogue() -> dict:
    packs = _packs()
    return {
        "packs": packs,
        "attribution": {
            "dem": "Copernicus DEM © DLR e.V. / Airbus Defence and Space, under COPERNICUS",
            "places": "© OpenStreetMap contributors, ODbL 1.0",
        },
    }


def _pack_path(pack_id: str) -> Path:
    if not pack_id or "/" in pack_id or "\\" in pack_id or ".." in pack_id:
        raise HTTPException(status_code=404, detail="pack not found")
    path = PACKS / f"{pack_id}.sqlite"
    if not path.exists():
        raise HTTPException(status_code=404, detail="pack not found")
    return path


@app.get("/v1/packs/{pack_id}/manifest")
def manifest(pack_id: str) -> dict:
    return _manifest(_pack_path(pack_id))


@app.get("/v1/packs/{pack_id}/download")
def download(pack_id: str, request: Request) -> FileResponse:
    path = _pack_path(pack_id)
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=f"{pack_id}.sqlite",
        headers={"ETag": f'"{int(path.stat().st_mtime)}-{path.stat().st_size}"'},
    )


@app.get("/v1/coverage")
def coverage(lat: float, lon: float) -> dict:
    for pack in _packs():
        b = pack.get("bounds", [-90, -180, 90, 180])
        if b[0] <= lat <= b[2] and b[1] <= lon <= b[3]:
            return {"covered": True, "pack": pack}
    return {"covered": False, "packs": [p["id"] for p in _packs()]}


@app.post("/v1/assemble")
def assemble(request: dict) -> JSONResponse:
    """Where regional assembly plugs in.

    The production instance mounts the 1° tile set and assembles from it. This
    build ships the Alps pack and deliberately refuses to pretend otherwise.
    """
    try:
        lat = float(request["lat"])
        lon = float(request["lon"])
        radius = float(request.get("radius_km", 80))
    except (KeyError, TypeError, ValueError):
        raise HTTPException(status_code=422, detail="lat, lon and radius_km required")
    for pack in _packs():
        b = pack.get("bounds", [-90, -180, 90, 180])
        if b[0] <= lat <= b[2] and b[1] <= lon <= b[3]:
            return JSONResponse({"status": "already_assembled", "pack": pack})
    return JSONResponse(
        status_code=501,
        content={
            "status": "assembly_not_mounted",
            "detail": "This instance serves pre-built packs. Mount the global "
                      "1°×1° tile set and run tools/build_pack.py workers to enable "
                      "on-demand assembly.",
            "requested": {"lat": lat, "lon": lon, "radius_km": radius},
        },
    )


@app.get("/v1/attribution")
def attribution() -> dict:
    return {
        "dem": {
            "name": "Copernicus GLO-30",
            "credit": "Copernicus DEM © DLR e.V. 2010-2014 and © Airbus Defence and "
                      "Space GmbH 2014-2018, provided under COPERNICUS by the EU and ESA.",
            "url": "https://www.copernicus.eu/en/access-data/copernicus-data-space-ecosystem",
        },
        "places": {
            "name": "OpenStreetMap",
            "credit": "© OpenStreetMap contributors, ODbL 1.0",
            "url": "https://www.openstreetmap.org/copyright",
        },
        "index": {
            "name": "GeoNames cities15000",
            "credit": "GeoNames, CC BY 4.0",
            "url": "https://www.geonames.org/",
        },
    }
