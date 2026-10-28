package app.vantage.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.abs

class LocationTracker(
    private val context: Context,
    private val onFix: (lat: Double, lon: Double, accuracy: Double, altitude: Double, demo: Boolean) -> Unit,
) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false
    private var lastFixMs = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun start() {
        if (started) return
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        started = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 3000L, 6f, this, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { accept(it) }
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        runCatching { lm.removeUpdates(this) }
        started = false
    }

    override fun onLocationChanged(location: Location) = accept(location)

    private fun accept(location: Location) {
        val now = System.currentTimeMillis()
        if (lastLat.isFinite() && now - lastFixMs < 2000) {
            val jump = distance(lastLat, lastLon, location.latitude, location.longitude)
            if (jump > 100.0) return
        }
        lastFixMs = now
        lastLat = location.latitude
        lastLon = location.longitude
        onFix(location.latitude, location.longitude, location.accuracy.toDouble(),
            location.altitude, false)
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * 6_371_008.8 * kotlin.math.asin(kotlin.math.sqrt(a).coerceIn(0.0, 1.0))
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
