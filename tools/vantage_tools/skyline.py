"""Synthetic camera + skyline extraction, used to validate the lock without a phone.

The extractor mirrors android/.../SkylineSampler.kt: a 160×120 luma image, a
sky reference from the top rows, a dropping edge, and weights from contrast.
The fit itself is the same C++ `vt_fit_skyline` the device calls.
"""
from __future__ import annotations

import ctypes
import math
from pathlib import Path

import numpy as np
from PIL import Image

from .grid import Silhouette

COLS = 160
ROWS = 120


def profile_at(profile: np.ndarray, az_rad: float | np.ndarray) -> np.ndarray:
    a = np.asarray(az_rad) % (2 * math.pi)
    idx = np.floor(a / (2 * math.pi) * profile.size).astype(np.int64)
    return profile[np.clip(idx, 0, profile.size - 1)]


def make_synthetic_frame(
    sil: Silhouette,
    true_heading_deg: float,
    pitch_deg: float,
    hfov_deg: float = 70.0,
    width: int = 640,
    height: int = 480,
    seed: int = 7,
    trees: bool = True,
) -> tuple[Image.Image, dict]:
    rng = np.random.default_rng(seed)
    fx = (width * 0.5) / math.tan(math.radians(hfov_deg) * 0.5)
    fy = fx
    cx, cy = width * 0.5, height * 0.5
    xs = np.arange(width, dtype=np.float64)[None, :] + 0.5
    ys = np.arange(height, dtype=np.float64)[:, None] + 0.5
    az = math.radians(true_heading_deg) + np.arctan((xs - cx) / fx)
    elev = profile_at(sil.elev, az)
    horizon = cy - fy * np.tan(elev - math.radians(pitch_deg))

    depth = np.clip((ys - horizon) / np.maximum(1.0, height - horizon), 0, 1)
    sky_t = ys / height
    sky_rgb = np.stack([
        112 + 88 * sky_t ** 0.7,
        128 + 78 * sky_t ** 0.7,
        148 + 60 * sky_t ** 0.7,
    ], axis=-1)
    terrain_rgb = np.stack([
        56 - 38 * depth,
        70 - 46 * depth,
        58 - 38 * depth,
    ], axis=-1)
    haze = np.exp(-np.abs(ys - horizon) / 11.0) * 34
    terrain_rgb = terrain_rgb + haze[..., None]

    mask = ys >= horizon
    rgb = np.where(mask[..., None], terrain_rgb, sky_rgb)

    # Low-contrast clouds in the sky, so the fit is not fed a perfect gradient.
    cloud = (np.exp(-((xs - width * 0.3) / (width * 0.16)) ** 2
                    - ((ys - height * 0.24) / (height * 0.07)) ** 2) * 16)
    cloud += (np.exp(-((xs - width * 0.72) / (width * 0.22)) ** 2
                     - ((ys - height * 0.4) / (height * 0.05)) ** 2) * 10)
    rgb = rgb + cloud[..., None] * (~mask[..., None])

    # Trees: dark blobs above the horizon in a few columns.
    if trees:
        for _ in range(18):
            tcx = int(rng.integers(8, width - 8))
            tw = int(rng.integers(4, 13))
            th = int(rng.integers(5, 18))
            top = int(max(0, horizon[0, tcx] - th))
            bot = int(min(height, horizon[0, tcx] + th))
            x0, x1 = max(0, tcx - tw // 2), min(width, tcx + tw // 2 + 1)
            rgb[top:bot, x0:x1, 0] *= 0.55
            rgb[top:bot, x0:x1, 1] *= 0.68
            rgb[top:bot, x0:x1, 2] *= 0.58

    rgb += rng.normal(0, 4.0, rgb.shape)
    # Vignette.
    vx = (xs - cx) / width
    vy = (ys - cy) / height
    vignette = 1.0 - 0.18 * (vx ** 2 + vy ** 2)
    rgb *= vignette[..., None]
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    return Image.fromarray(rgb, "RGB"), {
        "fx": fx, "fy": fy, "cx": cx, "cy": cy,
        "width": width, "height": height,
        "true_heading": true_heading_deg, "pitch": pitch_deg, "hfov": hfov_deg,
    }


def extract_boundary(
    frame: Image.Image,
    intr: dict,
    reported_heading_deg: float,
    reported_pitch_deg: float,
) -> dict:
    small = frame.convert("L").resize((COLS, ROWS), Image.BILINEAR)
    luma = np.asarray(small, dtype=np.float64)
    fx, fy, cx, cy = intr["fx"], intr["fy"], intr["cx"], intr["cy"]
    az_rel, elev_rel, weight, rows, cols = [], [], [], [], []
    for c in range(COLS):
        sky_ref = float(np.median(np.sort(luma[0:14, c])))
        if sky_ref < 48:
            continue
        found = -1
        contrast = 0.0
        for r in range(12, ROWS - 4):
            v = luma[r, c]
            v1 = luma[r + 1, c]
            v2 = luma[r + 2, c]
            drop = sky_ref - v
            if drop > 22 and v < sky_ref * 0.72 and v1 < sky_ref * 0.85 and v2 < sky_ref * 0.9:
                found = r
                contrast = drop
                break
        if found < 0 or contrast < 24:
            continue
        img_x = (c + 0.5) * intr["width"] / COLS
        img_y = (found + 0.5) * intr["height"] / ROWS
        ar = math.atan((img_x - cx) / fx)
        er = math.atan((cy - img_y) / fy)
        az_rel.append(ar)
        elev_rel.append(er)
        weight.append(min(1.0, max(0.15, contrast / 255.0)))
        rows.append(found)
        cols.append(c)
    if len(az_rel) < 24:
        return {"ok": False, "count": len(az_rel)}
    az_rad = math.radians(reported_heading_deg) + np.asarray(az_rel)
    el_rad = math.radians(reported_pitch_deg) + np.asarray(elev_rel)
    span = math.degrees(float(np.max(az_rel) - np.min(az_rel)))
    return {
        "ok": True,
        "az": az_rad.astype(np.float32),
        "el": el_rad.astype(np.float32),
        "w": np.asarray(weight, dtype=np.float32),
        "count": len(az_rel),
        "span": span,
        "rows": np.asarray(rows),
        "cols": np.asarray(cols),
        "small": np.asarray(small),
    }


def fit_core(profile: np.ndarray, obs: dict, dll: Path,
             max_offset_deg: float = 12.0) -> dict:
    lib = ctypes.CDLL(str(dll))
    f32p = ctypes.POINTER(ctypes.c_float)
    f64p = ctypes.POINTER(ctypes.c_double)
    fn = lib.vt_fit_skyline
    fn.argtypes = [f32p, ctypes.c_int, f32p, f32p, f32p, ctypes.c_int,
                   ctypes.c_double, ctypes.c_double, f64p, f64p, f64p]
    fn.restype = ctypes.c_int
    off = ctypes.c_double()
    score = ctypes.c_double()
    vertical = ctypes.c_double()
    syn = np.ascontiguousarray(profile, dtype=np.float32)
    az = np.ascontiguousarray(obs["az"], dtype=np.float32)
    el = np.ascontiguousarray(obs["el"], dtype=np.float32)
    wt = np.ascontiguousarray(obs["w"], dtype=np.float32)
    rc = fn(syn.ctypes.data_as(f32p), syn.size,
            az.ctypes.data_as(f32p), el.ctypes.data_as(f32p), wt.ctypes.data_as(f32p),
            az.size, math.radians(max_offset_deg), math.radians(0.05),
            ctypes.byref(off), ctypes.byref(score), ctypes.byref(vertical))
    return {
        "ok": rc == 0,
        "offset_deg": math.degrees(off.value),
        "score": score.value,
        "vertical_deg": math.degrees(vertical.value),
    }


def correlation_curve(profile: np.ndarray, obs: dict,
                      offsets_deg: np.ndarray) -> np.ndarray:
    out = []
    for off in offsets_deg:
        az = obs["az"] + math.radians(float(off))
        idx = np.floor((az % (2 * math.pi)) / (2 * math.pi) * profile.size).astype(np.int64)
        pred = profile[np.clip(idx, 0, profile.size - 1)].astype(np.float64)
        y = obs["el"].astype(np.float64)
        w = obs["w"].astype(np.float64)
        sw = w.sum()
        mx = (w * pred).sum() / sw
        my = (w * y).sum() / sw
        cov = (w * (pred - mx) * (y - my)).sum()
        vx = (w * (pred - mx) ** 2).sum()
        vy = (w * (y - my) ** 2).sum()
        out.append(cov / math.sqrt(max(1e-12, vx * vy)))
    return np.asarray(out)
