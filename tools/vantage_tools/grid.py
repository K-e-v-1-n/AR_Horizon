"""Regular lat/lon terrain grids and the silhouette the app and renderer share."""
from __future__ import annotations

import math
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np

from . import geo

TILE_SIZE = 600          # cells per pack tile edge (1" level 0)
TILES_PER_DEGREE = 6     # 3600 arcsec / 600; keeps tiles aligned to 1° COGs
PACK_LEVELS = ((0, 1.0 / 3600.0), (1, 4.0 / 3600.0))


def distance_schedule(max_range: float) -> np.ndarray:
    ds = []
    d = 25.0
    while d < max_range:
        ds.append(d)
        if d < 500:
            step = 15.0
        elif d < 2000:
            step = 60.0
        elif d < 8000:
            step = 150.0
        elif d < 25000:
            step = 300.0
        elif d < 60000:
            step = 600.0
        else:
            step = 1000.0
        d += step
    if not ds or ds[-1] < max_range:
        ds.append(max_range)
    return np.asarray(ds, dtype=np.float64)


@dataclass
class Grid:
    data: np.ndarray            # int16, row 0 = north
    lat_north: float
    lon_west: float
    dlat: float
    dlon: float

    def __post_init__(self) -> None:
        if self.data.dtype != np.int16:
            self.data = self.data.astype(np.int16, copy=False)

    @property
    def height(self) -> int:
        return int(self.data.shape[0])

    @property
    def width(self) -> int:
        return int(self.data.shape[1])

    @property
    def lat_south(self) -> float:
        return self.lat_north - self.height * self.dlat

    @property
    def lon_east(self) -> float:
        return self.lon_west + self.width * self.dlon

    def contains(self, lat: float, lon: float) -> bool:
        return (self.lat_south - 1e-12 <= lat <= self.lat_north + 1e-12 and
                self.lon_west - 1e-12 <= lon <= self.lon_east + 1e-12)

    def sample(self, lat: float, lon: float) -> float:
        return float(self.sample_many(np.array([lat]), np.array([lon]))[0])

    def sample_many(self, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
        lats = np.asarray(lats, dtype=np.float64)
        lons = np.asarray(lons, dtype=np.float64)
        row = (self.lat_north - lats) / self.dlat - 0.5
        col = (lons - self.lon_west) / self.dlon - 0.5
        valid = ((row >= -0.5) & (col >= -0.5) &
                 (row <= self.height - 0.5) & (col <= self.width - 0.5))
        out = np.full(lats.shape, np.nan, dtype=np.float64)
        if not np.any(valid):
            return out

        r = np.clip(row[valid], 0.0, self.height - 1.0)
        c = np.clip(col[valid], 0.0, self.width - 1.0)
        r0 = np.floor(r).astype(np.int32)
        c0 = np.floor(c).astype(np.int32)
        r1 = np.minimum(r0 + 1, self.height - 1)
        c1 = np.minimum(c0 + 1, self.width - 1)
        fr = r - r0
        fc = c - c0
        d = self.data
        h00 = d[r0, c0]
        h01 = d[r0, c1]
        h10 = d[r1, c0]
        h11 = d[r1, c1]
        a = h00 + (h01 - h00) * fc
        b = h10 + (h11 - h10) * fc
        out[valid] = a + (b - a) * fr
        return out

    def max_height(self) -> float:
        return float(self.data.max()) if self.data.size else float("nan")


@dataclass
class Terrain:
    fine: Optional[Grid] = None
    coarse: Optional[Grid] = None

    def sample(self, lat: float, lon: float) -> float:
        if self.fine is not None and self.fine.contains(lat, lon):
            return self.fine.sample(lat, lon)
        if self.coarse is not None and self.coarse.contains(lat, lon):
            return self.coarse.sample(lat, lon)
        return float("nan")

    def sample_many(self, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
        out = np.full(np.shape(lats), np.nan, dtype=np.float64)
        done = np.zeros(np.shape(lats), dtype=bool)
        if self.fine is not None:
            inside = ((lats <= self.fine.lat_north) & (lats >= self.fine.lat_south) &
                      (lons >= self.fine.lon_west) & (lons <= self.fine.lon_east))
            if np.any(inside):
                out[inside] = self.fine.sample_many(lats[inside], lons[inside])
                done |= inside
        if self.coarse is not None:
            inside = ((lats <= self.coarse.lat_north) & (lats >= self.coarse.lat_south) &
                      (lons >= self.coarse.lon_west) & (lons <= self.coarse.lon_east))
            inside &= ~done
            if np.any(inside):
                out[inside] = self.coarse.sample_many(lats[inside], lons[inside])
        return out

    def max_height(self) -> float:
        m = -10000.0
        for g in (self.fine, self.coarse):
            if g is not None:
                m = max(m, g.max_height())
        return m

    def range_m(self, lat: float, lon: float) -> float:
        g = self.coarse if self.coarse is not None else self.fine
        if g is None or not g.contains(lat, lon):
            return 0.0
        mlat = geo.metres_per_degree_lat(lat)
        mlon = geo.metres_per_degree_lon(lat)
        return max(0.0, min((g.lat_north - lat) * mlat, (lat - g.lat_south) * mlat,
                            (lon - g.lon_west) * mlon, (g.lon_east - lon) * mlon))


def write_vtgrid(path: Path, grid: Grid) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(b"VTG1")
        f.write(struct.pack("<iii", 0, grid.width, grid.height))
        f.write(struct.pack("<dddd", grid.lat_north, grid.lon_west, grid.dlat, grid.dlon))
        f.write(np.ascontiguousarray(grid.data, dtype="<i2").tobytes())


def read_vtgrid(path: Path) -> Grid:
    with Path(path).open("rb") as f:
        magic = f.read(4)
        if magic != b"VTG1":
            raise ValueError(f"{path}: not a VTG1 terrain grid")
        _level, w, h = struct.unpack("<iii", f.read(12))
        lat_north, lon_west, dlat, dlon = struct.unpack("<dddd", f.read(32))
        data = np.frombuffer(f.read(w * h * 2), dtype="<i2").reshape(h, w)
    return Grid(data=data, lat_north=lat_north, lon_west=lon_west, dlat=dlat, dlon=dlon)


@dataclass
class Silhouette:
    elev: np.ndarray   # radians, per bin
    dist: np.ndarray   # metres
    bins: int

    def elev_at(self, az_rad: float | np.ndarray) -> np.ndarray:
        idx = np.floor((np.asarray(az_rad) % (2 * np.pi)) / (2 * np.pi) * self.bins).astype(np.int64)
        return self.elev[np.clip(idx, 0, self.bins - 1)]

    def dist_at(self, az_rad: float | np.ndarray) -> np.ndarray:
        idx = np.floor((np.asarray(az_rad) % (2 * np.pi)) / (2 * np.pi) * self.bins).astype(np.int64)
        return self.dist[np.clip(idx, 0, self.bins - 1)]


def compute_silhouette(terrain: Terrain, lat: float, lon: float, eye_height: float,
                       bins: int = 3600, max_range: Optional[float] = None) -> Silhouette:
    """Apparent horizon per azimuth bin, with curvature and refraction.

    Vectorised over bins; the inner distance loop is the same schedule as the
    portable core so PNGs and device labels agree.
    """
    ground = terrain.sample(lat, lon)
    if not np.isfinite(ground):
        raise ValueError("observer is outside the terrain pack")
    eye = ground + max(eye_height, 0.2)
    rmax = min(max_range or terrain.range_m(lat, lon), terrain.range_m(lat, lon))
    if rmax < 100:
        raise ValueError("terrain pack too small around the observer")

    ds = distance_schedule(rmax)
    az = (np.arange(bins) + 0.5) * 2 * np.pi / bins
    cos_az, sin_az = np.cos(az)[:, None], np.sin(az)[:, None]
    lat_m = geo.metres_per_degree_lat(lat)
    lon_m = geo.metres_per_degree_lon(lat)
    inv2r = 1.0 / (2.0 * geo.EFFECTIVE_RADIUS_M)
    max_h = terrain.max_height()

    best_slope = np.full(bins, -np.inf, dtype=np.float64)
    best_d = np.full(bins, np.nan, dtype=np.float64)

    for d in ds:
        drop = d * d * inv2r
        # Highest possible apparent angle at this range (global max height).
        possible = (max_h - eye - drop) / d
        if np.all(np.isfinite(best_slope) & (best_slope >= possible)):
            break
        glat = lat + d * cos_az[:, 0] / lat_m
        glon = lon + d * sin_az[:, 0] / lon_m
        h = terrain.sample_many(glat, glon)
        slope = (h - eye - drop) / d
        better = np.isfinite(slope) & (slope > best_slope)
        best_slope[better] = slope[better]
        best_d[better] = d

    elev = np.full(bins, np.nan)
    ok = np.isfinite(best_slope)
    elev[ok] = np.arctan(best_slope[ok])
    dist = np.where(ok, best_d, np.nan)
    return Silhouette(elev=elev, dist=dist, bins=bins)


def visible(place_lat: float, place_lon: float, place_ele: float, observer_lat: float,
            observer_lon: float, eye_alt: float, sil: Silhouette) -> tuple[bool, float, float, bool]:
    """Returns (visible, az_rad, elev_rad, has_terrain) for a target."""
    d = geo.distance_m(observer_lat, observer_lon, place_lat, place_lon)
    az = geo.bearing_rad(observer_lat, observer_lon, place_lat, place_lon)
    elev = geo.elevation_angle(place_ele, eye_alt, d)
    s = float(sil.elev_at(az))
    if not math.isfinite(s):
        return True, az, elev, False
    return elev > s + math.radians(0.03), az, elev, True
