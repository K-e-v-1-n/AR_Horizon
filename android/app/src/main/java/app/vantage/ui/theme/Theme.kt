package app.vantage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VantageColors {
    val Background = Color(0xFF05090D)
    val Surface = Color(0xFF0B1217)
    val SurfaceRaised = Color(0xFF111A20)
    val Ink = Color(0xFFEDF3F7)
    val Muted = Color(0xFF93A6B2)
    val Dim = Color(0xFF5F717C)
    val Amber = Color(0xFFF3B85C)
    val AmberSoft = Color(0xFFE7B478)
    val Glacier = Color(0xFF7EC6E7)
    val Peak = Color(0xFFFFFFFF)
    val Water = Color(0xFF7EC6E7)
    val Landmark = Color(0xFFB7C4CC)
    val TerrainNear = Color(0xFF1D2A23)
    val TerrainFar = Color(0xFF5B6D72)
    val Success = Color(0xFF6FC59B)
    val Danger = Color(0xFFE07862)
    val SkyTop = Color(0xFF07131F)
    val SkyHorizon = Color(0xFFB9CBD5)
}

private val scheme = darkColorScheme(
    primary = VantageColors.Amber,
    onPrimary = Color(0xFF1C1406),
    primaryContainer = Color(0xFF3B2B13),
    onPrimaryContainer = VantageColors.Amber,
    secondary = VantageColors.Glacier,
    onSecondary = Color(0xFF06141A),
    background = VantageColors.Background,
    onBackground = VantageColors.Ink,
    surface = VantageColors.Surface,
    onSurface = VantageColors.Ink,
    surfaceVariant = VantageColors.SurfaceRaised,
    onSurfaceVariant = VantageColors.Muted,
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
    error = VantageColors.Danger,
    onError = Color(0xFF1D0805),
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun VantageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}
