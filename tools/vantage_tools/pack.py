"""Offline pack format: SQLite + zstd int16 terrain tiles.

pack.sqlite
  manifest(id TEXT PRIMARY KEY, json TEXT)
  dem(z INTEGER, x INTEGER, y INTEGER, w INTEGER, h INTEGER, data BLOB)
  places(...)
  places_rtree
  sector_max(az INTEGER, ring INTEGER, max_ele REAL)

Tiles are 600×600 cells and aligned to whole degrees (6 tiles per degree), so
the same tile ids work for every region and can be assembled into packs
straight from the global tile set. z=0 is 1", z=1 is 4".
"""
from __future__ import annotations

import json
import math
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional

import numpy as np
import zstandard as zstd

from . import dem, geo
from .grid import TILE_SIZE, TILES_PER_DEGREE, Grid, Terrain
from .places import Place

ZSTD_LEVEL = 7
RINGS_KM = (1, 2, 4, 8, 16, 32, 64, 128)
DEM_SOURCE = "Copernicus GLO-30 DSM (30 m surface model)"
DEM_ATTRIBUTION = (
    "Copernicus DEM © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH "
    "2014-2018, provided under COPERNICUS by the European Union and ESA."
)


def tile_origin(z: int) -> float:
    """Degrees covered by one tile at level z (z=0: 1/6°, z=1: 2/3°)."""
    return TILE_SIZE / 3600.0 * (4 ** z)


def tile_index(lat: float, lon: float, z: int) -> tuple[int, int]:
    step = tile_origin(z)
    x = int(math.floor((lon + 180.0) / step))
    y = int(math.floor((90.0 - lat) / step))
    return x, y


def tile_bounds(x: int, y: int, z: int) -> tuple[float, float]:
    step = tile_origin(z)
    return 90.0 - y * step, x * step - 180.0


def compress(a: np.ndarray) -> bytes:
    return zstd.ZstdCompressor(level=ZSTD_LEVEL).compress(
        np.ascontiguousarray(a, dtype="<i2").tobytes())


def decompress(blob: bytes, w: int, h: int) -> np.ndarray:
    raw = zstd.ZstdDecompressor().decompress(blob, max_output_size=w * h * 2)
    return np.frombuffer(raw, dtype="<i2").reshape(h, w).copy()


@dataclass
class PackInfo:
    path: Path
    manifest: dict


class PackWriter:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            self.path.unlink()
        self.conn = sqlite3.connect(self.path)
        self.conn.execute("PRAGMA journal_mode=OFF")
        self.conn.execute("PRAGMA synchronous=OFF")
        self.conn.execute("PRAGMA page_size=8192")
        self.conn.executescript("""
            CREATE TABLE manifest (id TEXT PRIMARY KEY, json TEXT NOT NULL);
            CREATE TABLE dem (z INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL,
                              w INTEGER NOT NULL, h INTEGER NOT NULL, data BLOB NOT NULL,
                              PRIMARY KEY (z, x, y));
            CREATE TABLE places (
                id INTEGER PRIMARY KEY, osm_id TEXT, kind TEXT, name TEXT,
                name_local TEXT, lat REAL, lon REAL, ele REAL, pop INTEGER,
                rank REAL, wikidata TEXT, source TEXT, desc TEXT);
            CREATE VIRTUAL TABLE places_rtree USING rtree(id, minlat, maxlat, minlon, maxlon);
            CREATE TABLE sector_max (az INTEGER, ring INTEGER, max_ele REAL,
                                     PRIMARY KEY (az, ring));
        """)

    def add_tile(self, z: int, x: int, y: int, data: np.ndarray) -> None:
        if data.shape != (TILE_SIZE, TILE_SIZE):
            raise ValueError(f"tile {z}/{x}/{y} has shape {data.shape}")
        self.conn.execute(
            "INSERT OR REPLACE INTO dem(z,x,y,w,h,data) VALUES (?,?,?,?,?,?)",
            (z, x, y, TILE_SIZE, TILE_SIZE, compress(data)))

    def add_places(self, places: Iterable[Place]) -> int:
        n = 0
        for p in places:
            cur = self.conn.execute(
                "INSERT INTO places(osm_id,kind,name,name_local,lat,lon,ele,pop,rank,wikidata,source,desc)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                p.to_row())
            pid = cur.lastrowid
            self.conn.execute(
                "INSERT INTO places_rtree(id,minlat,maxlat,minlon,maxlon) VALUES (?,?,?,?,?)",
                (pid, p.lat, p.lat, p.lon, p.lon))
            n += 1
        return n

    def set_manifest(self, manifest: dict) -> None:
        self.conn.execute("INSERT OR REPLACE INTO manifest(id,json) VALUES ('pack',?)",
                          (json.dumps(manifest, ensure_ascii=False),))

    def add_sector_max(self, rows: Iterable[tuple[int, int, float]]) -> None:
        self.conn.executemany(
            "INSERT OR REPLACE INTO sector_max(az,ring,max_ele) VALUES (?,?,?)", rows)

    def close(self) -> None:
        self.conn.commit()
        self.conn.execute("PRAGMA optimize")
        self.conn.close()


def _blit(dest: np.ndarray, tile: np.ndarray, row: int, col: int) -> None:
    h, w = tile.shape
    dh, dw = dest.shape
    r0, c0 = max(0, row), max(0, col)
    r1, c1 = min(dh, row + h), min(dw, col + w)
    if r1 <= r0 or c1 <= c0:
        return
    dest[r0:r1, c0:c1] = tile[r0 - row:r1 - row, c0 - col:c1 - col]


def _grid_for_extent(min_lat: float, min_lon: float, max_lat: float, max_lon: float,
                     z: int) -> tuple[int, int, int, int, float, float]:
    step = tile_origin(z)
    x0 = int(math.floor((min_lon + 180.0) / step))
    x1 = int(math.ceil((max_lon + 180.0) / step))
    y0 = int(math.floor((90.0 - max_lat) / step))
    y1 = int(math.ceil((90.0 - min_lat) / step))
    lat_north = 90.0 - y0 * step
    lon_west = x0 * step - 180.0
    return x0, y0, x1, y1, lat_north, lon_west


def build_pack(center_lat: float, center_lon: float, radius_km: float, name: str,
               pack_id: str, out: Path, places: list[Place], dem_cache: Path = dem.CACHE,
               verbose: bool = True) -> PackInfo:
    lat_r = radius_km / 111.0
    lon_r = radius_km / max(1.0, 111.0 * math.cos(math.radians(center_lat)))
    bbox = (center_lat - lat_r, center_lon - lon_r,
            center_lat + lat_r, center_lon + lon_r)
    # Keep the pack self-contained: places outside the terrain are index-only
    # and must not inflate the pack or claim a terrain check.
    pad_lat = 2.0 / 111.0
    pad_lon = pad_lat / max(0.15, math.cos(math.radians(center_lat)))
    places = [p for p in places
              if bbox[0] - pad_lat <= p.lat <= bbox[2] + pad_lat
              and bbox[1] - pad_lon <= p.lon <= bbox[3] + pad_lon]
    cells = dem.tiles_for_bounds(*bbox, cache=dem_cache)
    if verbose:
        print(f"pack {name}: {radius_km:.0f} km, {len(cells)} DEM tiles")

    writer = PackWriter(out)
    try:
        tile_cache: dict[tuple[int, int], np.ndarray] = {}
        count = 0
        for la, lo in cells:
            g = dem.load_cog(la, lo, dem_cache)
            gx0 = (lo + 180) * TILES_PER_DEGREE
            gy0 = (90 - (la + 1)) * TILES_PER_DEGREE
            for sy in range(TILES_PER_DEGREE):
                for sx in range(TILES_PER_DEGREE):
                    gx, gy = gx0 + sx, gy0 + sy
                    t_lat_n = 90.0 - gy / TILES_PER_DEGREE
                    t_lat_s = t_lat_n - tile_origin(0)
                    t_lon_w = gx / TILES_PER_DEGREE - 180.0
                    t_lon_e = t_lon_w + tile_origin(0)
                    if (t_lat_s > bbox[2] + 0.01 or t_lat_n < bbox[0] - 0.01 or
                            t_lon_e < bbox[1] - 0.01 or t_lon_w > bbox[3] + 0.01):
                        continue
                    tile = g.data[sy * TILE_SIZE:(sy + 1) * TILE_SIZE,
                                  sx * TILE_SIZE:(sx + 1) * TILE_SIZE]
                    writer.add_tile(0, gx, gy, tile)
                    tile_cache[(gx, gy)] = tile
                    count += 1
            if verbose:
                print(f"  z0 {dem.tile_name(la, lo)} done")

        # Level 1: 4x4 block means, only where the full block is present.
        groups: dict[tuple[int, int], list[tuple[int, int]]] = {}
        for (gx, gy) in tile_cache:
            groups.setdefault((gx // 4, gy // 4), []).append((gx, gy))
        coarse_tiles = 0
        for (gx1, gy1), members in groups.items():
            if not members:
                continue
            present = set(members)
            positions = [(dx, dy) for dy in range(4) for dx in range(4)]
            big = np.zeros((4 * TILE_SIZE, 4 * TILE_SIZE), dtype=np.float32)
            for dx, dy in positions:
                gx, gy = gx1 * 4 + dx, gy1 * 4 + dy
                if (gx, gy) not in present:
                    # Edge group: replicate the nearest tile so the far field does
                    # not acquire a fake hole just outside the requested radius.
                    gx, gy = min(present, key=lambda p: abs(p[0] - gx) + abs(p[1] - gy))
                row, col = dy * TILE_SIZE, dx * TILE_SIZE
                big[row:row + TILE_SIZE, col:col + TILE_SIZE] = tile_cache[(gx, gy)]
            coarse = big.reshape(TILE_SIZE, 4, TILE_SIZE, 4).mean(axis=(1, 3))
            writer.add_tile(1, gx1, gy1, np.rint(coarse).astype(np.int16))
            coarse_tiles += 1
        if verbose:
            print(f"  z1 {coarse_tiles} coarse tiles")

        # Fill missing elevations from the DEM (pack terrain), then insert places.
        fine_terrain = assemble_terrain_from_tiles(tile_cache, 0, bbox)
        for p in places:
            if p.kind == "peak" and not math.isfinite(p.ele):
                p.ele = _local_max(fine_terrain, p.lat, p.lon, 3)
            elif not math.isfinite(p.ele):
                p.ele = fine_terrain.sample(p.lat, p.lon)
            if not math.isfinite(p.ele):
                p.ele = -10000.0
        writer.add_places(places)

        writer.add_sector_max(_sector_max(tile_cache, center_lat, center_lon))

        actual = (bbox[0], bbox[1], bbox[2], bbox[3])
        manifest = {
            "format": "vantage-pack/1",
            "id": pack_id,
            "name": name,
            "center": [center_lat, center_lon],
            "radius_km": radius_km,
            "bounds": [actual[0], actual[1], actual[2], actual[3]],
            "version": datetime.now(timezone.utc).strftime("%Y.%m.%d"),
            "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "tile_size": TILE_SIZE,
            "tiles_per_degree": TILES_PER_DEGREE,
            "levels": [{"z": 0, "arcsec": 1, "metres": 30},
                       {"z": 1, "arcsec": 4, "metres": 120}],
            "dem_source": DEM_SOURCE,
            "dem_attribution": DEM_ATTRIBUTION,
            "places_attribution": "© OpenStreetMap contributors, ODbL 1.0",
            "quality": {
                "score": 0.9 if radius_km <= 100 else 0.8,
                "dem": "GLO-30",
                "place_density": "high" if len(places) > 3000 else "medium",
                "note": "30 m surface model: canopy and buildings read as bulk; single trees are not resolved.",
            },
            "counts": {"tiles_z0": count, "tiles_z1": coarse_tiles, "places": len(places)},
        }
        writer.set_manifest(manifest)
        if verbose:
            mb = out.stat().st_size / 1e6
            print(f"  wrote {out} ({mb:.1f} MB, {len(places)} places)")
        return PackInfo(path=out, manifest=manifest)
    finally:
        writer.close()


def _local_max(terrain: Terrain, lat: float, lon: float, radius_cells: int) -> float:
    if not terrain.fine:
        return terrain.sample(lat, lon)
    g = terrain.fine
    r = int((g.lat_north - lat) / g.dlat)
    c = int((lon - g.lon_west) / g.dlon)
    r0, r1 = max(0, r - radius_cells), min(g.height, r + radius_cells + 1)
    c0, c1 = max(0, c - radius_cells), min(g.width, c + radius_cells + 1)
    if r0 >= r1 or c0 >= c1:
        return terrain.sample(lat, lon)
    return float(g.data[r0:r1, c0:c1].max())


def _sector_max(tile_cache: dict[tuple[int, int], np.ndarray], lat: float,
                lon: float) -> list[tuple[int, int, float]]:
    """Coarse per-azimuth/ring maximum for fast-marching early exit.

    Sampled every fourth cell: these are hints for culling, not geometry.
    """
    if not tile_cache:
        return []
    xs = [gx for gx, _ in tile_cache]
    ys = [gy for _, gy in tile_cache]
    x0, x1, y0, y1 = min(xs), max(xs) + 1, min(ys), max(ys) + 1
    step = 4
    tile_cells = TILE_SIZE // step
    big = np.full(((y1 - y0) * tile_cells, (x1 - x0) * tile_cells), -32768,
                  dtype=np.int16)
    for (gx, gy), tile in tile_cache.items():
        row = (gy - y0) * tile_cells
        col = (gx - x0) * tile_cells
        big[row:row + tile_cells, col:col + tile_cells] = tile[::step, ::step]
    h, w = big.shape
    lat_n = 90.0 - y0 / TILES_PER_DEGREE
    lon_w = x0 / TILES_PER_DEGREE - 180.0
    rows, cols = np.indices((h, w))
    cell_lat = lat_n - (rows + 0.5) * step / 3600.0
    cell_lon = lon_w + (cols + 0.5) * step / 3600.0
    coslat = math.cos(math.radians(lat))
    d = np.sqrt(((cell_lat - lat) * 111320.0) ** 2 +
                ((cell_lon - lon) * 111320.0 * coslat) ** 2)
    az = (np.degrees(np.arctan2((cell_lon - lon) * coslat, cell_lat - lat)) + 360.0) % 360.0
    rings = np.digitize(d / 1000.0, RINGS_KM)
    sector = np.clip(az.astype(np.int32), 0, 359)
    flats = (sector * (len(RINGS_KM) + 1) + rings).ravel()
    values = big.ravel()
    out = np.full(360 * (len(RINGS_KM) + 1), -32768, dtype=np.int16)
    np.maximum.at(out, flats, values)
    result = []
    for sector_i in range(360):
        for ri in range(len(RINGS_KM) + 1):
            v = int(out[sector_i * (len(RINGS_KM) + 1) + ri])
            if v > -32768:
                result.append((sector_i, ri, float(v)))
    return result


def assemble_terrain_from_tiles(tile_cache: dict[tuple[int, int], np.ndarray], z: int,
                                bbox: tuple[float, float, float, float]) -> Terrain:
    min_lat, min_lon, max_lat, max_lon = bbox
    x0, y0, x1, y1, lat_n, lon_w = _grid_for_extent(min_lat, min_lon, max_lat, max_lon, z)
    w = (x1 - x0) * TILE_SIZE
    h = (y1 - y0) * TILE_SIZE
    data = np.full((h, w), -32768, dtype=np.int16)
    found = False
    for gy in range(y0, y1):
        for gx in range(x0, x1):
            tile = tile_cache.get((gx, gy))
            if tile is None:
                continue
            _blit(data, tile, (gy - y0) * TILE_SIZE, (gx - x0) * TILE_SIZE)
            found = True
    if not found:
        return Terrain()
    g = Grid(data=data, lat_north=lat_n, lon_west=lon_w,
             dlat=1.0 / 3600.0 * (4 ** z), dlon=1.0 / 3600.0 * (4 ** z))
    return Terrain(fine=g) if z == 0 else Terrain(coarse=g)


class PackReader:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.conn = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        row = self.conn.execute("SELECT json FROM manifest WHERE id='pack'").fetchone()
        if not row:
            raise ValueError(f"{path}: not a Vantage pack")
        self.manifest = json.loads(row[0])

    def places(self) -> list[dict]:
        cur = self.conn.execute(
            "SELECT id,kind,name,name_local,lat,lon,ele,pop,rank,wikidata,source,desc FROM places")
        cols = [c[0] for c in cur.description]
        return [dict(zip(cols, r)) for r in cur]

    def terrain(self, center_lat: float, center_lon: float,
                fine_radius_km: float = 28.0,
                coarse_radius_km: Optional[float] = None) -> Terrain:
        coarse_radius_km = coarse_radius_km or float(self.manifest.get("radius_km", 80))
        fine_bbox = _bbox(center_lat, center_lon, fine_radius_km)
        coarse_bbox = _bbox(center_lat, center_lon, coarse_radius_km)
        fine = self._assemble(0, fine_bbox)
        coarse = self._assemble(1, coarse_bbox)
        return Terrain(fine=fine, coarse=coarse)

    def _assemble(self, z: int, bbox: tuple[float, float, float, float]) -> Optional[Grid]:
        x0, y0, x1, y1, lat_n, lon_w = _grid_for_extent(*bbox, z)
        extent = self.conn.execute(
            "SELECT MIN(x),MAX(x),MIN(y),MAX(y) FROM dem WHERE z=?", (z,)).fetchone()
        if not extent or extent[0] is None:
            return None
        x0, x1 = max(x0, extent[0]), min(x1, extent[1] + 1)
        y0, y1 = max(y0, extent[2]), min(y1, extent[3] + 1)
        if x0 >= x1 or y0 >= y1:
            return None
        lat_n = 90.0 - y0 / (TILES_PER_DEGREE / (4 ** z))
        step = tile_origin(z)
        lon_w = x0 * step - 180.0
        w = (x1 - x0) * TILE_SIZE
        h = (y1 - y0) * TILE_SIZE
        data = np.full((h, w), -32768, dtype=np.int16)
        rows = self.conn.execute(
            "SELECT x,y,w,h,data FROM dem WHERE z=? AND x>=? AND x<? AND y>=? AND y<?",
            (z, x0, x1, y0, y1)).fetchall()
        for gx, gy, tw, th, blob in rows:
            tile = decompress(blob, tw, th)
            _blit(data, tile, (gy - y0) * TILE_SIZE, (gx - x0) * TILE_SIZE)
        if data.size == 0:
            return None
        arcsec = 1.0 * (4 ** z)
        d = arcsec / 3600.0
        # Crop to the requested radius so the silhouette cannot march into
        # replicated tiles outside the pack.
        row0 = int(math.floor((lat_n - bbox[2]) / d))
        row1 = int(math.ceil((lat_n - bbox[0]) / d))
        col0 = int(math.floor((bbox[1] - lon_w) / d))
        col1 = int(math.ceil((bbox[3] - lon_w) / d))
        row0 = max(0, min(row0, h - 1))
        row1 = max(row0 + 1, min(row1, h))
        col0 = max(0, min(col0, w - 1))
        col1 = max(col0 + 1, min(col1, w))
        data = np.ascontiguousarray(data[row0:row1, col0:col1])
        return Grid(data=data, lat_north=lat_n - row0 * d,
                    lon_west=lon_w + col0 * d, dlat=d, dlon=d)


def _bbox(lat: float, lon: float, radius_km: float) -> tuple[float, float, float, float]:
    dlat = radius_km / 111.0
    dlon = radius_km / max(1.0, 111.0 * math.cos(math.radians(lat)))
    return lat - dlat, lon - dlon, lat + dlat, lon + dlon
