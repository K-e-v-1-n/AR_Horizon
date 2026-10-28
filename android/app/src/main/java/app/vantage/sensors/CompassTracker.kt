package app.vantage.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.vantage.geo.Geo
import kotlin.math.atan2

/**
 * Camera heading from the rotation-vector sensor. Magnetometer noise means this
 * is a coarse prior (±5°), never the truth: the AR pose and skyline lock carry
 * short-term accuracy.
 */
class CompassTracker(
    context: Context,
    private val onHeading: (azimuthDeg: Double, accuracy: Int) -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = FloatArray(9)
    private val values = FloatArray(3)
    private var smoothed = Double.NaN
    private var declination = 0.0
    private var lat = 45.98331
    private var lon = 7.78470
    private var alt = 3000.0
    private var lastAccuracy = 0
    private var listener: SensorEventListener? = null

    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun updateLocation(lat: Double, lon: Double, altitude: Double) {
        this.lat = lat
        this.lon = lon
        this.alt = altitude
        runCatching {
            declination = GeomagneticField(
                lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
        }
    }

    fun start() {
        if (sensor == null) return
        listener = this
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (event.values.size < 3) return
        System.arraycopy(event.values, 0, values, 0, 3)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        // Camera looks along device -Z; world = R * device with X east, Y north.
        val ex = -rotation[2].toDouble()
        val ny = -rotation[5].toDouble()
        var az = Math.toDegrees(atan2(ex, ny)) + declination
        az = Geo.wrapDeg(az)
        if (smoothed.isNaN()) {
            smoothed = az
        } else {
            val delta = Geo.signedDeg(az - smoothed)
            smoothed = Geo.wrapDeg(smoothed + delta * 0.18)
        }
        val acc = event.accuracy
        if (acc != lastAccuracy) lastAccuracy = acc
        onHeading(smoothed, acc)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        lastAccuracy = accuracy
    }
}
