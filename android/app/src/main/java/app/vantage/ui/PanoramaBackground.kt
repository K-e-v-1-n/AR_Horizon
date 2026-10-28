package app.vantage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Synthetic horizon used when ARCore or the camera is unavailable (and on the
 * emulator). The same 3600-bin silhouette the labels use, drawn as sky and
 * terrain, then the normal label overlay sits on top.
 */
@Composable
fun PanoramaBackground(
    profile: FloatArray?,
    headingDeg: Double,
    pitchDeg: Double,
    hfovDeg: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val f = (w * 0.5f) / tan(Math.toRadians(hfovDeg / 2.0)).toFloat()
        val horizonY = h * 0.5f + f * tan(Math.toRadians(pitchDeg)).toFloat()

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF050D15),
                    0.42f to Color(0xFF143142),
                    0.78f to Color(0xFF5F8598),
                    1f to Color(0xFFB9CBD5),
                ),
                startY = 0f,
                endY = (horizonY + h * 0.16f).coerceAtLeast(1f),
            ),
            size = size,
        )

        // A low sun glow, kept subtle.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x33E8D7B8), Color(0x00E8D7B8)),
                center = Offset(w * 0.24f, horizonY * 0.92f),
                radius = w * 0.7f,
            ),
            radius = w * 0.7f,
            center = Offset(w * 0.24f, horizonY * 0.92f),
        )

        if (profile == null || profile.isEmpty()) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF16241E), Color(0xFF080E0B)),
                    startY = horizonY,
                    endY = h,
                ),
                topLeft = Offset(0f, horizonY),
                size = Size(w, h - horizonY),
            )
            drawLine(
                Color(0x66798D80),
                Offset(0f, horizonY),
                Offset(w, horizonY),
                strokeWidth = 1.2f,
            )
            return@Canvas
        }

        val path = Path()
        path.moveTo(0f, h)
        val step = 3f
        var x = 0f
        var started = false
        while (x <= w) {
            val delta = atan(((x - w * 0.5f) / f).toDouble())
            val az = Math.toRadians(headingDeg) + delta
            val idx = floor(((az % (2 * PI)) / (2 * PI)) * profile.size).toInt()
                .coerceIn(0, profile.size - 1)
            val elev = profile[idx].toDouble()
            val y = if (elev.isFinite()) {
                (h * 0.5 - f * tan(elev - Math.toRadians(pitchDeg))).toFloat()
                    .coerceIn(-h * 0.2f, h * 1.1f)
            } else {
                horizonY
            }
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            x += step
        }
        path.lineTo(w, h)
        path.close()

        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF4D6157),
                    0.35f to Color(0xFF22332A),
                    1f to Color(0xFF080E0B),
                ),
                startY = h * 0.15f,
                endY = h,
            ),
        )

        // Ridge line highlight.
        val ridge = Path()
        x = 0f
        var first = true
        while (x <= w) {
            val delta = atan(((x - w * 0.5f) / f).toDouble())
            val az = Math.toRadians(headingDeg) + delta
            val idx = floor(((az % (2 * PI)) / (2 * PI)) * profile.size).toInt()
                .coerceIn(0, profile.size - 1)
            val e = profile[idx].toDouble()
            val y = if (e.isFinite()) {
                (h * 0.5 - f * tan(e - Math.toRadians(pitchDeg))).toFloat()
                    .coerceIn(-h * 0.2f, h * 1.1f)
            } else {
                horizonY
            }
            if (first) { ridge.moveTo(x, y); first = false } else ridge.lineTo(x, y)
            x += 3f
        }
        drawPath(ridge, Color(0x3DB9CDBD), style = Stroke(width = 1.4f))
    }
}
