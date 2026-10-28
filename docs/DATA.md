# Data

## Terrain

**Copernicus GLO-30 Public** — a 30 m digital surface model (DSM), EPSG:4326,
1 arc-second grid, Int16 metres, EGM2008 orthometric heights. Canopy and
settlements appear as raised surface. A few country tiles are withheld from the
public release; the design falls back to GLO-90/SRTM there, but this build only
ships the Alps pack, all GLO-30.

Tiles come from the public S3 mirror:

```
https://copernicus-dem-30m.s3.amazonaws.com/
  Copernicus_DSM_COG_10_N45_00_E007_00_DEM/
  Copernicus_DSM_COG_10_N45_00_E007_00_DEM.tif
```

`tools/vantage_tools/dem.py` caches them under `data/cache/dem/`. Six 1° COGs
cover the 80 km pack. The builder reads a COG, divides it into 600×600 tiles,
and compresses each with zstd. No resampling: 600 divides 3,600 arc-seconds, so
every tile boundary lands exactly on a 1° COG boundary. This is the one deviation
from the design's nominal 512×512 tile: alignment avoids sub-cell interpolation
and makes the global tile index exact.

## Places

`tools/vantage_tools/places.py` queries Overpass for:

- `natural=peak` with a name;
- `place=city|town|village|hamlet|suburb|quarter|neighbourhood` with a name;
- named `natural=water|reservoir|lake|bay`;
- named `tourism=viewpoint`;
- named `historic=castle|ruins|monastery|fort`;
- named `man_made=lighthouse|tower`.

Public Overpass instances are flaky. `tools/fetch_places.py` prefers
`overpass.osm.ch`, falls back to `overpass-api.de`, retries, and caches the raw
JSON. For the Alps pack it returned 7,955 places: 3,397 peaks, 2,678 hamlets,
742 water bodies, 660 villages, 459 landmarks, 19 towns/cities.

Normalisation:

- English name when available, local name kept separately;
- `ele` parsed from free text (`4478`, `4478 m`, `4,478`);
- population parsed from free text;
- peak `rank` from elevation, settlement rank from log population;
- peaks with no `ele` get the local DEM maximum (3-cell radius);
- every other missing elevation gets the DEM value;
- OSM id, Wikidata id and a short description are preserved.

Ranking in the pack is a static bias. The runtime solver adds type weight,
log population, proximity and a visibility penalty.

## Worldwide index

`tools/build_world_index.py` builds `assets/index/world.idx`:

```
magic  "VTIDX1\0\0"   8 bytes
u32    version        1
u32    count
record: f32 lat, f32 lon, f32 ele, i32 pop, u8 kind, u8 nameLen, name UTF-8
```

Contents: all GeoNames `cities15000` (34,146 settlements) plus curated iconic
peaks and the ranked peaks from the pack (2,597), 1.02 MB total. Kind 0 = peak,
1 = city (≥100k), 2 = town. This is the first-run index; it answers bearing and
distance anywhere, and elevation where the source has it. It does **not** claim
visibility. A full OSM/Wikidata peak index belongs to the planet pipeline.

GeoNames: `https://download.geonames.org/export/dump/cities15000.zip`, CC BY 4.0.

## Pack build

```powershell
.\.venv\Scripts\python.exe tools\build_pack.py `
  --lat 45.98331 --lon 7.78470 --radius 80 `
  --name "Alps: Matterhorn and Monte Rosa" --id alps-matterhorn `
  --places data/places/alps.json `
  --out android/app/src/main/assets/packs/alps.pack.sqlite
```

Steps:

1. Compute the request bounding box from the radius and latitude.
2. Download/read the intersecting 1° COGs.
3. Keep only 600×600 z0 tiles that intersect the box.
4. Compress each with zstd level 7 and insert into SQLite.
5. Build z1 tiles for every touched 4×4 group; replicate the nearest member for
   edge groups.
6. Fill missing place elevations from the DEM.
7. Insert places and their R-tree entries.
8. Insert a coarse per-azimuth/ring max-height table (sampled every 4th cell) for
   future early-exit heuristics.
9. Write the manifest and close without `VACUUM` (fast and deterministic for
   this size).

Result: 130 z0 tiles, 16 z1 tiles, 7,955 places, 51.4 MB, 80.1 km usable range.

`tools/export_grids.py` writes the same cropped fine/coarse grids as `.vtgrid`
files for the C++ CLI. `tools/render_golden.py` renders the PNG and JSON.

## Quality and coverage

Each pack manifest carries `quality.score` (0.9 here), DEM source, place count
and a note that the model is 30 m. The app shows the score in the pack screen and
says "approximate" whenever no terrain covers a label. There is no pretence of
country-wide completeness.

## Licensing

- DEM: attribution required. Full text in the app and in `ATTRIBUTION.md`.
- OSM: ODbL 1.0. The derived places DB is offered under ODbL; attribution is
  shown in-app and in the manifest. Downstream redistribution must keep it.
- GeoNames: CC BY 4.0.
- Wikipedia/Wikidata: CC BY-SA 4.0, linked only.

## Reproducibility

- Pack builder is deterministic apart from timestamps and compression output.
- The golden metadata stores every label's azimuth/elevation/distance.
- Native and Python silhouettes agree to 5×10⁻⁵° across all 3,600 bins.
- `core/build/vantage-tests.exe` and `pytest tools/tests` are the regression
  gate. Both run without a phone.
