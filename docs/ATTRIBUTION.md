# Attribution and licences

AR_Horizon shows the following in-app (Info card, Terrain → Sources and licences)
and in every pack manifest.

## Copernicus DEM GLO-30

> Copernicus DEM © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH
> 2014–2018, provided under COPERNICUS by the European Union and ESA.
> All rights reserved.

The Copernicus DEM is free for commercial and non-commercial use with
attribution. AR_Horizon uses the 30 m surface model (GLO-30 Public). Ocean and some
country tiles are withheld from the public release; a production pipeline must
fall back to GLO-90 or SRTM where available and record that in the pack's quality
score.

More: <https://www.copernicus.eu/en/access-data/copernicus-data-space-ecosystem>

## OpenStreetMap

> © OpenStreetMap contributors

Data is available under the **Open Database Licence (ODbL) 1.0**. AR_Horizon's
derived places table (names, coordinates, kinds, populations, ranks) is a
Derivative Database and is offered under ODbL 1.0. Anyone redistributing a pack
must keep this attribution and licence, and must offer the derived database
under ODbL.

Full text: <https://opendatacommons.org/licenses/odbl/1-0/>
Copyright page: <https://www.openstreetmap.org/copyright>

## GeoNames

The bundled worldwide city index uses the GeoNames `cities15000` dump.

> GeoNames — CC BY 4.0

More: <https://www.geonames.org/>

## Wikipedia / Wikidata

AR_Horizon never bundles long Wikipedia text. The Info card links to Wikipedia
search or the Wikidata entity (when an OSM `wikidata` tag exists). Those pages
are CC BY-SA 4.0 and remain on the web.

## Map data in the demo pack

The bundled Alps pack is derived from:
- Copernicus GLO-30 tiles N45/N46 E006/E007/E008;
- OpenStreetMap via Overpass (peaks, settlements, water, viewpoints, historic
  sites, lighthouses/towers).

Pack: `alps-matterhorn`, 51.4 MB, 80.1 km usable radius, 7,955 places.

## Software

| Component | Licence |
|---|---|
| AR_Horizon core and app | MIT (see `LICENSE`) |
| ARCore SDK | Google APIs Terms / ARCore Additional Terms |
| Jetpack Compose, AndroidX | Apache 2.0 |
| Aircompressor | Apache 2.0 |
| zstd / zstandard | BSD |
| FastAPI / Starlette / uvicorn | MIT / BSD |
| NumPy | BSD-3-Clause |
| Pillow | HPND |
| rasterio / GDAL | BSD / MIT-style |
| Requests | Apache 2.0 |

## Third-party notices

- AndroidX and Jetpack Compose include notices in their artifacts.
- The ARCore AAR includes its own licence text; review it before redistribution.
- Copernicus DEM attribution must remain visible wherever the terrain is shown
  or exported, including photos.

## Name

AR_Horizon is the product name. Check trademark and store availability before
release.
