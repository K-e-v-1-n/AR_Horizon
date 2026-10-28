"""Geodesy shared by the M0 renderer and the pack builder.

Mirrors core/src/core.cpp. If one changes, change both and run the goldens.
"""
from __future__ import annotations

import math

EARTH_RADIUS_M = 6_371_008.8
REFRACTION_K = 0.13
EFFECTIVE_RADIUS_M = EARTH_RADIUS_M / (1.0 - REFRACTION_K)
DEG = math.pi / 180.0


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2.0 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def bearing_rad(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.atan2(y, x) % (2 * math.pi)


def curvature_drop_m(d: float) -> float:
    return d * d / (2.0 * EFFECTIVE_RADIUS_M)


def elevation_angle(h_target: float, h_eye: float, d: float) -> float:
    return math.atan2(h_target - h_eye - curvature_drop_m(d), d)


def metres_per_degree_lat(lat: float) -> float:
    p = math.radians(lat)
    return 111132.92 - 559.82 * math.cos(2 * p) + 1.175 * math.cos(4 * p) - 0.0023 * math.cos(6 * p)


def metres_per_degree_lon(lat: float) -> float:
    p = math.radians(lat)
    return 111412.84 * math.cos(p) - 93.5 * math.cos(3 * p) + 0.118 * math.cos(5 * p)


def destination(lat: float, lon: float, az_rad: float, dist_m: float) -> tuple[float, float]:
    """Spherical direct geodesic (used by the pack builder/goldens)."""
    p1 = math.radians(lat)
    l1 = math.radians(lon)
    d = dist_m / EARTH_RADIUS_M
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(az_rad))
    l2 = l1 + math.atan2(math.sin(az_rad) * math.sin(d) * math.cos(p1),
                         math.cos(d) - math.sin(p1) * math.sin(p2))
    return math.degrees(p2), (math.degrees(l2) + 540.0) % 360.0 - 180.0


def wrap_pi(a: float) -> float:
    while a > math.pi:
        a -= 2 * math.pi
    while a < -math.pi:
        a += 2 * math.pi
    return a


def compass_label(az_deg: float) -> str:
    names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
             "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
    return names[int((az_deg % 360) / 22.5 + 0.5) % 16]


def format_distance(metres: float) -> str:
    if metres < 1000:
        return f"{metres:.0f} m"
    return f"{metres / 1000.0:.1f} km"
