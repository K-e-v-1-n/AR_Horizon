# Standalone, 0 € server cost

AR_Horizon is built to work with no backend. The APK contains:

- the Alps terrain pack (51.4 MB, Copernicus GLO-30 + OSM places);
- the worldwide cities/peaks index (1.0 MB);
- the portable terrain core and all label math.

On first launch it copies the bundled pack to app storage and starts. No
account, no API key, no catalogue request, no analytics, no recurring bill.

## What works with the network off

| Feature | Offline? |
|---|---|
| Camera / compass panorama | yes |
| Terrain labels, hidden labels, list mode | yes |
| Tap card, distance, bearing, elevation | yes |
| Pack switch, eye height, hidden toggle | yes |
| Photo capture to `Pictures/AR_Horizon` | yes |
| Worldwide city/peak index (approximate mode) | yes (bundled) |
| Add another region | yes, via file import |
| Wikipedia / Wikidata link | no (opens the web) |
| Optional catalogue / direct URL download | no (only when you ask) |

Airplane mode is a supported state, not an error state.

No Google Play services are required for compass/list/label mode. ARCore is
optional; if it is missing or the device is not certified, the app explains that
and continues with the synthetic horizon.

There is also a **desktop field simulator** in `web/`: open `web/index.html` and
the same pack/horizon/labels run in a browser with no phone and no server. See
[DESKTOP.md](DESKTOP.md).

## Adding terrain without a server

### 1. Import a pack file

1. Build or obtain a `pack.sqlite` (see below).
2. Copy it to the phone: USB, Downloads, SD card, any file provider.
3. Open **Terrain → Add terrain → Import .sqlite**.
4. Pick the file. The app validates its manifest, copies it into app storage,
   and switches to it.

The file picker uses Android's Storage Access Framework. AR_Horizon needs no
storage permission and does not keep a grant beyond the copy.

### 2. Direct HTTPS URL

Upload the `.sqlite` somewhere static and free:

- GitHub Releases assets;
- Cloudflare R2 free tier or Pages;
- any HTTPS web space.

Paste the direct file URL into **Terrain → Add terrain → Direct pack URL** and
tap **Download pack file**. This is a plain file download with `Range` resume;
there is no API, database or server process. The app verifies the pack manifest
before installing and switches to it.

HTTPS is strongly preferred. Android's network security config permits cleartext
only for `10.0.2.2`, `localhost` and `127.0.0.1`, for local development.

### 3. Build another region

```powershell
.\.venv\Scripts\python.exe tools\fetch_places.py `
  --lat <lat> --lon <lon> --radius <km> --out data/places/region.json

.\.venv\Scripts\python.exe tools\build_pack.py `
  --lat <lat> --lon <lon> --radius <km> `
  --name "Region name" --id region-id `
  --places data/places/region.json `
  --download-dem `
  --out data/packs/region-id.sqlite
```

The DEM tiles are downloaded once from Copernicus (free, attribution required)
and cached under `data/cache/dem/`. The resulting pack is self-contained.
Copy it to the phone and import it; no server is involved.

## Optional local service

`server/app.py` is a FastAPI wrapper for a directory of packs. It is useful for
LAN development and for a phone that can reach your PC, but it is not part of
the app's required path. `server\run.ps1` binds only on your machine; there is
nothing to pay for.

The server is also the seam for a future global tile factory. That factory is
not in this repository; see the README's "not finished" section.

## Cost

| Item | Recurring cost |
|---|---|
| Android APK + bundled pack | 0 € |
| Import from local storage | 0 € |
| Direct HTTPS download from free static hosting | 0 € (free-tier limits apply) |
| Optional local FastAPI service | 0 € (runs on your PC) |
| Optional cloud tile factory | not built here |

## Privacy and network calls

The only network-capable code paths are:

1. the catalogue/direct download you explicitly start;
2. Wikipedia/Wikidata links you tap in the info card.

Camera frames never leave the device; the skyline lock is computed locally.
Location stays local. There is no telemetry.

## Updating

- **App update** replaces the bundled pack with the version inside the new APK.
- **Pack update** import a newer file or download it from a URL; AR_Horizon replaces
  the installed file with the same manifest id.
- Packs are immutable, versioned SQLite files; a bad download is validated
  before it can become active.
