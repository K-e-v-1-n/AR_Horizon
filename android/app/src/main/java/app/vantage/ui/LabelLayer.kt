package app.vantage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.geo.Geo
import app.vantage.model.LabelFrame
import app.vantage.model.LabelItem
import app.vantage.model.PlaceKind
import app.vantage.ui.theme.VantageColors

@Composable
fun LabelLayer(
    frame: LabelFrame?,
    selectedId: Int?,
    showHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    Canvas(modifier.fillMaxSize()) {
        val labels = frame?.labels ?: return@Canvas
        val pad = 10.dp.toPx()
        for (label in labels) {
            if (!showHidden && !label.visible) continue
            val kind = label.place.kind
            val baseColor = when (kind) {
                PlaceKind.PEAK -> VantageColors.Peak
                PlaceKind.CITY, PlaceKind.TOWN -> VantageColors.Amber
                PlaceKind.VILLAGE, PlaceKind.HAMLET -> VantageColors.AmberSoft
                PlaceKind.WATER -> VantageColors.Water
                PlaceKind.LANDMARK -> VantageColors.Landmark
            }
            val color = if (label.visible) baseColor else baseColor.copy(alpha = 0.55f)
            val isPeak = kind == PlaceKind.PEAK
            val fontSize = if (isPeak) 15.5.sp else 14.sp
            val weight = if (isPeak) FontWeight.SemiBold else FontWeight.Medium
            val style = TextStyle(
                fontSize = fontSize,
                fontWeight = weight,
                fontStyle = if (kind == PlaceKind.WATER) FontStyle.Italic else FontStyle.Normal,
                color = color,
                letterSpacing = 0.1.sp,
                shadow = Shadow(
                    color = Color(0xE6000000),
                    offset = Offset(1.6f, 1.6f),
                    blurRadius = 3f,
                ),
            )
            val text: AnnotatedString = buildAnnotatedString {
                append(label.place.name)
                if (isPeak && label.place.ele > 0) {
                    withStyle(
                        SpanStyle(
                            fontSize = 10.sp,
                            color = color.copy(alpha = 0.72f),
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.4.sp,
                        ),
                    ) {
                        append("  ${Math.round(label.place.ele)} m")
                    }
                }
                if (!label.visible) {
                    withStyle(
                        SpanStyle(
                            fontSize = 9.sp,
                            color = color.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.8.sp,
                        ),
                    ) {
                        append("  hidden")
                    }
                }
            }
            val layout = measurer.measure(text, style = style, maxLines = 1)
            val margin = 12.dp.toPx()
            val maxX = (size.width - layout.size.width - margin).coerceAtLeast(margin)
            val x = (label.x - layout.size.width / 2f).coerceIn(margin, maxX)
            val minY = 94.dp.toPx()
            val maxY = (size.height - 120.dp.toPx() - layout.size.height)
                .coerceAtLeast(minY)
            val y = (label.y - layout.size.height - pad).coerceIn(minY, maxY)
            if (selectedId == label.place.id) {
                drawRoundRect(
                    color = VantageColors.Amber.copy(alpha = 0.16f),
                    topLeft = Offset(x - 9.dp.toPx(), y - 5.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        layout.size.width + 18.dp.toPx(),
                        layout.size.height + 10.dp.toPx(),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
            }
            drawText(layout, topLeft = Offset(x, y))
            val minDotY = 80.dp.toPx()
            val maxDotY = (size.height - 96.dp.toPx()).coerceAtLeast(minDotY)
            val dotY = label.y.coerceIn(minDotY, maxDotY)
            val minDotX = 14.dp.toPx()
            val maxDotX = (size.width - 14.dp.toPx()).coerceAtLeast(minDotX)
            val dotX = label.x.coerceIn(minDotX, maxDotX)
            drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(dotX, dotY))
            drawLine(
                color.copy(alpha = 0.7f),
                start = Offset(dotX, dotY + 3.dp.toPx()),
                end = Offset(dotX, dotY + 8.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            if (!label.visible) {
                drawCircle(
                    color = color.copy(alpha = 0.55f),
                    radius = 7.dp.toPx(),
                    center = Offset(dotX, dotY),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

fun hitTest(frame: LabelFrame?, x: Float, y: Float, radiusPx: Float): LabelItem? {
    val labels = frame?.labels ?: return null
    return labels.minByOrNull { label ->
        val dx = label.x - x
        val dy = label.y - y
        dx * dx + dy * dy
    }?.takeIf { label ->
        val dx = label.x - x
        val dy = label.y - y
        dx * dx + dy * dy <= radiusPx * radiusPx
    }
}
