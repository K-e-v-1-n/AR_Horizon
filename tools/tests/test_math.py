import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np
import pytest

from vantage_tools import geo
from vantage_tools.grid import Grid, Terrain, compute_silhouette, visible
from vantage_tools.pack import PackReader


def test_gornergrat_matterhorn():
    lat1, lon1 = 45.98331, 7.78470
    lat2, lon2 = 45.97639, 7.65861
    d = geo.distance_m(lat1, lon1, lat2, lon2)
    bearing = math.degrees(geo.bearing_rad(lat1, lon1, lat2, lon2))
    assert d == pytest.approx(9880, abs=120)
    assert bearing == pytest.approx(265.4, abs=0.6)
    assert geo.curvature_drop_m(50_000) == pytest.approx(170.7, abs=2)


def test_destination_roundtrip():
    lat, lon = 45.98, 7.78
    for az in (0, 45, 180, 300):
        for d in (1_000, 25_000, 90_000):
            lat2, lon2 = geo.destination(lat, lon, math.radians(az), d)
            assert geo.distance_m(lat, lon, lat2, lon2) == pytest.approx(d, rel=0.002)
            got = math.degrees(geo.bearing_rad(lat, lon, lat2, lon2)) % 360
            assert abs((got - az + 180) % 360 - 180) < 0.3


def test_flat_horizon_curvature():
    lat, lon = 45.0, 7.0
    n = 400
    data = np.full((n, n), 500, dtype=np.int16)
    g = Grid(data=data, lat_north=45.05, lon_west=6.94, dlat=1 / 3600, dlon=1 / 3600)
    sil = compute_silhouette(Terrain(fine=g), lat, lon, 1.7, bins=720)
    finite = sil.elev[np.isfinite(sil.elev)]
    assert len(finite) > 0
    assert finite.max() < 0
    assert finite.max() > -math.radians(0.3)


def test_ridge_hides_village_but_not_peak():
    lat, lon = 45.0, 7.0
    n = 600
    data = np.zeros((n, n), dtype=np.int16)
    g = Grid(data=data, lat_north=45.06, lon_west=6.94, dlat=1 / 3600, dlon=1 / 3600)
    # 1000 m plateau at 4–6 km east.
    for r in range(60):
        for c in range(60):
            row = int((45.06 - (45.004 - r / 3600)) * 3600)
            col = int(((7.045 + c / 3600) - 6.94) * 3600)
            g.data[row, col] = 1000
    sil = compute_silhouette(Terrain(fine=g), lat, lon, 1.7, bins=3600)
    peak_vis, az, elev, has = visible(45.0, 7.05, 2200, lat, lon, 1.7, sil)
    town_vis, _, _, _ = visible(45.0, 7.09, 100, lat, lon, 1.7, sil)
    assert math.degrees(az) == pytest.approx(90, abs=1)
    assert peak_vis and has
    assert not town_vis
