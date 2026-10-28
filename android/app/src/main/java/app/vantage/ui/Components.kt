package app.vantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.model.Alignment as AlignState
import app.vantage.model.Mode
import app.vantage.model.PlaceKind
import app.vantage.ui.theme.VantageColors

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    corner: Int = 24,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(Color(0xE60A1116))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(corner.dp)),
    ) { content() }
}

@Composable
fun StatusPill(mode: Mode, alignment: AlignState, modifier: Modifier = Modifier) {
    val (color, text) = when (mode) {
        Mode.TERRAIN_LOCKED -> VantageColors.Success to "terrain-locked"
        Mode.TERRAIN_ONLY -> VantageColors.Amber to "terrain-aware"
        Mode.APPROXIMATE -> VantageColors.Amber to "approximate"
        Mode.LOADING -> VantageColors.Glacier to "building terrain"
        Mode.NO_DATA -> VantageColors.Danger to "no pack here"
        Mode.ERROR -> VantageColors.Danger to "error"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3091015))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = VantageColors.Ink,
            fontSize = 9.sp,
        )
        if (alignment == AlignState.LOCKED) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(1.dp, 10.dp).background(Color(0x33FFFFFF)))
            Spacer(Modifier.width(8.dp))
            Text(
                "SKYLINE",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Success,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
fun AlignmentChip(alignment: AlignState, accuracy: Int, modifier: Modifier = Modifier) {
    val (color, text) = when (alignment) {
        AlignState.LOCKED -> VantageColors.Success to "skyline lock"
        AlignState.ALIGNING -> VantageColors.Amber to "aligning"
        AlignState.MANUAL -> VantageColors.Muted to "drag to align"
        AlignState.COMPASS -> VantageColors.Glacier to if (accuracy >= 3) "compass" else "compass ±"
        AlignState.UNAVAILABLE -> VantageColors.Dim to "drag to align"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x80091015))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconGlyph(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 9.sp)
    }
}

@Composable
private fun IconGlyph(color: Color) {
    Box(Modifier.size(4.dp).clip(CircleShape).background(color))
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xCC0B1319))
            .border(1.dp, Color(0x14FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) VantageColors.Ink else VantageColors.Dim,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
fun KindDot(kind: PlaceKind, size: Int = 6, hidden: Boolean = false) {
    val color = kindColor(kind)
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (hidden) color.copy(alpha = 0.45f) else color),
    )
}

fun kindColor(kind: PlaceKind): Color = when (kind) {
    PlaceKind.PEAK -> VantageColors.Peak
    PlaceKind.CITY, PlaceKind.TOWN -> VantageColors.Amber
    PlaceKind.VILLAGE, PlaceKind.HAMLET -> VantageColors.AmberSoft
    PlaceKind.WATER -> VantageColors.Water
    PlaceKind.LANDMARK -> VantageColors.Landmark
}

@Composable
fun MetaLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = VantageColors.Dim,
        fontSize = 9.sp,
    )
}

@Composable
fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        MetaLabel(label)
        Spacer(Modifier.size(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = VantageColors.Ink)
    }
}

@Composable
fun HorizontalRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x14FFFFFF)),
    )
}

val CaptureGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Capture",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, 3f)
            lineTo(7.17f, 5f)
            lineTo(4f, 5f)
            curveTo(2.9f, 5f, 2f, 5.9f, 2f, 7f)
            verticalLineTo(19f)
            curveTo(2f, 20.1f, 2.9f, 21f, 4f, 21f)
            horizontalLineTo(20f)
            curveTo(21.1f, 21f, 22f, 20.1f, 22f, 19f)
            verticalLineTo(7f)
            curveTo(22f, 5.9f, 21.1f, 5f, 20f, 5f)
            horizontalLineTo(16.83f)
            lineTo(15f, 3f)
            horizontalLineTo(9f)
            close()
        }
        path(fill = SolidColor(Color(0xFF05090D))) {
            moveTo(12f, 17f)
            curveTo(9.79f, 17f, 8f, 15.21f, 8f, 13f)
            curveTo(8f, 10.79f, 9.79f, 9f, 12f, 9f)
            curveTo(14.21f, 9f, 16f, 10.79f, 16f, 13f)
            curveTo(16f, 15.21f, 14.21f, 17f, 12f, 17f)
            close()
        }
    }.build()
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1_000_000 -> "${bytes / 1000} KB"
    else -> String.format(java.util.Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
}

fun relativeSize(bytes: Long): String = formatBytes(bytes)
