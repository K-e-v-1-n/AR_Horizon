# Code review — 2026-10-28

Scope: `core/` C++ and JNI, Python pack/data/render tooling, Android shell and UI,
web simulator, FastAPI service, build scripts and docs. Every fix below was
rebuilt and re-tested.

## Highest-impact fixes

1. **Skyline lock used the wrong azimuth frame.** Observed image rays were
   converted using ARCore's arbitrary world yaw, not the fused true heading
   (AR yaw + compass seed + manual/lock offset). Once the compass seeded north,
   the fit was offset by that seed and could never lock. It now uses
   `engine.currentYawDeg()`. `android/.../ar/ArController.kt`

2. **Tap gestures restarted every 20 Hz.** The pointer-input keys included the
   label list, so a frame update could cancel a tap mid-gesture. Taps now read
   the latest frame through `rememberUpdatedState` and taps on empty sky clear
   the open card. `android/.../ui/ArScreen.kt`, `VantageApp.kt`

3. **Long labels could crash the overlay.** `coerceIn(min, max)` with a text
   wider than a narrow screen produced `max < min`. Clamps are now
   min/max-safe. `android/.../ui/LabelLayer.kt`

4. **Malformed/non-coveraging pack URL could throw out of a coroutine.** URL
   construction and 416 Range retry are handled; a stale `.part` is discarded
   and retried from zero. `android/.../VantageViewModel.kt`

5. **Imported packs could overwrite the bundled pack.** A malicious/manifest id
   like `alps.pack` mapped onto the bundled filename. Reserved files are now
   avoided, and deleting the bundled pack compares exact paths. Pack installs
   now verify the manifest and decompress a real tile before activation.
   `PackRepository.kt`, `VantageViewModel.kt`, `PackDatabase.kt`

6. **Database ownership and lifecycle races.** Failure paths in
   `TerrainSession.open`, `openPack` and `reloadAt` either double-closed or
   leaked the SQLite connection; native handle creation/upload return values
   are now checked. `TerrainSession.kt`, `VantageEngine.kt`

7. **No multi-pack fallback while moving.** When the current pack stopped
   covering a GPS fix, the app switched to index mode even if another installed
   pack covered the position. It now picks the covering installed pack, then
   falls back to index. `VantageEngine.kt`

8. **New ARCore session reused a stale heading offset.** After Activity
   recreation or a new session, the AR world frame is arbitrary; the old
   compass/manual/lock offset made every label wrong. A new session now resets
   the fusion state and reseeds from the compass. `ArController.kt`,
   `VantageEngine.kt`

9. **Coarse-location-only users were treated as location-off.** Both the
   permission callback and resume path now accept coarse location; the tracker
   requests updates when either permission is held. `MainActivity.kt`,
   `LocationTracker.kt`

10. **Far-field LOD disagreed between core, renderer and web.** Core allowed
    towns/landmarks/water beyond 50 km that the renderer and design excluded.
    All three now use the same 5/20/50/80 km rules. `core/src/core.cpp`,
    `web/app.js`, `tools/vantage_tools/render.py`

11. **JNI could read past caller arrays.** Width, place and grid arrays are
    length-checked; invalid sizes fall back safely. `core/jni/vantage_jni.cpp`

12. **Python pack builder left a locked database on failure.** The writer is
    closed in `finally`, places are clipped to the pack (plus 2 km), and
    elevation text like `4,478 m` is parsed as thousands, not 4.478.
    `tools/vantage_tools/pack.py`, `places.py`

13. **World-index and console portability.** UTF-8 names are truncated on a
    character boundary; Windows console arrows were replaced with ASCII so
    `fetch_places.py` no longer dies after writing its output. `index.py`,
    `fetch_places.py`, `build_world_index.py`

14. **Web list/info interaction.** Opening Features now closes the info card so
    the panels cannot overlap; the list is the current in-view set rather than
    all 3,542 candidates in the pack. `web/app.js`

15. **Server path traversal.** Pack ids containing `/`, `\` or `..` are
    rejected before touching the filesystem. `server/app.py`

Also fixed: `lastLock` false positive on very young device uptime, NaN profile
bins in the Android panorama, invalid packed-tile lengths, rank/population
sanitisation in the C++ solver, missing `onResume` permission refresh, and the
demo-location/startup GPS race.

## Verification

| Check | Result |
|---|---|
| C++ tests | 39 checks, 0 failures |
| Python + server tests | 17 passed |
| Synthetic skyline lock | 5/5, max error 0.10°, score > 0.94 |
| Android assemble | `BUILD SUCCESSFUL` |
| Android lint | passes, 0 errors |
| Web JS syntax | clean |
| APK | `dist/AR_Horizon-0.1.0-debug.apk`, 84.3 MB |
| Native lib in APK | SHA-256 matches `jniLibs` |

## Residual risks (not code regressions)

- No ARCore phone was available. Camera orientation, texture crop, compass
  quality, real skyline lock and thermals remain untested on hardware.
- The skyline fit is roll-compensated only through the AR pose used for labels;
  the boundary sampler uses pitch and yaw, not roll. This should be checked
  outdoors with the phone deliberately tilted.
- `acquireCameraImage` intrinsics and the preview crop are handled, but the
  principal-point offset is not part of the label projection; a device check
  at the screen edges is still needed.
- Photo export is a window PixelCopy, not a 1080p image-intrinsics re-projection.
- Marks, barometer smoothing, global on-demand pack assembly, and i18n/RTL are
  still out of scope for this build.
