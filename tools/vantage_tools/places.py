"""OpenStreetMap places through Overpass, normalised into pack candidates."""
from __future__ import annotations

import json
import math
import re
import time
from dataclasses import dataclass, asdict
from pathlib import Path

import requests

OVERPASS = [
    "https://overpass.osm.ch/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]
CACHE = Path(__file__).resolve().parents[2] / "data" / "cache" / "places"

PLACE_KINDS = {"city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood"}


@dataclass
class Place:
    osm_id: str
    kind: str
    name: str
    name_local: str
    lat: float
    lon: float
    ele: float
    pop: int
    rank: float
    wikidata: str
    source: str = "osm"
    desc: str = ""

    def to_row(self) -> tuple:
        return (self.osm_id, self.kind, self.name, self.name_local, self.lat,
                self.lon, self.ele, self.pop, self.rank, self.wikidata,
                self.source, self.desc)


def _parse_ele(value) -> float:
    if value is None:
        return float("nan")
    text = str(value).replace("\u00a0", " ").strip()
    m = re.search(r"-?\d[\d.,]*", text)
    if not m:
        return float("nan")
    num = m.group(0)
    if "," in num and "." in num:
        num = num.replace(",", "")
    elif "," in num:
        before, after = num.split(",", 1)
        # "4,478 m" is thousands; "4478,5" is a decimal comma.
        if len(after) == 3 and len(before.lstrip("-")) <= 3:
            num = num.replace(",", "")
        else:
            num = num.replace(",", ".")
    try:
        return float(num)
    except ValueError:
        return float("nan")


def _parse_pop(value) -> int:
    if value is None:
        return 0
    digits = re.sub(r"[^\d]", "", str(value))
    return int(digits) if digits else 0


def _kind(tags: dict) -> tuple[str, float]:
    if tags.get("natural") == "peak":
        return "peak", 0.0
    if tags.get("natural") in ("water", "reservoir", "lake", "bay") or tags.get("waterway") == "river":
        return "water", 0.5
    place = tags.get("place", "")
    if place in ("city", "town"):
        return place, 0.5
    if place in ("village", "hamlet", "suburb", "quarter", "neighbourhood"):
        return "village" if place == "village" else "hamlet", 0.3
    if tags.get("tourism") == "viewpoint":
        return "landmark", 0.35
    if tags.get("man_made") in ("lighthouse", "tower") or tags.get("historic") in (
            "castle", "ruins", "monastery", "fort", "archaeological_site"):
        return "landmark", 0.35
    return "", 0.0


def _rank(kind: str, ele: float, pop: int, tags: dict) -> float:
    if kind == "peak":
        e = ele if math.isfinite(ele) else 1500.0
        return max(0.0, min(1.0, (e - 800.0) / 3800.0)) * 0.85 + 0.1
    if kind in ("city", "town"):
        return max(0.0, min(1.0, math.log10(max(pop, 10)) / 6.0))
    if kind == "village":
        return 0.35 + max(0.0, min(0.25, math.log10(max(pop, 10)) / 30.0))
    if kind == "hamlet":
        return 0.15
    if kind == "water":
        return 0.55
    if kind == "landmark":
        return 0.4
    return 0.1


def query(lat: float, lon: float, radius_km: float, cache: Path = CACHE,
          refresh: bool = False) -> list[Place]:
    r = radius_km / 111.0
    rlon = r / max(0.1, math.cos(math.radians(lat)))
    bbox = (lat - r, lon - rlon, lat + r, lon + rlon)
    key = f"{lat:.3f}_{lon:.3f}_{radius_km:.0f}"
    cache = Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / f"{key}.json"
    if path.exists() and not refresh:
        raw = json.loads(path.read_text(encoding="utf-8"))
    else:
        raw = _fetch(bbox)
        path.write_text(json.dumps(raw), encoding="utf-8")
    return normalize(raw)


def _fetch(bbox: tuple[float, float, float, float]) -> dict:
    s, w, n, e = bbox
    q = f"""
[out:json][timeout:240][maxsize:1073741824];
(
  nwr["natural"="peak"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
  nwr["place"~"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
  nwr["natural"~"^(water|reservoir|lake|bay)$"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
  nwr["tourism"="viewpoint"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
  nwr["historic"~"^(castle|ruins|monastery|fort)$"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
  nwr["man_made"~"^(lighthouse|tower)$"]["name"]({s:.4f},{w:.4f},{n:.4f},{e:.4f});
);
out center tags;
"""
    headers = {"User-Agent": "Vantage/0.1 (offline horizon app; pack builder)"}
    last: Exception | None = None
    for endpoint in OVERPASS:
        for attempt in range(3):
            try:
                resp = requests.post(endpoint, data={"data": q}, headers=headers, timeout=260)
                resp.raise_for_status()
                return resp.json()
            except Exception as exc:  # noqa: BLE001
                last = exc
                time.sleep(2 + attempt * 3)
    raise RuntimeError(f"Overpass failed: {last}")


def normalize(raw: dict) -> list[Place]:
    out: list[Place] = []
    seen: set[tuple[str, int, int]] = set()

    for el in raw.get("elements", []):
        tags = el.get("tags") or {}
        name = (tags.get("name:en") or tags.get("name") or "").strip()
        local = (tags.get("name") or name).strip()
        if not name:
            continue
        kind, _bias = _kind(tags)
        if not kind:
            continue
        if "lat" in el and "lon" in el:
            lat, lon = float(el["lat"]), float(el["lon"])
        elif "center" in el:
            lat, lon = float(el["center"]["lat"]), float(el["center"]["lon"])
        else:
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        ele = _parse_ele(tags.get("ele"))
        pop = _parse_pop(tags.get("population"))
        key = (name.casefold(), int(lat * 500), int(lon * 500))
        if key in seen:
            continue
        seen.add(key)
        desc = (tags.get("description") or tags.get("tourism") or
                tags.get("historic") or "").strip()
        out.append(Place(
            osm_id=f"{el.get('type', 'n')[0]}{el.get('id')}",
            kind=kind,
            name=name,
            name_local=local,
            lat=lat,
            lon=lon,
            ele=ele,
            pop=pop,
            rank=_rank(kind, ele, pop, tags),
            wikidata=(tags.get("wikidata") or "").strip(),
            source="osm",
            desc=desc,
        ))
    return out


def write_json(places: list[Place], path: Path) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps([asdict(p) for p in places], ensure_ascii=False, indent=1),
                          encoding="utf-8")


def read_json(path: Path) -> list[Place]:
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    return [Place(**row) for row in rows]
