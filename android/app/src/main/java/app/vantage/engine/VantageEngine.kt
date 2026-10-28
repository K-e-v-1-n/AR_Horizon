package app.vantage.engine

import android.content.Context
import android.os.SystemClock
import app.vantage.data.PackDatabase
import app.vantage.data.PackManifest
import app.vantage.data.PackRepository
import app.vantage.data.VantageSettings
import app.vantage.data.WorldIndex
import app.vantage.geo.Geo
import app.vantage.model.Alignment
import app.vantage.model.CameraPose
import app.vantage.model.LabelFrame
import app.vantage.model.LabelItem
import app.vantage.model.LatLon
import app.vantage.model.Mode
import app.vantage.model.Place
import app.vantage.sensors.CompassTracker
import app.vantage.sensors.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class EngineState(
    val mode: Mode = Mode.LOADING,
    val alignment: Alignment = Alignment.UNAVAILABLE,
    val frame: LabelFrame? = null,
    val pack: PackManifest? = null,
    val altitude: Double = Double.NaN,
    val location: LatLon = LatLon(45.98331, 7.78470),
    val demoLocation: Boolean = true,
    val locationAccuracy: Double = 0.0,
    val compassAccuracy: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val status: String = "",
    val selected: LabelItem? = null,
    val showHidden: Boolean = true,
    val terrainRadiusKm: Double = 0.0,
    val placeCount: Int = 0,
    val arActive: Boolean = false,
)

class VantageEngine(
    private val context: Context,
    private val repo: PackRepository,
    private val settings: VantageSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EngineState(showHidden = settings.showHidden))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val density = context.resources.displayMetrics.density
    private val lock = Any()
    @Volatile private var session: TerrainSession? = null

    @Volatile private var worldIndex: WorldIndex = WorldIndex(emptyList())
    @Volatile private var indexPlaces: List<Place> = emptyList()
    private var reloadJob: Job? = null
    private var loopJob: Job? = null

    private val locationTracker = LocationTracker(context) { lat, lon, acc, _, _ ->
        onLocationFix(lat, lon, acc)
    }
    private val compassTracker = CompassTracker(context) { az, accuracy ->
        onCompass(az, accuracy)
    }

    // Pose state, written from the sensor and AR threads.
    @Volatile private var arActive = false
    @Volatile private var arWorldAz = 0.0
    @Volatile private var arPitch = 0.0
    @Volatile private var arRoll = 0.0
    @Volatile private var arHfov = 65.0
    @Volatile private var arWidth = 0
    @Volatile private var arHeight = 0
    @Volatile private var compassAz = Double.NaN
    @Volatile private var compassAccuracy = 0
    @Volatile private var viewportW = 1080
    @Volatile private var viewportH = 1920
    @Volatile private var headingOffset = Double.NaN
    @Volatile private var manualOffset = 0.0
    @Volatile private var lockOffset = 0.0
    @Volatile private var lastLockMs = 0L
    private var lockQueue = ArrayDeque<Double>()
    private var goodLockFrames = 0
    @Volatile private var demoHeading = 265.0
    @Volatile private var selectedId: Int? = null
    @Volatile private var lastFix: LatLon? = null

    fun start() {
        loopJob = scope.launch { labelLoop() }
        reloadJob = scope.launch(Dispatchers.IO) {
            worldIndex = repo.loadWorldIndex()
            try {
                val preferred = settings.packPath
                    ?.let { java.io.File(it) }
                    ?.takeIf { it.exists() && it.length() > 1_000_000 }
                val file = preferred ?: repo.ensureBundled()
                settings.packPath = file.absolutePath
                val fix = lastFix
                try {
                    openPack(file.absolutePath, fix?.lat ?: 45.98331,
                        fix?.lon ?: 7.78470, demo = fix == null)
                } catch (openError: Throwable) {
                    if (preferred == null) throw openError
                    // A damaged imported pack must not brick startup.
                    val bundled = repo.ensureBundled()
                    settings.packPath = bundled.absolutePath
                    openPack(bundled.absolutePath, fix?.lat ?: 45.98331,
                        fix?.lon ?: 7.78470, demo = fix == null)
                }
            } catch (t: Throwable) {
                updateIndexAt(45.98331, 7.78470)
                _state.update {
                    it.copy(
                        loading = false,
                        mode = Mode.APPROXIMATE,
                        error = t.message ?: "could not open the bundled pack",
                    )
                }
            }
        }
        startSensors()
    }

    fun onResume() {
        locationTracker.start()
        compassTracker.start()
    }

    fun onPause() {
        locationTracker.stop()
        compassTracker.stop()
    }

    fun stop() {
        locationTracker.stop()
        compassTracker.stop()
        scope.cancel()
        synchronized(lock) {
            session?.close()
            session = null
        }
    }

    private fun startSensors() {
        locationTracker.start()
        compassTracker.start()
    }

    private suspend fun openPack(path: String, lat: Double, lon: Double, demo: Boolean) {
        val db = withContext(Dispatchers.IO) { PackDatabase(java.io.File(path)) }
        val manifest = try {
            db.manifest
        } catch (t: Throwable) {
            db.close()
            throw t
        }
        val covered = manifest.contains(lat, lon)
        val useLat = if (covered) lat else manifest.centerLat
        val useLon = if (covered) lon else manifest.centerLon
        val newSession = withContext(Dispatchers.IO) {
            TerrainSession.open(db, useLat, useLon, settings.eyeHeight.toDouble(), density)
        }
        if (newSession == null) {
            // TerrainSession.open owns and closes the database on failure.
            synchronized(lock) {
                session?.close()
                session = null
            }
            _state.update {
                it.copy(loading = false, mode = Mode.NO_DATA, pack = null,
                    error = "This pack could not be loaded at the current position.")
            }
            return
        }
        synchronized(lock) {
            session?.close()
            session = newSession
        }
        compassTracker.updateLocation(useLat, useLon, newSession.eyeAltitude)
        val matterhorn = newSession.places.firstOrNull { it.name.contains("Matterhorn", true) }
        demoHeading = if (matterhorn != null) {
            Geo.bearingDeg(useLat, useLon, matterhorn.lat, matterhorn.lon)
        } else {
            265.0
        }
        _state.update {
            it.copy(
                loading = false,
                pack = manifest,
                mode = Mode.TERRAIN_LOCKED,
                location = LatLon(useLat, useLon),
                demoLocation = demo || !covered,
                error = null,
                placeCount = newSession.placeCount,
                terrainRadiusKm = manifest.radiusKm,
                altitude = newSession.eyeAltitude,
            )
        }
    }

    private suspend fun labelLoop() {
        while (scope.isActive) {
            val cam = currentCamera()
            val (current, terrainLabels) = synchronized(lock) {
                val s = session
                s to (s?.solve(cam) ?: emptyList())
            }
            val labels = if (current != null) {
                terrainLabels
            } else {
                val places = indexPlaces
                if (places.isEmpty()) emptyList()
                else ApproximateSolver.solve(places, _state.value.location.lat,
                    _state.value.location.lon, cam, density)
            }
            val now = SystemClock.elapsedRealtime()
            val visible = if (_state.value.showHidden) labels else labels.filter { it.visible }
            val selected = selectedId?.let { id -> labels.firstOrNull { it.place.id == id } }
            val alignment = when {
                lastLockMs > 0 && now - lastLockMs < 6000 -> Alignment.LOCKED
                Math.abs(manualOffset) > 0.01 -> Alignment.MANUAL
                !arActive && compassAccuracy >= 3 -> Alignment.COMPASS
                arActive -> Alignment.ALIGNING
                compassAz.isFinite() -> Alignment.MANUAL
                else -> Alignment.UNAVAILABLE
            }
            val mode = when {
                current != null -> Mode.TERRAIN_LOCKED
                indexPlaces.isNotEmpty() -> Mode.APPROXIMATE
                _state.value.loading -> Mode.LOADING
                else -> Mode.NO_DATA
            }
            val status = when {
                current != null -> "30 m surface model · ${visible.size} labels · " +
                    "${current.manifest.radiusKm.toInt()} km pack"
                indexPlaces.isNotEmpty() -> "Index only · bearing and distance · no terrain"
                else -> "No terrain pack here"
            }
            _state.update {
                it.copy(
                    frame = LabelFrame(
                        labels = visible,
                        headingDeg = cam.yawDeg,
                        pitchDeg = cam.pitchDeg,
                        rollDeg = cam.rollDeg,
                        hfovDeg = cam.hfovDeg,
                        width = cam.width,
                        height = cam.height,
                    ),
                    mode = mode,
                    alignment = alignment,
                    altitude = current?.eyeAltitude ?: it.altitude,
                    selected = selected,
                    compassAccuracy = compassAccuracy,
                    status = status,
                    arActive = arActive,
                )
            }
            delay(50)
        }
    }

    private fun currentCamera(): CameraPose {
        val ar = arActive && arWidth > 0
        val width = if (ar) arWidth else viewportW
        val height = if (ar) arHeight else viewportH
        val yaw: Double = if (ar) {
            Geo.wrapDeg(arWorldAz + offset() + manualOffset + lockOffset)
        } else {
            val base = if (compassAz.isFinite()) compassAz else demoHeading
            Geo.wrapDeg(base + manualOffset + lockOffset)
        }
        return CameraPose(
            yawDeg = yaw,
            pitchDeg = if (ar) arPitch else 0.0,
            rollDeg = if (ar) arRoll else 0.0,
            hfovDeg = if (ar) arHfov else 65.0,
            width = width,
            height = height,
        )
    }

    private fun offset(): Double = if (headingOffset.isFinite()) headingOffset else 0.0

    fun onArPose(worldAzDeg: Double, pitchDeg: Double, rollDeg: Double,
                 hfovDeg: Double, width: Int, height: Int) {
        arWorldAz = Geo.wrapDeg(worldAzDeg)
        arPitch = pitchDeg
        arRoll = rollDeg
        arHfov = if (hfovDeg.isFinite() && hfovDeg > 20) hfovDeg else 65.0
        arWidth = width
        arHeight = height
        if (!arActive) {
            arActive = true
            seedHeading()
        }
    }

    fun onArTracking(active: Boolean) {
        if (!active) arActive = false
    }

    /** A new ARCore session has a new, arbitrary world frame. */
    fun onArSessionReset() {
        arActive = false
        headingOffset = Double.NaN
        manualOffset = 0.0
        lockOffset = 0.0
        synchronized(lock) {
            lockQueue.clear()
            goodLockFrames = 0
        }
        lastLockMs = 0L
    }

    private fun seedHeading() {
        if (!headingOffset.isFinite() && compassAz.isFinite() && compassAccuracy >= 3) {
            headingOffset = Geo.signedDeg(compassAz - arWorldAz)
        }
    }

    fun onCompass(azimuthDeg: Double, accuracy: Int) {
        compassAz = Geo.wrapDeg(azimuthDeg)
        compassAccuracy = accuracy
        if (arActive) seedHeading()
    }

    fun nudgeYaw(deltaDeg: Double) {
        if (!deltaDeg.isFinite()) return
        synchronized(lock) {
            manualOffset += deltaDeg
            lockOffset = 0.0
            lockQueue.clear()
            goodLockFrames = 0
            lastLockMs = 0L
        }
        _state.update { it.copy(alignment = Alignment.MANUAL) }
    }

    fun resetAlignment() {
        synchronized(lock) {
            manualOffset = 0.0
            lockOffset = 0.0
            lockQueue.clear()
            goodLockFrames = 0
            lastLockMs = 0L
            headingOffset = Double.NaN
            if (arActive) seedHeading()
        }
    }

    fun syntheticProfile(): FloatArray? = synchronized(lock) { session?.profile() }

    fun currentYawDeg(): Double = currentCamera().yawDeg

    fun switchPack(path: String) {
        settings.packPath = path
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.IO) {
            try {
                openPack(path, _state.value.location.lat, _state.value.location.lon,
                    _state.value.demoLocation)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, mode = Mode.NO_DATA, error =
                        t.message ?: "could not open that pack")
                }
            }
        }
    }

    fun applySkylineOffset(offsetDeg: Double, score: Double) {
        if (!arActive || !offsetDeg.isFinite() || score < 0.6) return
        synchronized(lock) {
            lockQueue.addLast(offsetDeg.coerceIn(-12.0, 12.0))
            while (lockQueue.size > 5) lockQueue.removeFirst()
            val median = lockQueue.sorted()[lockQueue.size / 2]
            goodLockFrames++
            val step = median.coerceIn(-0.5, 0.5) * 0.7
            lockOffset = (lockOffset + step).coerceIn(-25.0, 25.0)
            if (goodLockFrames >= 3) lastLockMs = SystemClock.elapsedRealtime()
        }
    }

    fun setViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportW = width
            viewportH = height
        }
    }

    fun onLocationFix(lat: Double, lon: Double, accuracy: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        lastFix = LatLon(lat, lon)
        compassTracker.updateLocation(lat, lon, _state.value.altitude.takeIf { it.isFinite() } ?: 0.0)
        val wasDemo = _state.value.demoLocation
        _state.update {
            it.copy(
                location = LatLon(lat, lon),
                demoLocation = false,
                locationAccuracy = accuracy,
            )
        }
        val current = synchronized(lock) { session }
        if (current != null && current.manifest.contains(lat, lon)) {
            if (wasDemo || current.distanceFromOrigin(lat, lon) > 1500) {
                reloadAt(lat, lon, demo = false)
            }
        } else {
            synchronized(lock) {
                session?.close()
                session = null
            }
            reloadAt(lat, lon, demo = false)
        }
    }

    private fun reloadAt(lat: Double, lon: Double, demo: Boolean) {
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.IO) {
            val path = settings.packPath ?: repo.installed().firstOrNull()?.path ?: return@launch
            val db = runCatching { PackDatabase(java.io.File(path)) }.getOrNull() ?: return@launch
            val manifest = runCatching { db.manifest }.getOrElse {
                db.close()
                return@launch
            }
            if (!manifest.contains(lat, lon)) {
                db.close()
                val candidate = repo.installed().firstOrNull {
                    it.manifest.contains(lat, lon) && it.path != path
                }
                if (candidate != null) {
                    settings.packPath = candidate.path
                    try {
                        openPack(candidate.path, lat, lon, demo = false)
                    } catch (t: Throwable) {
                        updateIndexAt(lat, lon)
                        _state.update {
                            it.copy(mode = Mode.APPROXIMATE,
                                error = t.message ?: "could not open the covering pack")
                        }
                    }
                } else {
                    updateIndexAt(lat, lon)
                }
                return@launch
            }
            val newSession = runCatching {
                TerrainSession.open(db, lat, lon, settings.eyeHeight.toDouble(), density)
            }.getOrNull()
            if (newSession == null) {
                // TerrainSession.open owns and closes the database on failure.
                updateIndexAt(lat, lon)
                return@launch
            }
            synchronized(lock) {
                session?.close()
                session = newSession
            }
            compassTracker.updateLocation(lat, lon, newSession.eyeAltitude)
            _state.update {
                it.copy(mode = Mode.TERRAIN_LOCKED, pack = manifest,
                    altitude = newSession.eyeAltitude, error = null,
                    placeCount = newSession.placeCount, demoLocation = demo)
            }
        }
    }

    private suspend fun updateIndexAt(lat: Double, lon: Double) {
        val places = withContext(Dispatchers.Default) { worldIndex.nearby(lat, lon, 170.0, 350) }
        indexPlaces = places
        _state.update {
            it.copy(
                mode = if (places.isEmpty()) Mode.NO_DATA else Mode.APPROXIMATE,
                pack = null,
                altitude = Double.NaN,
                placeCount = places.size,
                demoLocation = false,
            )
        }
    }

    fun selectLabel(label: LabelItem?) {
        selectedId = label?.place?.id
        _state.update { it.copy(selected = label) }
    }

    fun clearSelection() {
        selectedId = null
        _state.update { it.copy(selected = null) }
    }

    fun setShowHidden(show: Boolean) {
        settings.showHidden = show
        _state.update { it.copy(showHidden = show) }
    }

    fun refreshIndex() {
        scope.launch { updateIndexAt(_state.value.location.lat, _state.value.location.lon) }
    }

    fun eyeHeight(): Double = settings.eyeHeight.toDouble()
}
