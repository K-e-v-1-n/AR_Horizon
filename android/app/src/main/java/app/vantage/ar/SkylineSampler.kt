package app.vantage.ar

import android.media.Image
import com.google.ar.core.CameraIntrinsics
import kotlin.math.atan

/**
 * Extracts sky/terrain boundary samples from a downscaled YUV camera frame.
 * The fit itself happens in the portable core.
 */
object SkylineSampler {
    const val COLS = 160
    const val ROWS = 120

    data class Samples(
        val azRel: FloatArray,
        val elevRel: FloatArray,
        val weight: FloatArray,
        val count: Int,
        val azSpanDeg: Double,
    )

    fun capture(image: Image, intrinsics: CameraIntrinsics): Samples? {
        if (image.planes.isEmpty()) return null
        val y = image.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        val iw = image.width
        val ih = image.height
        if (iw < 32 || ih < 32) return null

        val luma = FloatArray(COLS * ROWS)
        for (r in 0 until ROWS) {
            val sy = (r * ih / ROWS).coerceIn(0, ih - 1)
            val rowBase = sy * rowStride
            for (c in 0 until COLS) {
                val sx = (c * iw / COLS).coerceIn(0, iw - 1)
                val idx = rowBase + sx * pixelStride
                luma[r * COLS + c] = if (idx < buf.limit()) (buf.get(idx).toInt() and 0xff).toFloat() else 0f
            }
        }

        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val fx = focal[0]
        val fy = focal[1]
        val cx = principal[0]
        val cy = principal[1]
        if (fx <= 0 || fy <= 0) return null

        val az = ArrayList<Float>(COLS)
        val el = ArrayList<Float>(COLS)
        val wt = ArrayList<Float>(COLS)
        var minAz = Double.MAX_VALUE
        var maxAz = -Double.MAX_VALUE

        for (c in 0 until COLS) {
            val sky = (0 until 14).map { luma[it * COLS + c] }.sorted()
            val skyRef = sky[sky.size / 2]
            if (skyRef < 48f) continue
            var found = -1
            var contrast = 0f
            for (r in 12 until ROWS - 4) {
                val v = luma[r * COLS + c]
                val v1 = luma[(r + 1) * COLS + c]
                val v2 = luma[(r + 2) * COLS + c]
                val drop = skyRef - v
                if (drop > 22f && v < skyRef * 0.72f &&
                    v1 < skyRef * 0.85f && v2 < skyRef * 0.9f
                ) {
                    found = r
                    contrast = drop
                    break
                }
            }
            if (found < 0 || contrast < 24f) continue
            val imgX = c.toDouble() * iw / COLS
            val imgY = found.toDouble() * ih / ROWS
            val ar = atan((imgX - cx) / fx)
            val er = atan((cy - imgY) / fy)
            az += ar.toFloat()
            el += er.toFloat()
            wt += (contrast / 255f).coerceIn(0.15f, 1f)
            minAz = minOf(minAz, ar)
            maxAz = maxOf(maxAz, ar)
        }
        if (az.size < 24) return null
        val span = Math.toDegrees(maxAz - minAz)
        if (span < 12.0) return null
        return Samples(az.toFloatArray(), el.toFloatArray(), wt.toFloatArray(), az.size, span)
    }
}
