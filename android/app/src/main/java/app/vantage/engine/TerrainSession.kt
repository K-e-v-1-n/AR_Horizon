package app.vantage.engine

import app.vantage.core.NativeCore
import app.vantage.data.PackDatabase
import app.vantage.data.PackManifest
import app.vantage.data.TerrainAssembler
import app.vantage.geo.Geo
import app.vantage.model.CameraPose
import app.vantage.model.LabelItem
import app.vantage.model.Place
import app.vantage.model.labelsFromNative

/**
 * One loaded pack, one terrain window, one horizon. All native handles are
 * owned here and only touched from the engine dispatcher.
 */
class TerrainSession private constructor(
    private val db: PackDatabase,
    val manifest: PackManifest,
    val places: List<Place>,
    private val density: Float,
) : AutoCloseable {
    var originLat = 0.0
        private set
    var originLon = 0.0
        private set
    var eyeAltitude = 0.0
        private set

    private var terrainHandle = 0L
    private var silhouetteHandle = 0L
    private var placesHandle = 0L
    private var silhouette = FloatArray(0)
    private var ready = false
    private val widths = FloatArray(places.size)

    val placeCount: Int get() = places.size

    init {
        for (i in places.indices) {
            val p = places[i]
            widths[i] = ((p.name.length.coerceAtMost(40) * 7.4f) + 30f) * density
        }
    }

    fun profile(): FloatArray? =
        if (ready && silhouette.isNotEmpty()) silhouette.copyOf() else null

    fun setPose(lat: Double, lon: Double, eyeHeight: Double): Boolean {
        if (!manifest.contains(lat, lon)) return false
        val fine = TerrainAssembler.assemble(db, 0, lat, lon, 30.0) ?: return false
        val coarse = TerrainAssembler.assemble(db, 1, lat, lon, manifest.radiusKm) ?: fine

        if (terrainHandle == 0L) terrainHandle = NativeCore.nativeCreateTerrain()
        if (terrainHandle == 0L) return false
        if (NativeCore.nativeSetGrid(
                terrainHandle, 0, fine.data, fine.latNorth, fine.lonWest,
                fine.dlat, fine.dlon, fine.width, fine.height,
            ) != 0
        ) return false
        if (coarse !== fine) {
            // A missing coarse upload only shortens the far field; keep the fine grid.
            NativeCore.nativeSetGrid(
                terrainHandle, 1, coarse.data, coarse.latNorth, coarse.lonWest,
                coarse.dlat, coarse.dlon, coarse.width, coarse.height,
            )
        }

        if (silhouetteHandle == 0L) silhouetteHandle = NativeCore.nativeCreateSilhouette(3600)
        if (silhouetteHandle == 0L) return false
        val rc = NativeCore.nativeComputeSilhouette(terrainHandle, silhouetteHandle, lat, lon, eyeHeight)
        if (rc != 0) return false
        silhouette = NativeCore.nativeSilhouetteElev(silhouetteHandle) ?: FloatArray(0)
        originLat = lat
        originLon = lon

        val ground = NativeCore.nativeTerrainHeight(terrainHandle, lat, lon)
        eyeAltitude = if (ground.isFinite()) ground + eyeHeight else Double.NaN
        ready = true
        if (placesHandle == 0L && places.isNotEmpty()) {
            placesHandle = NativeCore.nativeCreatePlaces(
                IntArray(places.size) { places[it].nativeKind },
                DoubleArray(places.size) { places[it].lat },
                DoubleArray(places.size) { places[it].lon },
                FloatArray(places.size) { places[it].ele.toFloat() },
                LongArray(places.size) { places[it].population.toLong() },
                FloatArray(places.size) { places[it].rank },
                Array(places.size) { places[it].name },
                Array(places.size) { places[it].localName },
                Array(places.size) { places[it].wikidata ?: "" },
            )
            if (placesHandle == 0L) return false
        }
        return true
    }

    fun solve(cam: CameraPose): List<LabelItem> {
        if (!ready || terrainHandle == 0L || silhouetteHandle == 0L || placesHandle == 0L) {
            return emptyList()
        }
        val packed = NativeCore.nativeSolveLabels(
            terrainHandle, silhouetteHandle, placesHandle,
            originLat, originLon, eyeAltitude,
            cam.yawDeg, cam.pitchDeg, cam.rollDeg, cam.hfovDeg,
            cam.width, cam.height, widths, 35,
        ) ?: return emptyList()
        return labelsFromNative(packed, places)
    }

    fun distanceFromOrigin(lat: Double, lon: Double): Double =
        Geo.distanceM(originLat, originLon, lat, lon)

    override fun close() {
        if (placesHandle != 0L) NativeCore.nativeDestroyPlaces(placesHandle)
        if (silhouetteHandle != 0L) NativeCore.nativeDestroySilhouette(silhouetteHandle)
        if (terrainHandle != 0L) NativeCore.nativeDestroyTerrain(terrainHandle)
        placesHandle = 0
        silhouetteHandle = 0
        terrainHandle = 0
        db.close()
    }

    companion object {
        fun open(db: PackDatabase, lat: Double, lon: Double, eyeHeight: Double,
                 density: Float): TerrainSession? {
            var session: TerrainSession? = null
            return try {
                val created = TerrainSession(db, db.manifest, db.places(), density)
                session = created
                if (!created.setPose(lat, lon, eyeHeight)) {
                    created.close()
                    session = null
                }
                session
            } catch (t: Throwable) {
                if (session != null) session.close() else db.close()
                throw t
            }
        }
    }
}
