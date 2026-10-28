package app.vantage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.vantage.UiState
import app.vantage.ar.ArController
import app.vantage.engine.EngineState
import app.vantage.geo.Geo
import app.vantage.model.Alignment as AlignState
import app.vantage.model.LabelItem
import app.vantage.model.Mode
import app.vantage.ui.theme.VantageColors
import kotlinx.coroutines.delay
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.tan

@Composable
fun ArScreen(
    state: EngineState,
    ui: UiState,
    arController: ArController,
    profileSnapshot: () -> FloatArray?,
    onNudge: (Double) -> Unit,
    onSelect: (LabelItem?) -> Unit,
    onList: () -> Unit,
    onPacks: () -> Unit,
    onReset: () -> Unit,
    onCapture: () -> Unit,
    onRequestPermissions: () -> Unit,
    onViewport: (Int, Int) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var profile by remember { mutableStateOf<FloatArray?>(null) }
    var arTimedOut by remember { mutableStateOf(false) }
    val useAr = ui.arAvailable && ui.cameraGranted
    val showAr = useAr && !arTimedOut

    LaunchedEffect(state.pack?.id, state.terrainRadiusKm, state.location) {
        profile = profileSnapshot()
    }

    LaunchedEffect(useAr, state.arActive) {
        if (!useAr) {
            arTimedOut = true
        } else if (state.arActive) {
            arTimedOut = false
        } else {
            arTimedOut = false
            delay(4000)
            if (!state.arActive) arTimedOut = true
        }
    }

    DisposableEffect(showAr, lifecycleOwner) {
        if (showAr) {
            arController.onResume()
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> arController.onResume()
                    Lifecycle.Event.ON_PAUSE -> arController.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                arController.onPause()
            }
        } else {
            arController.onPause()
            onDispose { }
        }
    }
    val frame = state.frame
    val latestFrame by rememberUpdatedState(frame)
    val hfov = frame?.hfovDeg ?: 65.0

    Box(
        Modifier
            .fillMaxSize()
            .background(VantageColors.Background)
            .onSizeChanged {
                viewport = it
                if (it.width > 0 && it.height > 0) onViewport(it.width, it.height)
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val currentFov = latestFrame?.hfovDeg ?: 65.0
                    onNudge(-drag.x / size.width.toFloat() * currentFov)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val hit = hitTest(latestFrame, offset.x, offset.y, 52.dp.toPx())
                    onSelect(hit)
                }
            },
    ) {
        if (showAr) {
            AndroidView(
                factory = { arController.view },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PanoramaBackground(
                profile = profile,
                headingDeg = state.frame?.headingDeg ?: 0.0,
                pitchDeg = state.frame?.pitchDeg ?: 0.0,
                hfovDeg = hfov,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Legibility scrims.
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC04080B), Color(0x6604080B), Color(0x0004080B)),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x0004080B), Color(0x9904080B), Color(0xE604080B)),
                    ),
                ),
        )

        LabelLayer(
            frame = frame,
            selectedId = state.selected?.place?.id,
            showHidden = state.showHidden,
            modifier = Modifier.fillMaxSize(),
        )

        if (viewport.width > 0 && frame != null) {
            ElevationTicks(
                pitchDeg = frame.pitchDeg,
                hfovDeg = hfov,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    StatusPill(state.mode, state.alignment)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        when {
                            state.mode == Mode.APPROXIMATE -> "Index only · no terrain pack"
                            state.mode == Mode.LOADING -> "Building the horizon…"
                            state.mode == Mode.NO_DATA -> "No terrain for this view"
                            else -> state.pack?.name ?: "Terrain"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VantageColors.Muted,
                        fontSize = 9.sp,
                    )
                }
                AlignmentChip(state.alignment, state.compassAccuracy)
            }
            Spacer(Modifier.height(10.dp))
            HeadingTape(state.frame?.headingDeg ?: 0.0)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(java.util.Locale.getDefault(), "%.0f° %s",
                        state.frame?.headingDeg ?: 0.0,
                        Geo.compassLabel(state.frame?.headingDeg ?: 0.0)),
                    style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Ink,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.altitude.isFinite()) "${state.altitude.toInt()} m eye" else "— m",
                    style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Muted,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.weight(1f))
                if (state.demoLocation) {
                    Text(
                        "DEMO VIEWPOINT",
                        style = MaterialTheme.typography.labelSmall,
                        color = VantageColors.Amber,
                        fontSize = 8.sp,
                    )
                }
            }
        }

        // Centre reticle.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(4.dp)
                .background(Color(0x99EDF3F7), CircleShape),
        )

        if (state.mode == Mode.LOADING) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = VantageColors.Amber, strokeWidth = 2.dp)
                Spacer(Modifier.height(14.dp))
                Text("Building the horizon", style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Muted, fontSize = 9.sp)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            if (state.mode == Mode.APPROXIMATE) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(Color(0xCC131B14), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Terrain not downloaded for this view",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VantageColors.Muted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onPacks) {
                        Text("Get terrain", color = VantageColors.Amber,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (!showAr) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(Color(0x9910171D), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LocationOn, null, tint = VantageColors.Glacier,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ui.cameraGranted) "AR unavailable · compass panorama"
                        else "Camera off · compass panorama",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VantageColors.Muted, fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = if (ui.cameraGranted) onPacks else onRequestPermissions) {
                        Text(if (ui.cameraGranted) "Terrain" else "Camera",
                            color = VantageColors.Glacier,
                            style = MaterialTheme.typography.labelLarge, fontSize = 12.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.List, "List view", onList)
                GlassIconButton(Icons.Rounded.Menu, "Terrain packs", onPacks)
                GlassIconButton(Icons.Rounded.Refresh, "Reset alignment", onReset)
                GlassIconButton(CaptureGlyph, "Capture", onCapture)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                state.status.ifBlank { "30 m surface model" },
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim,
                fontSize = 8.sp,
            )
        }
    }
}

@Composable
private fun HeadingTape(heading: Double) {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val cx = size.width / 2f
        val pxPerDeg = size.width / 74f
        val base = floor(heading - 38).toInt()
        val labelStyle = TextStyle(
            fontSize = 9.sp,
            color = VantageColors.Muted,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
        )
        for (d in base..(base + 76)) {
            val x = cx + ((d - heading).toFloat() * pxPerDeg)
            if (x < 6f || x > size.width - 6f) continue
            val norm = ((d % 360) + 360) % 360
            val major = norm % 15 == 0
            drawLine(
                if (major) Color(0x66FFFFFF) else Color(0x26FFFFFF),
                start = Offset(x, size.height - if (major) 16f else 9f),
                end = Offset(x, size.height - 2f),
                strokeWidth = if (major) 1.4f else 1f,
            )
            if (major) {
                val label = when (norm) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> "$norm"
                }
                val layout = measurer.measure(label, style = labelStyle, maxLines = 1)
                drawText(
                    layout,
                    topLeft = Offset(x - layout.size.width / 2f, 0f),
                )
            }
        }
        drawLine(
            VantageColors.Amber,
            start = Offset(cx, 0f),
            end = Offset(cx, 7.dp.toPx()),
            strokeWidth = 2f,
        )
    }
}

@Composable
private fun ElevationTicks(pitchDeg: Double, hfovDeg: Double, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    Canvas(modifier) {
        val f = (size.width * 0.5f) / tan(Math.toRadians(hfovDeg / 2.0)).toFloat()
        val style = TextStyle(fontSize = 8.sp, color = Color(0x80FFFFFF),
            fontFamily = FontFamily.Monospace)
        for (deg in -10..40 step 5) {
            val y = (size.height * 0.5f - f * tan(Math.toRadians(deg.toDouble()) -
                Math.toRadians(pitchDeg))).toFloat()
            if (y < 150.dp.toPx() || y > size.height - 190.dp.toPx()) continue
            drawLine(
                Color(0x33FFFFFF),
                Offset(size.width - 12.dp.toPx(), y),
                Offset(size.width - 4.dp.toPx(), y),
                strokeWidth = 1f,
            )
            val layout = measurer.measure("$deg°", style = style, maxLines = 1)
            drawText(
                layout,
                topLeft = Offset(size.width - 22.dp.toPx() - layout.size.width, y - layout.size.height / 2f),
            )
        }
    }
}
