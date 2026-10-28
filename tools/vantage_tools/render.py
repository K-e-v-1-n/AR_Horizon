"""M0 panorama renderer: a still that proves the terrain, projection and label
solver before any AR code runs. Output is also the pack's visual golden."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from . import geo
from .grid import Silhouette, Terrain, compute_silhouette
from .pack import PackReader

FONT_DIR = Path("C:/Windows/Fonts")

PEAK = (255, 255, 255)
PEAK_DIM = (176, 184, 190)
TOWN = (244, 190, 92)
VILLAGE = (231, 178, 96)
WATER = (126, 198, 231)
LANDMARK = (186, 196, 202)
INK = (16, 22, 28)


def _font(name: str, size: int) -> ImageFont.FreeTypeFont:
    path = FONT_DIR / name
    try:
        return ImageFont.truetype(str(path), size)
    except Exception:
        return ImageFont.load_default()


@dataclass
class Label:
    text: str
    suffix: str
    x: float
    y: float
    color: tuple[int, int, int]
    hidden: bool
    italic: bool
    size: int
    score: float
    dist: float
    az: float
    elev: float
    kind: str
    w: float = 0
    h: float = 0


def _load_places(pack: PackReader) -> list[dict]:
    out = []
    for p in pack.places():
        try:
            p["ele"] = float(p["ele"])
        except (TypeError, ValueError):
            p["ele"] = -10000.0
        out.append(p)
    return out


def _lod(place: dict, dist_km: float) -> bool:
    kind = place["kind"]
    pop = int(place.get("pop") or 0)
    rank = float(place.get("rank") or 0)
    if dist_km <= 5:
        return True
    if dist_km <= 20:
        return kind != "hamlet"
    if dist_km <= 50:
        if kind in ("city", "town", "water", "landmark"):
            return True
        if kind == "peak":
            return rank > 0.42
        return False
    if kind == "city":
        return pop >= 10000
    if kind == "peak":
        return rank > 0.62
    if kind == "water":
        return rank > 0.5
    return False


def _project(az: float, elev: float, yaw: float, pitch: float, hfov: float,
             width: int, height: int) -> tuple[float, float, bool]:
    ce = math.cos(elev)
    vx, vy, vz = math.sin(az) * ce, math.cos(az) * ce, math.sin(elev)
    fx, fy, fz = (math.sin(yaw) * math.cos(pitch), math.cos(yaw) * math.cos(pitch),
                  math.sin(pitch))
    rx, ry, rz = math.cos(yaw), -math.sin(yaw), 0.0
    ux = -math.sin(yaw) * math.sin(pitch)
    uy = -math.cos(yaw) * math.sin(pitch)
    uz = math.cos(pitch)
    pz = vx * fx + vy * fy + vz * fz
    if pz <= 0.02:
        return -1e9, -1e9, False
    f = (width * 0.5) / math.tan(hfov * 0.5)
    sx = (vx * rx + vy * ry + vz * rz) / pz
    sy = (vx * ux + vy * uy + vz * uz) / pz
    return width * 0.5 + f * sx, height * 0.5 - f * sy, True


def _elev_to_y(elev: float, width: int, height: int, hfov: float, pitch: float) -> float:
    f = (width * 0.5) / math.tan(hfov * 0.5)
    # Pinhole vertical mapping through the camera axes is exact for roll 0:
    return height * 0.5 - f * math.tan(elev - pitch)


def _sky_image(width: int, height: int, pitch: float, hfov: float) -> Image.Image:
    f = (width * 0.5) / math.tan(hfov * 0.5)
    ys = np.arange(height, dtype=np.float32)[:, None]
    elev = pitch + np.arctan((height * 0.5 - ys) / f)
    t = np.clip(elev / np.deg2rad(40), 0, 1)[:, :, None]
    top = np.array([7, 19, 34], dtype=np.float32)
    mid = np.array([32, 67, 94], dtype=np.float32)
    horizon = np.array([198, 214, 221], dtype=np.float32)
    rgb = np.where(t < 0.26,
                   horizon + (mid - horizon) * (t / 0.26),
                   mid + (top - mid) * ((t - 0.26) / 0.74))
    img = np.repeat(rgb.astype(np.uint8), width, axis=1)
    return Image.fromarray(img, "RGB")


def _draw_terrain_layer(img: Image.Image, draw: ImageDraw.ImageDraw, sil: Silhouette,
                        yaw: float, pitch: float, hfov: float, width: int, height: int,
                        color: tuple[int, int, int], haze: float, max_dist: float,
                        ridge: bool = True) -> None:
    px = np.arange(width)
    f = (width * 0.5) / math.tan(hfov * 0.5)
    delta = np.arctan((px - width * 0.5) / f)
    az = (yaw + delta) % (2 * np.pi)
    elev = sil.elev_at(az)
    dist = sil.dist_at(az)
    ys = height * 0.5 - f * np.tan(elev - pitch)
    ys = np.nan_to_num(ys, nan=height + 10, posinf=height + 10, neginf=height + 10)
    ys = np.clip(ys, -height, height + 10).astype(int)

    # Per-column colour fades with distance into the sky haze.
    dnorm = np.clip(np.nan_to_num(dist, nan=max_dist) / max_dist, 0, 1)
    base = np.array(color, dtype=np.float32)
    sky = np.array([188, 209, 219], dtype=np.float32)
    fade = (dnorm ** 1.25) * haze
    rgb = base[None, :] * (1 - fade[:, None]) + sky[None, :] * fade[:, None]
    rgb *= (0.9 + 0.2 * (1 - dnorm))[:, None]

    arr = np.asarray(img).copy()
    for i in range(width):
        y = ys[i]
        if y < height:
            arr[max(0, y):, i] = rgb[i].astype(np.uint8)
    out = Image.fromarray(arr, "RGB")
    img.paste(out)
    if ridge:
        for i in range(width - 1):
            y0, y1 = int(ys[i]), int(ys[i + 1])
            if y0 < height and y1 < height:
                draw.line([(i, y0), (i + 1, y1)], fill=tuple(int(c) for c in (rgb[i] * 1.18).clip(0, 255)), width=2)


def _text_size(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont) -> tuple[int, int]:
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0], box[3] - box[1]


def _place_labels(pack: PackReader, terrain: Terrain, sil: Silhouette, lat: float, lon: float,
                  eye: float, yaw: float, pitch: float, hfov: float, width: int,
                  height: int, max_labels: int, scale: float) -> list[Label]:
    f = (width * 0.5) / math.tan(hfov * 0.5)
    labels: list[Label] = []
    for p in _load_places(pack):
        d = geo.distance_m(lat, lon, p["lat"], p["lon"])
        if d < 20 or d > 130000:
            continue
        if not _lod(p, d / 1000.0):
            continue
        az = geo.bearing_rad(lat, lon, p["lat"], p["lon"])
        elev = geo.elevation_angle(p["ele"], eye, d)
        s = float(sil.elev_at(az))
        has_terrain = math.isfinite(s)
        visible = (elev > s + math.radians(0.03)) if has_terrain else True
        if not visible and has_terrain:
            elev = s
            # hidden labels sit on the skyline but never above the camera frame
        x, y, in_front = _project(az, elev, yaw, pitch, hfov, width, height)
        if not in_front:
            continue
        if x < -160 or x > width + 160 or y < 60 or y > height - 90:
            continue
        if not visible and not has_terrain:
            continue

        kind = p["kind"]
        color = {"peak": PEAK, "city": TOWN, "town": TOWN, "village": VILLAGE,
                 "hamlet": VILLAGE, "water": WATER, "landmark": LANDMARK}.get(kind, LANDMARK)
        if not visible:
            color = tuple(int(c * 0.55 + 90 * 0.45) for c in color)
        italic = kind == "water"
        size = int((30 if kind == "peak" else 27) * scale)
        if not visible:
            size = int(size * 0.92)
        suffix = ""
        if kind == "peak":
            suffix = f"{int(p['ele']):,} m"
        elif kind == "city" and int(p.get("pop") or 0) >= 100000:
            suffix = f"{int(p.get('pop') or 0) / 1000:.0f}k"
        score = _score_place(p, d, visible)
        labels.append(Label(text=p["name"], suffix=suffix, x=x, y=y, color=color,
                            hidden=not visible, italic=italic, size=size,
                            score=score, dist=d, az=az, elev=elev, kind=kind))

    labels.sort(key=lambda l: l.score, reverse=True)
    measured = []
    img = Image.new("RGB", (8, 8))
    d = ImageDraw.Draw(img)
    placed_boxes: list[tuple[float, float, float, float]] = []
    for lab in labels:
        font = _font("segoeuii.ttf" if lab.italic else ("segoeuib.ttf" if lab.kind == "peak" else "segoeui.ttf"),
                     lab.size)
        sub = _font("segoeuil.ttf", max(14, int(lab.size * 0.56)))
        w1, h1 = _text_size(d, lab.text, font)
        w2, h2 = _text_size(d, "  " + lab.suffix, sub) if lab.suffix else (0, 0)
        lab.w = w1 + w2 + 16 * scale
        lab.h = max(h1, h2) + 8 * scale
        top = lab.y - lab.h - 10 * scale
        box = (lab.x - lab.w / 2, top, lab.x + lab.w / 2, lab.y - 8 * scale)
        if box[0] < 24 or box[2] > width - 24 or box[1] < 84 or box[3] > height - 96:
            continue
        if any(not (box[2] + 8 < b[0] or box[0] - 8 > b[2] or box[3] + 6 < b[1] or box[1] - 6 > b[3])
               for b in placed_boxes):
            continue
        placed_boxes.append(box)
        measured.append(lab)
        if len(measured) >= max_labels:
            break
    return measured


def _score_place(place: dict, dist_m: float, visible: bool) -> float:
    kind = place["kind"]
    pop = int(place.get("pop") or 0)
    rank = float(place.get("rank") or 0)
    tw = {"peak": 0.34, "city": 0.42, "town": 0.31, "village": 0.19,
          "hamlet": 0.05, "water": 0.24, "landmark": 0.18}.get(kind, 0.1)
    pop_s = min(1.0, math.log10(pop + 1) / 7.0)
    prox = max(0.0, 1.0 - dist_m / 120000.0)
    return tw + pop_s * 0.34 + rank * 0.28 + prox * 0.22 + (0.06 if visible else -0.12)


def _draw_label(img: Image.Image, draw: ImageDraw.ImageDraw, lab: Label, scale: float) -> None:
    font = _font("segoeuii.ttf" if lab.italic else ("segoeuib.ttf" if lab.kind == "peak" else "segoeui.ttf"),
                 lab.size)
    sub = _font("segoeuil.ttf", max(14, int(lab.size * 0.56)))
    suffix = ("  " + lab.suffix) if lab.suffix else ""
    w1, h1 = _text_size(draw, lab.text, font)
    w2, h2 = _text_size(draw, suffix, sub) if suffix else (0, 0)
    total_w = w1 + w2
    x = lab.x - total_w / 2
    y = lab.y - lab.h - 10 * scale - h1 * 0.1
    shadow = (8, 12, 16)

    # Ground tick and dot at the anchored elevation.
    dot = 3.4 * scale
    draw.ellipse((lab.x - dot, lab.y - dot, lab.x + dot, lab.y + dot), fill=lab.color)
    if lab.hidden:
        r = 7.5 * scale
        draw.ellipse((lab.x - r, lab.y - r, lab.x + r, lab.y + r), outline=lab.color, width=max(1, int(scale)))
    draw.line([(lab.x, lab.y + dot + 1), (lab.x, lab.y + 9 * scale)], fill=lab.color, width=max(1, int(1 * scale)))

    # One-pixel shadow, no pill.
    off = max(1, int(round(1.6 * scale)))
    draw.text((x + off, y + off), lab.text, font=font, fill=shadow)
    if suffix:
        draw.text((x + w1 + off, y + off + (h1 - h2) * 0.55), suffix, font=sub, fill=shadow)
    draw.text((x, y), lab.text, font=font, fill=lab.color)
    if suffix:
        draw.text((x + w1, y + (h1 - h2) * 0.55), suffix, font=sub,
                  fill=tuple(min(255, c + 30) for c in lab.color))
    if lab.hidden:
        tag = "hidden"
        tf = _font("segoeui.ttf", max(12, int(lab.size * 0.44)))
        tw, th = _text_size(draw, tag, tf)
        draw.text((lab.x - tw / 2, lab.y + 11 * scale), tag, font=tf,
                  fill=(150, 158, 164))


def _draw_chrome(img: Image.Image, draw: ImageDraw.ImageDraw, lat: float, lon: float,
                 heading: float, eye: float, width: int, height: int, scale: float,
                 title: str, subtitle: str, max_range: float, hfov: float,
                 pitch: float) -> None:
    mono = _font("consola.ttf", int(17 * scale))
    mono_s = _font("consola.ttf", int(14 * scale))
    title_f = _font("segoeuil.ttf", int(38 * scale))
    sub_f = _font("segoeui.ttf", int(17 * scale))
    white = (238, 244, 248)
    dim = (168, 182, 192)

    # top scrim
    top = Image.new("L", (1, int(150 * scale)), 0)
    for i in range(top.height):
        top.putpixel((0, i), int(110 * (1 - i / top.height) ** 1.6))
    top = top.resize((width, top.height))
    dark = Image.new("RGB", (width, top.height), (6, 12, 18))
    img.paste(dark, (0, 0), top)

    # bottom scrim
    bh = int(180 * scale)
    bot = Image.new("L", (1, bh), 0)
    for i in range(bh):
        bot.putpixel((0, i), int(170 * (i / bh) ** 1.8))
    bot = bot.resize((width, bh))
    dark2 = Image.new("RGB", (width, bh), (6, 12, 18))
    img.paste(dark2, (0, height - bh), bot)

    # Compass tape
    f = (width * 0.5) / math.tan(hfov * 0.5)
    x0, x1 = int(width * 0.16), int(width * 0.84)
    y = 74 * scale
    draw.line([(x0, y), (x1, y)], fill=(255, 255, 255, 48), width=1)
    for deg in range(0, 360, 5):
        az = math.radians(deg)
        delta = geo.wrap_pi(az - math.radians(heading))
        if abs(delta) > hfov * 0.62:
            continue
        px = width / 2 + f * math.tan(delta)
        if x0 <= px <= x1:
            major = deg % 15 == 0
            tick = 12 * scale if major else 6 * scale
            draw.line([(px, y), (px, y + tick)], fill=(255, 255, 255, 140 if major else 70), width=1)
            if major:
                label = {0: "N", 90: "E", 180: "S", 270: "W"}.get(deg, str(deg))
                tw, _ = _text_size(draw, label, mono_s)
                draw.text((px - tw / 2, y - 25 * scale), label, font=mono_s, fill=(228, 236, 242, 220))
    # centre mark
    draw.polygon([(width / 2 - 5 * scale, y - 6 * scale), (width / 2 + 5 * scale, y - 6 * scale),
                  (width / 2, y + 2 * scale)], fill=(255, 190, 92))
    hdg = f"{heading:03.0f}° {geo.compass_label(heading)}"
    tw, _ = _text_size(draw, hdg, mono)
    draw.text((width / 2 - tw / 2, y + 18 * scale), hdg, font=mono, fill=white)

    # elevation scale on the right
    scale_x = width - 56 * scale
    for deg in range(-10, 41, 5):
        e = math.radians(deg)
        yy = height * 0.5 - f * math.tan(e - pitch)
        if 120 < yy < height - 200:
            draw.line([(scale_x - 8 * scale, yy), (scale_x, yy)], fill=(255, 255, 255, 90), width=1)
            draw.text((scale_x + 6 * scale, yy - 9 * scale), f"{deg}°", font=mono_s,
                      fill=(210, 222, 230, 170))

    # bottom-left title
    bx, by = 54 * scale, height - 122 * scale
    draw.text((bx, by), title.upper(), font=title_f, fill=white)
    draw.text((bx, by + 48 * scale), subtitle, font=sub_f, fill=dim)

    # bottom-right meta
    meta = [f"{lat:.4f}°N  {abs(lon):.4f}°E", f"eye {eye:.0f} m",
            f"terrain to {max_range / 1000:.0f} km"]
    for i, line in enumerate(meta):
        tw, _ = _text_size(draw, line, mono_s)
        draw.text((width - 54 * scale - tw, height - 120 * scale + i * 22 * scale), line,
                  font=mono_s, fill=dim)

    # attribution
    attr = ("30 m surface model · Copernicus GLO-30 © DLR/ESA · "
            "places © OpenStreetMap contributors (ODbL)")
    tw, _ = _text_size(draw, attr, mono_s)
    draw.text(((width - tw) / 2, height - 34 * scale), attr, font=mono_s, fill=(150, 164, 174))


def render_panorama(pack_path: Path, lat: float, lon: float, heading_deg: float,
                    out: Path, title: str = "Gornergrat", subtitle: str = "Valais, Switzerland",
                    width: int = 2400, height: int = 1500, hfov_deg: float = 70.0,
                    pitch_deg: float = 0.0, eye_height: float = 1.7,
                    max_labels: int = 35, max_range_km: Optional[float] = None,
                    meta_out: Optional[Path] = None) -> dict:
    pack = PackReader(pack_path)
    terrain = pack.terrain(lat, lon, fine_radius_km=30.0,
                           coarse_radius_km=float(pack.manifest.get("radius_km", 80)))
    eye = float(terrain.sample(lat, lon)) + eye_height
    max_range = (max_range_km or float(pack.manifest.get("radius_km", 80))) * 1000.0
    max_range = min(max_range, terrain.range_m(lat, lon))

    sil = compute_silhouette(terrain, lat, lon, eye_height, bins=3600, max_range=max_range)
    sil_mid = compute_silhouette(terrain, lat, lon, eye_height, bins=3600,
                                 max_range=min(max_range, 38000.0))
    sil_near = compute_silhouette(terrain, lat, lon, eye_height, bins=3600,
                                  max_range=min(max_range, 13000.0))

    scale = width / 2400.0
    hfov = math.radians(hfov_deg)
    pitch = math.radians(pitch_deg)
    yaw = math.radians(heading_deg)

    img = _sky_image(width, height, pitch, hfov)
    draw = ImageDraw.Draw(img, "RGBA")

    # Soft sun haze low on the horizon, opposite the view.
    haze = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    hd = ImageDraw.Draw(haze)
    hd.ellipse((width * 0.02, height * 0.28, width * 0.65, height * 1.1), fill=(214, 205, 180, 26))
    haze = haze.filter(ImageFilter.GaussianBlur(120 * scale))
    img = Image.alpha_composite(img.convert("RGBA"), haze).convert("RGB")
    draw = ImageDraw.Draw(img, "RGBA")

    _draw_terrain_layer(img, draw, sil, yaw, pitch, hfov, width, height,
                        (108, 124, 132), 0.78, max_range)
    draw = ImageDraw.Draw(img, "RGBA")
    _draw_terrain_layer(img, draw, sil_mid, yaw, pitch, hfov, width, height,
                        (72, 88, 82), 0.42, 38000)
    draw = ImageDraw.Draw(img, "RGBA")
    _draw_terrain_layer(img, draw, sil_near, yaw, pitch, hfov, width, height,
                        (44, 58, 48), 0.12, 13000)
    draw = ImageDraw.Draw(img, "RGBA")

    labels = _place_labels(pack, terrain, sil, lat, lon, eye, yaw, pitch, hfov,
                           width, height, max_labels, scale)
    for lab in labels:
        _draw_label(img, draw, lab, scale)

    _draw_chrome(img, draw, lat, lon, heading_deg, eye, width, height, scale,
                 title, subtitle, max_range, hfov, pitch)

    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, "PNG", optimize=True)

    meta = {
        "pack": pack.manifest.get("id"),
        "viewpoint": {"name": title, "lat": lat, "lon": lon,
                      "ground_m": round(eye - eye_height, 1),
                      "eye_m": round(eye, 1)},
        "camera": {"heading_deg": heading_deg, "pitch_deg": pitch_deg,
                   "hfov_deg": hfov_deg, "width": width, "height": height},
        "terrain": {"model": "Copernicus GLO-30", "bins": sil.bins,
                    "max_range_km": round(max_range / 1000, 1),
                    "curvature": "R_eff = 6371 km / (1 - 0.13)"},
        "labels": [{"name": l.text, "kind": l.kind, "az_deg": round(math.degrees(l.az), 3),
                    "elev_deg": round(math.degrees(l.elev), 3),
                    "dist_m": round(l.dist, 1), "hidden": l.hidden}
                   for l in labels],
        "attribution": ["© OpenStreetMap contributors (ODbL)",
                        "Copernicus DEM © DLR/ESA"],
    }
    if meta_out:
        Path(meta_out).parent.mkdir(parents=True, exist_ok=True)
        Path(meta_out).write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    return meta
