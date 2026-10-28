package app.vantage.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import app.vantage.core.NativeCore
import app.vantage.engine.VantageEngine
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt

class ArController(
    private val context: Context,
    private val engine: VantageEngine,
) {
    val view: GLSurfaceView = GLSurfaceView(context)
    private var session: Session? = null
    private val renderer = ArRenderer(context, engine)
    private var resumed = false

    init {
        view.setEGLContextClientVersion(2)
        view.setRenderer(renderer)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.preserveEGLContextOnPause = true
    }

    fun availability(): ArCoreApk.Availability =
        runCatching { ArCoreApk.getInstance().checkAvailability(context) }
            .getOrDefault(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE)

    fun requestInstall(activity: android.app.Activity): Boolean =
        runCatching { ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED }
            .getOrDefault(false)

    fun onResume() {
        if (resumed) return
        val s = runCatching {
            session ?: Session(context).also {
                session = it
                renderer.session = it
                configure(it)
                engine.onArSessionReset()
            }
        }.getOrNull() ?: return
        runCatching { s.resume() }
        renderer.displayRotation = (context as? android.app.Activity)
            ?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        view.onResume()
        view.post { renderer.displayRotation = view.display?.rotation ?: renderer.displayRotation }
        resumed = true
    }

    fun onPause() {
        if (!resumed) return
        view.onPause()
        runCatching { session?.pause() }
        resumed = false
        engine.onArTracking(false)
    }

    fun onDestroy() {
        runCatching { session?.close() }
        session = null
        renderer.session = null
        engine.onArTracking(false)
    }

    private fun configure(session: Session) {
        val config = Config(session).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            focusMode = Config.FocusMode.AUTO
            lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            depthMode = Config.DepthMode.DISABLED
        }
        runCatching { session.configure(config) }
    }

    private class ArRenderer(
        private val context: Context,
        private val engine: VantageEngine,
    ) : GLSurfaceView.Renderer, android.graphics.SurfaceTexture.OnFrameAvailableListener {
        @Volatile var session: Session? = null
        private val bg = BackgroundRenderer()
        private var surfaceTexture: android.graphics.SurfaceTexture? = null
        private var lastTimestamp = 0L
        private var lastFitMs = 0L
        private var viewWidth = 0
        private var viewHeight = 0
        @Volatile var displayRotation = Surface.ROTATION_0
        private var lastTracking = false
        private val fitting = AtomicBoolean(false)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.02f, 0.03f, 0.04f, 1f)
            bg.createOnGlThread()
            surfaceTexture = android.graphics.SurfaceTexture(bg.textureId).apply {
                setOnFrameAvailableListener(this@ArRenderer)
            }
            session?.setCameraTextureName(bg.textureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            GLES20.glViewport(0, 0, width, height)
            if (viewWidth > 0 && viewHeight > 0) engine.setViewport(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val s = session ?: return
            if (viewWidth == 0 || viewHeight == 0) return
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val frame = try {
                s.setDisplayGeometry(displayRotation, viewWidth, viewHeight)
                s.update()
            } catch (_: Exception) {
                engine.onArTracking(false)
                return
            }
            surfaceTexture?.updateTexImage()
            if (frame.timestamp == lastTimestamp) return
            lastTimestamp = frame.timestamp
            bg.draw(frame)

            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING
            if (tracking != lastTracking) {
                lastTracking = tracking
                engine.onArTracking(tracking)
            }
            if (!tracking) return

            val pose = camera.displayOrientedPose
            val q = pose.getRotationQuaternion()
            val forward = rotate(q, 0f, 0f, -1f)
            val up = rotate(q, 0f, 1f, 0f)
            val worldAz = Math.toDegrees(atan2(forward[0].toDouble(), -forward[2].toDouble()))
            val pitch = Math.toDegrees(asin(forward[1].toDouble().coerceIn(-1.0, 1.0)))
            val roll = rollDeg(forward, up)
            val hfov = horizontalFov(camera, viewWidth, viewHeight)

            engine.onArPose(worldAz, pitch, roll, hfov, viewWidth, viewHeight)
            maybeSkylineLock(frame, camera, s)
        }

        private fun maybeSkylineLock(frame: Frame, camera: com.google.ar.core.Camera, s: Session) {
            val now = System.currentTimeMillis()
            if (now - lastFitMs < 150) return
            if (!fitting.compareAndSet(false, true)) return
            lastFitMs = now
            try {
                val profile = engine.syntheticProfile() ?: return
                val image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
                try {
                    val samples = SkylineSampler.capture(image, camera.imageIntrinsics) ?: return
                    val sensorPose = camera.pose
                    val sq = sensorPose.getRotationQuaternion()
                    val sf = rotate(sq, 0f, 0f, -1f)
                    val sensorPitch = Math.toDegrees(asin(sf[1].toDouble().coerceIn(-1.0, 1.0)))
                    // The synthetic horizon is in true azimuth. Use the fused
                    // true camera heading (AR world + compass/manual/lock),
                    // never the raw AR world azimuth.
                    val camAzRad = Math.toRadians(engine.currentYawDeg())
                    val pitchRad = Math.toRadians(sensorPitch)
                    val obsAz = FloatArray(samples.count) { (camAzRad + samples.azRel[it]).toFloat() }
                    val obsEl = FloatArray(samples.count) { (pitchRad + samples.elevRel[it]).toFloat() }
                    val fitted = NativeCore.nativeFitSkyline(profile, obsAz, obsEl, samples.weight, 12f)
                    if (fitted != null && fitted.size >= 2) {
                        engine.applySkylineOffset(fitted[0].toDouble(), fitted[1].toDouble())
                    }
                } finally {
                    image.close()
                }
            } finally {
                fitting.set(false)
            }
        }

        private fun horizontalFov(camera: com.google.ar.core.Camera, width: Int, height: Int): Double {
            val intr = camera.textureIntrinsics
            val tw = intr.imageDimensions[0].toFloat()
            val th = intr.imageDimensions[1].toFloat()
            if (tw <= 0f || th <= 0f) return 65.0
            val fx = intr.focalLength[0]
            val viewAspect = width.toFloat() / height.toFloat()
            val texAspect = tw / th
            val visibleW = if (viewAspect >= texAspect) tw else th * viewAspect
            val fxScreen = fx * (width / visibleW)
            return Math.toDegrees(2.0 * atan(width.toDouble() / (2.0 * fxScreen)))
        }

        private fun rollDeg(forward: FloatArray, up: FloatArray): Double {
            var rx = -forward[2]
            var ry = 0f
            var rz = forward[0]
            val len = sqrt((rx * rx + ry * ry + rz * rz).toDouble()).toFloat()
            if (len < 1e-4f) return 0.0
            rx /= len; ry /= len; rz /= len
            val ux = ry * forward[2] - rz * forward[1]
            val uy = rz * forward[0] - rx * forward[2]
            val uz = rx * forward[1] - ry * forward[0]
            val dRight = up[0] * rx + up[1] * ry + up[2] * rz
            val dUp = up[0] * ux + up[1] * uy + up[2] * uz
            return Math.toDegrees(atan2(dRight.toDouble(), dUp.toDouble()))
        }

        private fun rotate(q: FloatArray, x: Float, y: Float, z: Float): FloatArray {
            val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
            val tx = 2f * (qy * z - qz * y)
            val ty = 2f * (qz * x - qx * z)
            val tz = 2f * (qx * y - qy * x)
            return floatArrayOf(
                x + qw * tx + qy * tz - qz * ty,
                y + qw * ty + qz * tx - qx * tz,
                z + qw * tz + qx * ty - qy * tx,
            )
        }

        override fun onFrameAvailable(surfaceTexture: android.graphics.SurfaceTexture?) {
            // Rendering is continuous; no explicit request needed.
        }
    }
}
