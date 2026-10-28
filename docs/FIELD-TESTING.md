# Field testing

The maths, pack, APK and synthetic lock are verified. Real camera exposure,
motion blur, ARCore pose timing and the compass seed outdoors still need a real
phone; an emulator cannot validate those. This is the protocol and the current
status.

For the no-phone checks: `web/index.html` is the desktop field simulator, and
`tools/validate_skyline_lock.py` recovers injected yaw errors from generated
camera frames using the same sampler and C++ fit as the app. See
[DESKTOP.md](DESKTOP.md).

## Status

| Milestone | Status |
|---|---|
| M0 math spike + golden | **Done** — Gornergrat PNG/JSON, 265.6° / +7.9° / 9.8 km |
| M1 terrain core + tile builder | **Done** — 15.3 ms desktop, 80 km pack |
| M2 world index | **Partial** — GeoNames + curated/pack peaks, 1 MB |
| M3 ARCore shell | **Code complete, unrun on hardware** |
| M4 towns + declutter | **Done in core/UI, verified in golden** |
| M5 skyline lock | **Implemented; synthetic 5/5, max error 0.10°; unrun outdoors** |
| M6 pack service + catalogue | **Partial** — one real pack, download/coverage, no global assembly |
| M7 launch polish | **Partial** — i18n/RTL not done, photo export is a screen capture |

## Devices

At minimum:

- one Pixel-class phone (reference compass, ARCore-certified);
- one mid-range Samsung (weaker magnetometer, thermal throttling).

Record with every session:

- device model, Android version, ARCore version;
- `PowerManager` thermal status at start and every 10 minutes;
- compass `SENSOR_STATUS_ACCURACY_*` and `GeomagneticField` declination;
- time to first fix, time to first lock, lock score;
- battery percentage at start/end;
- ambient conditions (sun, cloud, fog, forest, urban).

## Viewpoints

Start with unambiguous skylines where a wrong yaw is obvious:

- **Gornergrat → Matterhorn** (bundled pack; golden 265.6°)
- Kerry Park → Rainier
- Taormina → Etna
- A coastal viewpoint with a clean sea horizon (tests the ambiguous-yaw case)
- A forested valley (tests "too few sky columns")

For each, record a 30-second pan and a 5-minute stationary drift test.

## Protocol

1. Cold start, location on, pack installed. Note time to terrain ready.
2. Hold the phone upright. Pan slowly across the known peak. Record the first
   moment labels settle; time it.
3. Compare the Matterhorn label against a handheld compass bearing. Target
   end-to-end error < 0.3°; at 20 km that is < 100 m.
4. Drag-align manually. Record the offset and confirm labels follow the world,
   not the screen.
5. Leave the phone still for 5 minutes. Record drift in degrees.
6. Repeat with a magnetic case, then with the case removed. The accuracy gate
   must reject the bad reading; the skyline lock should carry.
7. Walk 100 m. Confirm the silhouette rebuild triggers only when needed and that
   labels do not jump.
8. Tilt the phone flat. Expect a "tilt up" prompt, not a broken projection.
9. Fog up the lens or cover the camera. Confirm the fallback: last lock retained,
   manual drag available, **list mode** fully usable.
10. Capture a photo. Check the labels and attribution are in the exported image.
11. Airplane mode: confirm labels, packs, list and capture still work. Only
    catalogue, downloads and Wikipedia links should fail.

## Metrics

| Term | Target | How measured |
|---|---|---|
| Yaw after lock | ±0.2° | handheld compass / surveyed bearing |
| Drift over 5 min | < 0.3° | stationary log |
| Pitch / roll | ±0.1° | gravity, visual horizon |
| Position | ±10 m | GPS vs known point |
| Altitude | ±5 m | DEM + baro vs surveyed spot height |
| End-to-end label error | < 0.3° | peak bearing/elevation |
| Time to lock | < 2 s at 70% of viewpoints | stopwatch |
| Thermals | no throttle in 15 min | `PowerManager` + battery |

## Reproducing field logs

Log per frame:

- timestamp, AR tracking state, AR world azimuth, compass azimuth + accuracy;
- heading offset, manual offset, lock offset, lock score, fit sample count;
- latitude/longitude/accuracy, DEM eye altitude;
- label count, visible count, first label's screen position.

Replay the pose + fit log through `vt_solve` offline; the label positions must
match the device within a pixel. The C++ CLI and Python renderer both operate on
the same `.vtgrid` grids, so a disagreement is a bug in one of them.

## Known failure modes to test

| Situation | Expected |
|---|---|
| Magnetic case / car keys | accuracy gate rejects; lock carries; manual available |
| Forest / urban horizon | too few sky samples → no lock; manual drag |
| Flat sea horizon | yaw ambiguous; labels still at the right distance and elevation |
| Night / fog | no sky contrast; manual |
| GPS jump | >100 m in <2 s ignored; hysteresis |
| Phone flat | "tilt up" prompt |
| No ARCore | compass panorama + list mode |
| No location permission | demo viewpoint clearly labelled |
| Pack edge | "approximate" labels, no hidden claims |

## What still needs doing

- Run the protocol on both devices and record results here.
- Compare 100+ automated goldens across continents against survey bearings.
- Add a nightly canary pack build and a per-pack confidence flag.
- Log thermal status from the GL thread and drop the skyline fit to 3 Hz when
  the device throttles.
