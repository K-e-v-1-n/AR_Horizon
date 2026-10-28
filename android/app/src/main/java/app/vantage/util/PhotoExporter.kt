package app.vantage.util

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoExporter {
    fun capture(activity: Activity, onDone: (Boolean) -> Unit) {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) {
            onDone(false)
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    if (result != PixelCopy.SUCCESS) {
                        onDone(false)
                        return@request
                    }
                    burnAttribution(bitmap)
                    val ok = runCatching { save(activity, bitmap) }.getOrDefault(false)
                    onDone(ok)
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: Throwable) {
            onDone(false)
        }
    }

    private fun burnAttribution(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            textSize = (bitmap.width * 0.018f).coerceAtLeast(18f)
            letterSpacing = 0.04f
        }
        val text = "AR_Horizon · 30 m surface model · © OpenStreetMap · Copernicus DEM"
        val y = bitmap.height - paint.textSize * 1.2f
        val w = paint.measureText(text)
        val bg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        canvas.drawRect(0f, y - paint.textSize, w + paint.textSize, y + paint.textSize * 0.4f, bg)
        canvas.drawText(text, paint.textSize * 0.5f, y, paint)
    }

    private fun save(activity: Activity, bitmap: Bitmap): Boolean {
        val name = "AR_Horizon_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AR_Horizon")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        }
        @Suppress("DEPRECATION")
        val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AR_Horizon")
        dir.mkdirs()
        return FileOutputStream(File(dir, name)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.let { true }
    }
}
