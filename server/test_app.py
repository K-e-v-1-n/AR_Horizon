"""Minimal smoke tests for the pack service."""
from __future__ import annotations

import json
import shutil
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import PACKS, app

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/assets/packs/alps.pack.sqlite"


@pytest.fixture(scope="module", autouse=True)
def pack_dir(tmp_path_factory):
    global PACKS
    target = tmp_path_factory.mktemp("packs")
    if SOURCE.exists():
        shutil.copy2(SOURCE, target / "alps-matterhorn.sqlite")
    old = PACKS
    PACKS = target
    import app as module
    module.PACKS = target
    yield target
    PACKS = old
    module.PACKS = old


client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True


def test_catalogue_and_manifest():
    r = client.get("/v1/catalogue")
    assert r.status_code == 200
    packs = r.json()["packs"]
    assert packs
    pack = packs[0]
    assert pack["id"] == "alps-matterhorn"
    assert pack["size_bytes"] > 1_000_000
    assert "download" in pack

    m = client.get(f"/v1/packs/{pack['id']}/manifest")
    assert m.status_code == 200
    assert m.json()["dem_source"].startswith("Copernicus")


def test_coverage():
    r = client.get("/v1/coverage", params={"lat": 45.98, "lon": 7.78})
    assert r.status_code == 200
    assert r.json()["covered"] is True
    r = client.get("/v1/coverage", params={"lat": 0.0, "lon": 0.0})
    assert r.json()["covered"] is False


def test_download_range():
    r = client.get("/v1/packs/alps-matterhorn/download", headers={"Range": "bytes=0-1023"})
    assert r.status_code in (200, 206)
    assert len(r.content) > 0


def test_assemble_refuses_honestly():
    r = client.post("/v1/assemble", json={"lat": 0.0, "lon": 0.0, "radius_km": 80})
    assert r.status_code == 501
    assert r.json()["status"] == "assembly_not_mounted"
