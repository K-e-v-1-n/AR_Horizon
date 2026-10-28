package app.vantage.engine

import app.vantage.geo.Geo
import app.vantage.model.CameraPose
import app.vantage.model.LabelItem
import app.vantage.model.Place
import app.vantage.model.PlaceKind
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Index-only labels: bearing, distance and elevation, no terrain occlusion. */
object ApproximateSolver {
    fun solve(
        places: List<Place>, lat: Double, lon: Double, cam: CameraPose,
        density: Float, limit: Int = 32,
    ): List<LabelItem> {
        val f = (cam.width * 0.5) / tan(Math.toRadians(cam.hfovDeg) * 0.5)
        val yaw = Math.toRadians(cam.yawDeg)
        val pitch = Math.toRadians(cam.pitchDeg)
        val candidates = ArrayList<LabelItem>()
        for (p in places) {
            val d = Geo.distanceM(lat, lon, p.lat, p.lon)
            if (d < 50 || d > 180_000) continue
            val az = Geo.bearingRad(lat, lon, p.lat, p.lon)
            val ele = if (p.ele > -1000) p.ele else 0.0
            val elev = Geo.elevationAngleDeg(ele, 0.0, d)
            val rel = Geo.wrapPi(az - yaw)
            if (abs(rel) > Math.toRadians(cam.hfovDeg * 0.75)) continue
            val x = cam.width * 0.5 + f * tan(rel)
            val y = cam.height * 0.5 - f * tan(Math.toRadians(elev) - pitch)
            if (x < -40 || x > cam.width + 40 || y < 80 || y > cam.height - 80) continue
            val w = ((p.name.length.coerceAtMost(34) * 7.4f) + 26f) * density
            candidates += LabelItem(
                place = p,
                x = x.toFloat(),
                y = y.toFloat(),
                azDeg = Math.toDegrees(az),
                elevDeg = elev,
                distM = d,
                targetEle = ele,
                visible = true,
                hasTerrain = false,
                score = score(p, d),
                boxW = w,
                boxH = 22f * density,
            )
        }
        candidates.sortByDescending { it.score }
        val placed = ArrayList<LabelItem>(limit)
        for (c in candidates) {
            if (placed.size >= limit) break
            val overlap = placed.any {
                abs(c.x - it.x) * 2 < c.boxW + it.boxW + 10f &&
                    abs(c.y - it.y) * 2 < c.boxH + it.boxH + 8f
            }
            if (!overlap) placed += c
        }
        return placed
    }

    private fun score(p: Place, dist: Double): Float {
        val type = when (p.kind) {
            PlaceKind.PEAK -> 0.42f
            PlaceKind.CITY -> 0.44f
            PlaceKind.TOWN -> 0.32f
            PlaceKind.WATER -> 0.22f
            PlaceKind.LANDMARK -> 0.18f
            else -> 0.08f
        }
        val pop = (Math.log10(p.population.toDouble() + 1.0) / 7.0).coerceIn(0.0, 1.0)
        val prox = (1.0 - dist / 160_000.0).coerceIn(0.0, 1.0)
        return (type + pop * 0.3 + p.rank * 0.25 + prox * 0.2).toFloat()
    }
}
