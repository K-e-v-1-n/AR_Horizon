import math
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.grid import compute_silhouette
from vantage_tools.pack import PackReader
from vantage_tools.skyline import extract_boundary, fit_core, make_synthetic_frame

PACK = ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite"
DLL = ROOT / "core/build/vantage_core.dll"


def test_synthetic_skyline_lock():
    if not PACK.exists():
        pytest.skip("pack missing")
    if not DLL.exists():
        pytest.skip("build core/build/vantage_core.dll first")
    pack = PackReader(PACK)
    terrain = pack.terrain(45.98331, 7.78470, 30.0, 80.0)
    sil = compute_silhouette(terrain, 45.98331, 7.78470, 1.7, 3600, 80_000)

    for error, seed in ((4.0, 11), (-3.2, 12), (7.1, 14)):
        frame, intr = make_synthetic_frame(
            sil, 262.0, 4.0, hfov_deg=70.0, width=640, height=480, seed=seed,
        )
        obs = extract_boundary(frame, intr, 262.0 - error, 4.0)
        assert obs["ok"], "synthetic boundary extraction failed"
        assert obs["count"] >= 100
        assert obs["span"] > 50
        fit = fit_core(sil.elev, obs, DLL)
        assert fit["ok"]
        assert fit["score"] > 0.8
        assert abs(fit["offset_deg"] - error) < 0.35
