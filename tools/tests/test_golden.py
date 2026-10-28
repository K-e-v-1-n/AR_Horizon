import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.pack import PackReader  # noqa: E402

PACK = ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite"
META = ROOT / "docs/goldens/gornergrat.json"


def test_golden_pack_covers_gornergrat():
    if not PACK.exists():
        return
    pack = PackReader(PACK)
    m = pack.manifest
    lat, lon = 45.98331, 7.78470
    assert m["format"] == "vantage-pack/1"
    assert m["bounds"][0] <= lat <= m["bounds"][2]
    assert m["bounds"][1] <= lon <= m["bounds"][3]
    assert m["counts"]["places"] > 1000
    terrain = pack.terrain(lat, lon, 30, 80)
    assert terrain.fine is not None
    assert 3000 < terrain.sample(lat, lon) < 3300


def test_golden_labels():
    if not META.exists() or not PACK.exists():
        return
    meta = json.loads(META.read_text(encoding="utf-8"))
    labels = {l["name"]: l for l in meta["labels"]}
    matterhorn = next(v for k, v in labels.items() if k.startswith("Matterhorn"))
    assert matterhorn["az_deg"] == 265.6 or abs(matterhorn["az_deg"] - 265.6) < 0.6
    assert matterhorn["elev_deg"] > 7.4
    assert 9.5 < matterhorn["dist_m"] / 1000 < 10.2
    assert not matterhorn["hidden"]
    assert any(l["kind"] == "peak" and not l["hidden"] for l in meta["labels"])


def test_manifest_attribution():
    if not PACK.exists():
        return
    m = PackReader(PACK).manifest
    assert "Copernicus" in m["dem_attribution"]
    assert "OpenStreetMap" in m["places_attribution"]
