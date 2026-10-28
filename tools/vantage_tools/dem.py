"""Copernicus GLO-30 access.

Public S3 mirror, no key. Files are 1°×1° COGs at 1 arc-second; we cache them
under data/cache/dem and never need them again once a pack is built.
"""
from __future__ import annotations

import math
import shutil
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import requests

from .grid import Grid

S3 = "https://copernicus-dem-30m.s3.amazonaws.com"
CACHE = Path(__file__).resolve().parents[2] / "data" / "cache" / "dem"
TILE_ARCSEC = 3600


def _ns(lat: int) -> str:
    return f"{'N' if lat >= 0 else 'S'}{abs(lat):02d}_00"


def _ew(lon: int) -> str:
    return f"{'E' if lon >= 0 else 'W'}{abs(lon):03d}_00"


def tile_name(lat: int, lon: int) -> str:
    return f"Copernicus_DSM_COG_10_{_ns(lat)}_{_ew(lon)}_DEM"


def tile_path(lat: int, lon: int, cache: Path = CACHE) -> Path:
    return Path(cache) / tile_name(lat, lon) / f"{tile_name(lat, lon)}.tif"


def tiles_for_bounds(min_lat: float, min_lon: float, max_lat: float, max_lon: float,
                     cache: Path = CACHE):
    lat0 = math.floor(min_lat)
    lat1 = math.floor(max_lat - 1e-9)
    lon0 = math.floor(min_lon)
    lon1 = math.floor(max_lon - 1e-9)
    return [(la, lo) for la in range(lat0, lat1 + 1) for lo in range(lon0, lon1 + 1)]


def _download_one(lat: int, lon: int, cache: Path, timeout: int = 120) -> Path:
    dest = tile_path(lat, lon, cache)
    if dest.exists() and dest.stat().st_size > 8_000_000:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    name = tile_name(lat, lon)
    url = f"{S3}/{name}/{name}.tif"
    part = dest.with_suffix(".tif.part")
    last: Exception | None = None
    for attempt in range(4):
        try:
            with requests.get(url, stream=True, timeout=timeout) as r:
                r.raise_for_status()
                with part.open("wb") as f:
                    for chunk in r.iter_content(chunk_size=1 << 20):
                        if chunk:
                            f.write(chunk)
            part.replace(dest)
            return dest
        except Exception as exc:  # noqa: BLE001
            last = exc
            if part.exists():
                part.unlink(missing_ok=True)
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"failed to download {name}: {last}")


def download_tiles(lat: int, lon: int, cache: Path = CACHE) -> Path:
    return _download_one(lat, lon, Path(cache))


def download_for_bounds(min_lat: float, min_lon: float, max_lat: float, max_lon: float,
                        cache: Path = CACHE, workers: int = 3) -> list[Path]:
    cells = tiles_for_bounds(min_lat, min_lon, max_lat, max_lon, cache)
    results: list[Path] = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(_download_one, la, lo, Path(cache)): (la, lo) for la, lo in cells}
        for fut in as_completed(futures):
            la, lo = futures[fut]
            path = fut.result()
            print(f"  DEM {_ns(la)} {_ew(lo)}  {path.stat().st_size / 1e6:.1f} MB")
            results.append(path)
    return results


def load_cog(lat: int, lon: int, cache: Path = CACHE) -> Grid:
    """Reads a 1° COG into an int16 grid (row 0 = north)."""
    import rasterio

    path = tile_path(lat, lon, cache)
    if not path.exists():
        raise FileNotFoundError(f"{path} missing; run fetch_dem.py first")
    with rasterio.open(path) as ds:
        data = ds.read(1)
        bounds = ds.bounds
    if data.shape != (TILE_ARCSEC, TILE_ARCSEC):
        # Some fallback tiles are 1° at a different sampling; resample to 1".
        from PIL import Image
        data = np.asarray(Image.fromarray(data).resize((TILE_ARCSEC, TILE_ARCSEC), Image.BILINEAR))
    data = np.where(np.isfinite(data), data, 0).astype(np.int16)
    return Grid(data=data, lat_north=bounds.top, lon_west=bounds.left,
                dlat=1.0 / TILE_ARCSEC, dlon=1.0 / TILE_ARCSEC)


def load_mosaic(min_lat: float, min_lon: float, max_lat: float, max_lon: float,
                cache: Path = CACHE) -> Grid:
    """Mosaics covering COGs into one grid of 1" cells aligned to whole degrees."""
    lat0, lon0 = math.floor(min_lat), math.floor(min_lon)
    lat1, lon1 = math.ceil(max_lat), math.ceil(max_lon)
    h = (lat1 - lat0) * TILE_ARCSEC
    w = (lon1 - lon0) * TILE_ARCSEC
    data = np.full((h, w), -32768, dtype=np.int16)
    for la in range(lat0, lat1):
        for lo in range(lon0, lon1):
            g = load_cog(la, lo, cache)
            r0 = int(round((lat1 - (la + 1)) * TILE_ARCSEC))
            c0 = int(round((lo - lon0) * TILE_ARCSEC))
            data[r0:r0 + TILE_ARCSEC, c0:c0 + TILE_ARCSEC] = g.data
    return Grid(data=data, lat_north=float(lat1), lon_west=float(lon0),
                dlat=1.0 / TILE_ARCSEC, dlon=1.0 / TILE_ARCSEC)


def clear_cache(cache: Path = CACHE) -> None:
    if Path(cache).exists():
        shutil.rmtree(cache)
