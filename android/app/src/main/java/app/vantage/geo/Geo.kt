package app.vantage.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val REFRACTION_K = 0.13
    const val EFFECTIVE_RADIUS_M = EARTH_RADIUS_M / (1.0 - REFRACTION_K)
    const val DEG = Math.PI / 180.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    fun bearingRad(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) + 2 * Math.PI) % (2 * Math.PI)
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        Math.toDegrees(bearingRad(lat1, lon1, lat2, lon2))

    fun curvatureDropM(distM: Double): Double =
        distM * distM / (2.0 * EFFECTIVE_RADIUS_M)

    fun elevationAngleDeg(targetM: Double, eyeM: Double, distM: Double): Double =
        Math.toDegrees(atan2(targetM - eyeM - curvatureDropM(distM), distM))

    fun wrapDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    fun signedDeg(deg: Double): Double {
        var d = (deg + 180.0) % 360.0
        if (d < 0) d += 360.0
        return d - 180.0
    }

    fun wrapPi(rad: Double): Double {
        var r = rad
        while (r > Math.PI) r -= 2 * Math.PI
        while (r < -Math.PI) r += 2 * Math.PI
        return r
    }

    fun compassLabel(azDeg: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((wrapDeg(azDeg) / 22.5) + 0.5).toInt() % 16]
    }

    fun formatDistance(metres: Double): String = when {
        metres < 1000 -> "${metres.toInt()} m"
        metres < 10000 -> String.format(java.util.Locale.getDefault(), "%.1f km", metres / 1000)
        else -> "${(metres / 1000).toInt()} km"
    }

    fun formatElevation(metres: Double): String =
        if (metres < -1000) "—" else "${Math.round(metres).toInt()} m"

    fun prominenceGuess(placeEle: Double, population: Int): Double =
        log10(max(population, 1).toDouble()) / 7.0 * 0.6 + (placeEle / 5000.0).coerceIn(0.0, 1.0) * 0.4
}
