import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np

from vantage_tools.grid import TILE_SIZE, Grid
from vantage_tools.index import IndexEntry, read as read_index, write as write_index
from vantage_tools.pack import PackWriter
from vantage_tools.places import _parse_ele


def test_index_roundtrip(tmp_path):
    path = tmp_path / "world.idx"
    entries = [
        IndexEntry(45.98, 7.78, 4478, 0, 0, "Matterhorn"),
        IndexEntry(46.2, 6.1, 0, 200_000, 1, "Genève"),
    ]
    assert write_index(path, entries) == 2
    got = read_index(path)
    assert got[0].name == "Matterhorn"
    assert got[1].pop == 200_000
    assert got[1].kind == 1
    assert got[0].ele == 4478.0


def test_pack_tile_roundtrip(tmp_path):
    # One z1 group (4×4 z0 tiles), flat 900 m.
    gx0, gy0 = 600, 180
    tiles = {}
    for dy in range(4):
        for dx in range(4):
            tiles[(gx0 + dx, gy0 + dy)] = np.full((TILE_SIZE, TILE_SIZE), 900, dtype=np.int16)
            tiles[(gx0 + dx, gy0 + dy)][10, 10] = 1200

    writer = PackWriter(tmp_path / "test.sqlite")
    for (gx, gy), tile in tiles.items():
        writer.add_tile(0, gx, gy, tile)
    big = np.zeros((4 * TILE_SIZE, 4 * TILE_SIZE), dtype=np.float32)
    for (gx, gy), tile in tiles.items():
        big[(gy - gy0) * TILE_SIZE:(gy - gy0 + 1) * TILE_SIZE,
            (gx - gx0) * TILE_SIZE:(gx - gx0 + 1) * TILE_SIZE] = tile
    coarse = big.reshape(TILE_SIZE, 4, TILE_SIZE, 4).mean(axis=(1, 3))
    writer.add_tile(1, gx0 // 4, gy0 // 4, np.rint(coarse).astype(np.int16))
    writer.set_manifest({"format": "vantage-pack/1", "id": "test",
                         "name": "Test", "center": [0, 0], "radius_km": 10,
                         "bounds": [-1, -1, 1, 1], "counts": {"places": 0}})
    writer.close()

    from vantage_tools.pack import PackReader

    pack = PackReader(tmp_path / "test.sqlite")
    lat = 90 - (gy0 * TILE_SIZE + 5) / 3600
    lon = (gx0 * TILE_SIZE + 5) / 3600 - 180
    terrain = pack.terrain(lat, lon, fine_radius_km=4, coarse_radius_km=4)
    assert terrain.fine is not None
    assert terrain.sample(lat, lon) == 900
    assert terrain.coarse is not None


def test_elevation_text_parser():
    assert _parse_ele("4,478 m") == 4478
    assert _parse_ele("4478.5") == 4478.5
    assert _parse_ele("4478,5 m") == 4478.5
    assert _parse_ele("1,234.0") == 1234.0
    assert np.isnan(_parse_ele(None))
    assert np.isnan(_parse_ele("no number"))


def test_grid_bilinear():
    data = np.array([[0, 100], [200, 300]], dtype=np.int16)
    g = Grid(data=data, lat_north=2.0, lon_west=0.0, dlat=1.0, dlon=1.0)
    # Row/column centres sit at 1.5/0.5 and 0.5/1.5.
    assert g.sample(1.5, 0.5) == 0
    assert g.sample(0.5, 0.5) == 200
    assert g.sample(1.0, 1.0) == 150
    assert np.isnan(g.sample(3.0, 0.0))
