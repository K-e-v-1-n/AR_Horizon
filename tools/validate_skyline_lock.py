#!/usr/bin/env python
"""Validate the skyline lock without a phone or camera.

Builds synthetic camera frames from the same DEM horizon, runs the same
boundary extraction as the Android sampler, and feeds the observations into the
C++ `vt_fit_skyline` used by the app. It recovers a deliberately injected yaw
error and writes a visual proof.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from vantage_tools.grid import compute_silhouette  # noqa: E402
from vantage_tools.pack import PackReader  # noqa: E402
from vantage_tools.skyline import (  # noqa: E402
    correlation_curve, extract_boundary, fit_core, make_synthetic_frame,
)

DLL = ROOT / "core" / "build" / "vantage_core.dll"
PACK = ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite"
FONT_DIR = Path("C:/Windows/Fonts")


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(str(FONT_DIR / name), size)
    except Exception:
        return ImageFont.load_default()


def load_profile():
    pack = PackReader(PACK)
    terrain = pack.terrain(45.98331, 7.78470, 30.0, 80.0)
    sil = compute_silhouette(terrain, 45.98331, 7.78470, 1.7, bins=3600, max_range=80_000)
    return sil


def run_case(sil, true_heading: float, pitch: float, error_deg: float, seed: int):
    frame, intr = make_synthetic_frame(
        sil, true_heading, pitch, hfov_deg=70.0, width=640, height=480,
        seed=seed, trees=True,
    )
    reported = true_heading - error_deg
    obs = extract_boundary(frame, intr, reported, pitch)
    if not obs.get("ok"):
        return frame, intr, obs, None
    fit = fit_core(sil.elev, obs, DLL)
    return frame, intr, obs, fit


def draw_curve(draw: ImageDraw.ImageDraw, box, offsets, scores, fit_offset):
    x0, y0, x1, y1 = box
    draw.rounded_rectangle(box, radius=18, fill=(11, 19, 24), outline=(255, 255, 255, 24))
    lo, hi = -12, 12
    grid = font("consola.ttf", 16)
    label = font("consola.ttf", 18)
    for v in (-12, -6, 0, 6, 12):
        x = x0 + 34 + (v - lo) / (hi - lo) * (x1 - x0 - 54)
        draw.line([(x, y0 + 38), (x, y1 - 34)], fill=(255, 255, 255, 18), width=1)
        draw.text((x - 8, y1 - 28), str(v), font=grid, fill=(120, 138, 148))
    for v in (0.0, 0.5, 1.0):
        y = y1 - 34 - v * (y1 - y0 - 76)
        draw.text((x0 + 8, y - 8), f"{v:.2f}", font=grid, fill=(120, 138, 148))
    pts = []
    for o, s in zip(offsets, scores):
        x = x0 + 34 + (o - lo) / (hi - lo) * (x1 - x0 - 54)
        y = y1 - 34 - max(0.0, min(1.0, s)) * (y1 - y0 - 76)
        pts.append((x, y))
    if len(pts) > 1:
        draw.line(pts, fill=(126, 198, 231), width=3)
    x = x0 + 34 + (fit_offset - lo) / (hi - lo) * (x1 - x0 - 54)
    draw.line([(x, y0 + 38), (x, y1 - 34)], fill=(243, 184, 92), width=2)
    draw.text((x + 9, y0 + 46), f"fit {fit_offset:+.2f}°", font=label,
              fill=(243, 184, 92))
    draw.text((x0 + 20, y0 + 12), "NORMALISED CORRELATION", font=grid,
              fill=(147, 166, 178))


def proof_image(frame: Image.Image, intr: dict, obs: dict, fit: dict,
                true_heading: float, reported: float, expected: float,
                out: Path) -> None:
    W, H = 1880, 1180
    img = Image.new("RGB", (W, H), (6, 11, 16))
    draw = ImageDraw.Draw(img, "RGBA")

    # Header
    draw.text((56, 46), "VANTAGE / SKYLINE LOCK", font=font("segoeuib.ttf", 22),
              fill=(243, 184, 92))
    draw.text((56, 82), "Synthetic field test — no phone, no camera",
              font=font("segoeuil.ttf", 42), fill=(237, 243, 247))
    draw.text((56, 136),
              "The same DEM horizon, the same 160×120 boundary sampler and the same C++ fit the app runs.",
              font=font("segoeui.ttf", 17), fill=(147, 166, 178))

    # Synthetic frame with detected edge.
    photo = frame.resize((960, 720), Image.LANCZOS)
    img.paste(photo, (56, 192))
    d2 = ImageDraw.Draw(img, "RGBA")
    for c, r in zip(obs["cols"], obs["rows"]):
        x = 56 + (c + 0.5) * 960 / 160
        y = 192 + (r + 0.5) * 720 / 120
        d2.ellipse((x - 2.2, y - 2.2, x + 2.2, y + 2.2), fill=(243, 184, 92))
    # Frame label
    d2.rounded_rectangle((76, 212, 330, 246), radius=17, fill=(6, 12, 17, 200))
    d2.text((92, 219), "SYNTHETIC CAMERA · 70° FOV", font=font("consola.ttf", 14),
            fill=(237, 243, 247))
    d2.rounded_rectangle((56, 872, 1016, 912), radius=12, fill=(6, 12, 17, 205))
    d2.text((76, 880),
            f"true {true_heading:.1f}°   reported {reported:.1f}°   "
            f"injected {true_heading - reported:+.2f}°",
            font=font("consola.ttf", 17), fill=(190, 205, 214))
    # Extracted boundary box
    d2.text((56, 930), "ORANGE: EXTRACTED SKY / TERRAIN BOUNDARY", font=font("consola.ttf", 15),
            fill=(243, 184, 92))

    # Results panel
    x0 = 1060
    d2.rounded_rectangle((x0, 192, W - 56, 680), radius=22, fill=(10, 17, 22),
                         outline=(255, 255, 255, 22))
    d2.text((x0 + 28, 216), "RECOVERY", font=font("consola.ttf", 15), fill=(147, 166, 178))
    d2.text((x0 + 28, 250), f"{fit['offset_deg']:+.2f}°", font=font("segoeuil.ttf", 78),
            fill=(237, 243, 247))
    d2.text((x0 + 30, 344), f"expected {expected:+.2f}°   error "
                            f"{abs(fit['offset_deg'] - expected):.2f}°",
            font=font("segoeui.ttf", 17), fill=(147, 166, 178))
    d2.text((x0 + 30, 386), f"score {fit['score']:.3f}   samples {obs['count']}   "
                            f"span {obs['span']:.1f}°",
            font=font("consola.ttf", 15), fill=(111, 197, 155))
    d2.text((x0 + 30, 420), f"vertical residual {fit['vertical_deg']:+.2f}°",
            font=font("consola.ttf", 15), fill=(147, 166, 178))
    verdict = "PASS" if abs(fit["offset_deg"] - expected) < 0.35 and fit["score"] > 0.65 else "CHECK"
    d2.rounded_rectangle((x0 + 28, 474, x0 + 152, 518), radius=22,
                         fill=(111, 197, 155, 28), outline=(111, 197, 155, 90))
    d2.text((x0 + 50, 483), verdict, font=font("consola.ttf", 20), fill=(111, 197, 155))
    d2.text((x0 + 30, 548), "No magnetometer, no ARCore, no phone.", font=font("segoeui.ttf", 15),
            fill=(147, 166, 178))
    d2.text((x0 + 30, 576), "If this passes, the lock maths and image sampler are sound;",
            font=font("segoeui.ttf", 14), fill=(95, 113, 124))
    d2.text((x0 + 30, 598), "only camera exposure and hand motion remain untested.",
            font=font("segoeui.ttf", 14), fill=(95, 113, 124))

    offsets = np.arange(-12, 12.0001, 0.05)
    scores = correlation_curve(load_profile().elev, obs, offsets)
    draw_curve(d2, (x0, 708, W - 56, 1010), offsets, scores, fit["offset_deg"])

    d2.text((56, 1038),
            "Gornergrat · Copernicus GLO-30 30 m surface model · places © OpenStreetMap contributors (ODbL)",
            font=font("consola.ttf", 14), fill=(95, 113, 124))
    d2.text((56, 1064),
            "Synthetic frame generated from the pack; boundary extraction mirrors SkylineSampler.kt.",
            font=font("consola.ttf", 14), fill=(95, 113, 124))

    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, "PNG", optimize=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=ROOT / "docs/goldens/skyline-lock.png")
    ap.add_argument("--json", type=Path, default=ROOT / "docs/goldens/skyline-lock.json")
    args = ap.parse_args()
    if not DLL.exists():
        raise SystemExit("build core/build/vantage_core.dll first (core/build_core_dll.bat)")

    sil = load_profile()
    true_heading, pitch = 262.0, 4.0
    cases = [
        (4.0, 11, "main"),
        (-3.2, 12, ""),
        (0.0, 13, ""),
        (7.1, 14, ""),
        (-7.8, 15, ""),
    ]
    results = []
    proof = None
    for error_deg, seed, tag in cases:
        frame, intr, obs, fit = run_case(sil, true_heading, pitch, error_deg, seed)
        if obs is None or fit is None:
            print(f"skip error {error_deg:+.1f}: extraction failed ({obs})")
            continue
        expected = true_heading - (true_heading - error_deg)
        results.append({
            "injected_error_deg": error_deg,
            "expected_offset_deg": expected,
            "recovered_offset_deg": round(fit["offset_deg"], 4),
            "error_deg": round(abs(fit["offset_deg"] - expected), 4),
            "score": round(fit["score"], 4),
            "samples": obs["count"],
            "span_deg": round(obs["span"], 2),
            "vertical_residual_deg": round(fit["vertical_deg"], 3),
        })
        print(f"injected {error_deg:+.1f}° -> recovered {fit['offset_deg']:+.2f}° "
              f"score {fit['score']:.3f} samples {obs['count']}")
        if tag == "main":
            proof = (frame, intr, obs, fit)

    if not results:
        raise SystemExit("no cases passed")
    passed = sum(1 for r in results
                 if abs(r["recovered_offset_deg"] - r["expected_offset_deg"]) < 0.35
                 and r["score"] > 0.65)
    print(f"{passed}/{len(results)} cases within 0.35° and score > 0.65")

    if proof:
        frame, intr, obs, fit = proof
        proof_image(frame, intr, obs, fit, true_heading, true_heading - 4.0, 4.0, args.out)
        print(f"proof {args.out}")
    summary = {
        "method": "synthetic camera frames from the same DEM silhouette; boundary extraction mirrors SkylineSampler.kt; fit is C++ vt_fit_skyline",
        "viewpoint": {"lat": 45.98331, "lon": 7.78470, "heading": true_heading, "pitch": pitch, "hfov": 70},
        "dll": str(DLL.relative_to(ROOT)),
        "results": results,
        "passed": passed,
        "total": len(results),
    }
    args.json.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"json {args.json}")
    if passed < len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
