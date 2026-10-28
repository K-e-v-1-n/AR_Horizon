package app.vantage.core

import androidx.annotation.Keep

/**
 * JNI bridge to the portable C++ core. All methods are called from the engine
 * dispatcher; the native side is not thread-safe.
 */
@Keep
object NativeCore {
    init {
        System.loadLibrary("vantage_core")
    }

    external fun nativeCreateTerrain(): Long
    external fun nativeDestroyTerrain(handle: Long)
    external fun nativeSetGrid(
        handle: Long, level: Int, data: ShortArray,
        latNorth: Double, lonWest: Double, dlat: Double, dlon: Double,
        width: Int, height: Int,
    ): Int

    external fun nativeHasGrid(handle: Long, level: Int): Int
    external fun nativeTerrainHeight(handle: Long, lat: Double, lon: Double): Double

    external fun nativeCreateSilhouette(bins: Int): Long
    external fun nativeDestroySilhouette(handle: Long)
    external fun nativeComputeSilhouette(
        terrain: Long, silhouette: Long, lat: Double, lon: Double, eye: Double,
    ): Int

    external fun nativeSilhouetteElev(handle: Long): FloatArray?

    external fun nativeCreatePlaces(
        kinds: IntArray, lat: DoubleArray, lon: DoubleArray, ele: FloatArray,
        pop: LongArray, rank: FloatArray, names: Array<String>,
        regions: Array<String>, wikis: Array<String>,
    ): Long

    external fun nativeDestroyPlaces(handle: Long)

    external fun nativeSolveLabels(
        terrain: Long, silhouette: Long, places: Long,
        lat: Double, lon: Double, eye: Double,
        yawDeg: Double, pitchDeg: Double, rollDeg: Double, hfovDeg: Double,
        width: Int, height: Int, nameWidths: FloatArray?, maxLabels: Int,
    ): FloatArray?

    external fun nativeFitSkyline(
        synthetic: FloatArray, obsAz: FloatArray, obsElev: FloatArray,
        obsWeight: FloatArray, maxOffsetDeg: Float,
    ): FloatArray?

    external fun nativeVersion(): String

    const val STRIDE = 12

    fun version(): String = runCatching { nativeVersion() }.getOrDefault("unavailable")
}
