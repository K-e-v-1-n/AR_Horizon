# Architecture

## Boundaries

```
┌──────────────────┐   ┌────────────────────┐   ┌────────────────────┐
│ ARCore / sensors │──▶│ pose fusion        │──▶│ projection         │
│ camera, IMU, GPS │   │ compass → offset   │   │ az/elev → pixel    │
└──────────────────┘   │ skyline lock       │   └─────────┬──────────┘
                       └─────────┬──────────┘             │
                                 │                        ▼
┌──────────────────┐   ┌─────────▼──────────┐   ┌────────────────────┐
│ pack.sqlite      │──▶│ horizon oracle     │──▶│ label solver       │
│ DEM + places     │   │ silhouette[3600]   │   │ rank · cull · box  │
└──────────────────┘   └────────────────────┘   └─────────┬──────────┘
                                                          ▼
                                                ┌────────────────────┐
                                                │ Compose labels     │
                                                └────────────────────┘
```

The portable core owns geodesy, terrain sampling, the silhouette, visibility,
projection and the label solver. It has no I/O, no Android types and no
third-party dependencies. The Android shell owns the camera, ARCore, sensors,
SQLite/zstd, Compose and permissions. This is the same cut the design calls for,
so an iOS shell would reuse `core/` unchanged.

## Portable core (`core/`)

Public C ABI in `include/vantage_core.h`; implementation in `src/core.cpp`.

**Terrain hand-off.** The platform assembles two regular lat/lon Int16 grids
and calls `vt_terrain_set_grid(level=0|1)`. Row 0 is north, column 0 west,
values are metres above the DEM datum. The core copies the data. Level 0 is 1",
level 1 is 4". Sampling is bilinear; outside the grid is NaN, not sea level.

**Geodesy.** Haversine distance, initial great-circle bearing, curvature drop
`d²/(2·R_eff)` with `R_eff = 6,371,008.8/(1−0.13)`. The eye altitude is
`DEM(lat,lon) + eyeHeight`; GPS vertical is deliberately unused.

**Silhouette.** For each of 3,600 bins, march a piecewise distance schedule from
25 m to the pack edge (15/60/150/300/600/1,000 m steps). Track the maximum
slope `(h − eye − drop)/d` rather than calling `atan2` per sample; convert once
per bin. A global max-height early exit ends the march when no remaining terrain
can beat the current horizon. Ele 3,600 bins in ~15 ms on a desktop i9; the pack
also carries `sector_max(az, ring, max_ele)` for a future per-sector exit.

**Visibility.** A target at distance `d` has apparent elevation
`atan2(h_target − eye − drop(d), d)`. It is visible when that exceeds
`silhouette[az] + 0.03°`. If the silhouette has no data for that bearing (edge of
pack), the label is marked `has_terrain = false` and the UI says approximate.

**Projection.** Camera basis from yaw/pitch/roll, pinhole focal from horizontal
FOV. Positive roll is a clockwise camera tilt; the right side of the horizon
rises. `vt_project` returns `on_screen = 0` behind the camera.

**Solver.** `vt_solve` computes distance/bearing/elevation for every candidate,
culls off-screen, scores by type weight + log population + pack rank + proximity
− hidden penalty, sorts, and greedily places measured screen boxes with 7 px
padding, capped at 35. It returns labels, not pixels on a bitmap, so Compose can
style and animate opacity without moving anchors.

**Skyline fit.** `vt_fit_skyline` takes the synthetic horizon and observed
sky/terrain boundary samples (azimuth, elevation, weight), scans ±12° in 0.05°
steps, and returns the best normalized correlation, the yaw correction and the
median vertical residual. The Kotlin side applies a median-of-5, requires a
score > 0.6 for three frames, and clamps each update to 0.5°.

## Android shell (`android/`)

### Camera and ARCore

- `ArController` creates one `Session` with `LATEST_CAMERA_IMAGE`, plane finding
  off, auto-focus, ambient light estimation.
- `BackgroundRenderer` draws ARCore's OES texture with a full-screen quad; UVs
  come from `Frame.transformCoordinates2d` every frame, so the preview crop is
  correct.
- `displayOrientedPose` gives the label camera. Camera forward/up vectors yield
  azimuth, pitch and roll. Horizontal FOV comes from texture intrinsics with
  the aspect-fill crop applied.
- A `GLSurfaceView` is embedded with `AndroidView`; Compose draws labels and
  chrome above it.

### Pose fusion

- The rotation-vector sensor and `GeomagneticField` declination give a coarse
  true heading, gated on `SENSOR_STATUS_ACCURACY_HIGH` (3).
- ARCore's world azimuth is arbitrary. The first high-accuracy compass reading
  seeds `headingOffset = compassAz − worldAz`; after that the compass does not
  steer labels.
- A drag adds a manual offset and clears the lock. The skyline lock adds a
  slowly smoothed offset. Both are session state; nothing is written to the
  pack.
- Without AR, the compass heading (or a demo heading toward the Matterhorn when
  no compass exists) drives projection, pitch/roll = 0, FOV 65°.

### Terrain session

`TerrainSession` owns the native handles. On open it assembles:

- fine 1" grid, cropped to 30 km around the observer;
- coarse 4" grid, cropped to the pack's advertised radius (80 km here);
- 3,600-bin silhouette;
- native place array with names/regions/wikidata;
- precomputed label widths for declutter.

Location moves over 1.5 km rebuild the session off the main thread. Labels solve
at 20 Hz on a background dispatcher. The UI receives immutable `LabelFrame`s.

### Skyline lock

`SkylineSampler` copies the Y plane at 160×120, finds the first confident
sky→terrain drop per column, and maps columns to camera-relative rays with
`imageIntrinsics`. The camera's **sensor** pose (not display-oriented) is used
for this geometry, because the image is sensor-oriented. The synthetic horizon
is copied from the core and correlated in C++. `engine.applySkylineOffset`
handles median, confidence and rate limits.

### UI

- `ArScreen`: camera/panorama, label canvas, compass tape, elevation scale,
  status and alignment chips, drag and tap gestures, bottom actions.
- `PanoramaBackground`: the same silhouette drawn as sky and terrain when AR is
  unavailable or the camera is off.
- `PlaceSheet`: name, kind, distance, bearing, elevation, hidden state, 30 m
  model note, position, population, Wikipedia/Wikidata link and attribution.
- `ListScreen`: every label sorted by bearing, with distance and hidden state.
- `PackScreen`: installed packs, size/quality/radius, eye height, hidden-label
  toggle, reset alignment, catalogue/download, server URL.
- `AttributionScreen`: sources, licences, privacy and model limits.

## Pack format

`pack.sqlite`:

```sql
manifest(id TEXT PRIMARY KEY, json TEXT)
dem(z INTEGER, x INTEGER, y INTEGER, w INTEGER, h INTEGER, data BLOB, PK(z,x,y))
places(id INTEGER PRIMARY KEY, osm_id, kind, name, name_local, lat, lon, ele,
       pop, rank, wikidata, source, desc)
places_rtree USING rtree(id, minlat, maxlat, minlon, maxlon)
sector_max(az INTEGER, ring INTEGER, max_ele REAL, PK(az,ring))
```

Tiles are 600×600 cells and six per degree at z=0, so global indices are
`x = floor((lon+180)·6)`, `y = floor((90−lat)·6)`. Each blob is zstd-compressed
little-endian Int16, row 0 north. z=1 tiles are 4× means of 4×4 z0 tiles, so
they cover 2/3° and use the same indexing divided by 4. Edge z1 groups replicate
nearby tiles instead of punching holes; grids are cropped to the advertised
radius so replicated terrain is never sampled.

The manifest carries the requested radius, actual bounds, tile counts, DEM and
places attribution, a quality score and an honesty note.

## Server (optional)

The app is standalone and has no catalogue URL by default. This service is
local development tooling and a seam for a future global tile factory, not a
runtime dependency. See [STANDALONE.md](STANDALONE.md).

FastAPI with a directory of immutable pack files:

- `GET /v1/catalogue`
- `GET /v1/packs/{id}/manifest`
- `GET /v1/packs/{id}/download` (Starlette `FileResponse`, ETag, range support)
- `GET /v1/coverage?lat&lon`
- `POST /v1/assemble` (returns the existing pack or a documented 501)
- `GET /v1/attribution`

The app downloads with a Range request when a `.part` file exists, verifies the
SQLite manifest, installs atomically and switches the engine.

## Threads and budgets

| Work | Thread | Rate |
|---|---|---|
| Camera preview | GL thread | display |
| ARCore update + pose | GL thread | display |
| Skyline image capture + fit | GL thread | ~6.7 Hz |
| Label solve | engine dispatcher | 20 Hz |
| Silhouette rebuild | IO dispatcher | on load / >1.5 km move |
| Compass | sensor thread | SENSOR_DELAY_GAME |
| Location | main looper | 3 s / 6 m |

No disk access happens on the render or label path. The 3,600-bin silhouette is
recomputed on open and when the observer moves far enough to matter.

## Desktop simulator

`web/` is a generated static build of the same pack and horizon. `tools/export_web.py`
writes four viewpoints and their full/40 km/13 km silhouettes into `web/data.js`;
the browser mirrors the LOD, score, declutter and projection rules for rendering.
It is a field simulator, not the Android shell. `tools/validate_skyline_lock.py`
uses synthetic camera frames, the same 160×120 boundary sampler and the compiled
C++ `vt_fit_skyline` to validate yaw recovery without a phone.

See [DESKTOP.md](DESKTOP.md).
