package app.vantage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.geo.Geo
import app.vantage.model.LabelItem
import app.vantage.model.PlaceKind
import app.vantage.ui.theme.VantageColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSheet(
    label: LabelItem,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val place = label.place
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VantageColors.Surface,
        contentColor = VantageColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Spacer(
                Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(3.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(3.dp)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindDot(place.kind, size = 7, hidden = !label.visible)
                Spacer(Modifier.width(9.dp))
                MetaLabel(
                    if (label.visible) place.kind.label else "${place.kind.label} · hidden",
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when (place.kind) {
                        PlaceKind.PEAK -> "SUMMIT"
                        PlaceKind.WATER -> "WATER"
                        PlaceKind.LANDMARK -> "LANDMARK"
                        PlaceKind.CITY, PlaceKind.TOWN, PlaceKind.VILLAGE,
                        PlaceKind.HAMLET -> "SETTLEMENT"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Dim,
                    fontSize = 9.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                place.name,
                style = MaterialTheme.typography.displaySmall,
                color = if (label.visible) VantageColors.Ink else VantageColors.Muted,
            )
            if (place.localName.isNotBlank() && place.localName != place.name) {
                Text(place.localName, style = MaterialTheme.typography.bodyMedium,
                    color = VantageColors.Muted)
            }
            if (!place.description.isNullOrBlank() &&
                !place.description.equals(place.kind.label, ignoreCase = true)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    place.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VantageColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                StatColumn("DISTANCE", Geo.formatDistance(label.distM))
                StatColumn("BEARING", "${Math.round(label.azDeg)}° ${Geo.compassLabel(label.azDeg)}")
                StatColumn("ELEVATION", Geo.formatElevation(label.targetEle))
            }
            Spacer(Modifier.height(18.dp))
            if (!label.visible) {
                Banner(
                    title = "Hidden behind the ridge",
                    body = "The terrain between here and ${place.name} rises above it. " +
                        "You are seeing its direction, not its face.",
                    accent = VantageColors.Amber,
                )
            } else if (!label.hasTerrain) {
                Banner(
                    title = if (place.source == "index") "Index only" else "Beyond the pack",
                    body = if (place.source == "index") {
                        "No terrain pack covers this view. Bearing and distance are exact; " +
                            "visibility is not checked against the horizon."
                    } else {
                        "This target is outside the terrain pack's range. Bearing and distance " +
                            "are exact; visibility is not checked."
                    },
                    accent = VantageColors.Glacier,
                )
            }
            if (label.hasTerrain) {
                Banner(
                    title = "30 m surface model",
                    body = "Copernicus GLO-30 sees canopy and buildings as bulk. " +
                        "A single tree or house is below its resolution.",
                    accent = VantageColors.Dim,
                )
            }
            Spacer(Modifier.height(14.dp))
            HorizontalRule()
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MetaLabel("POSITION")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format(java.util.Locale.US, "%.4f°, %.4f°", place.lat, place.lon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VantageColors.Muted,
                    )
                    if (place.population > 0) {
                        Spacer(Modifier.height(8.dp))
                        MetaLabel("POPULATION")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            String.format(java.util.Locale.getDefault(), "%,d", place.population),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VantageColors.Muted,
                        )
                    }
                }
                TypeGlyph(place.kind)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val url = if (!place.wikidata.isNullOrBlank()) {
                            "https://www.wikidata.org/wiki/${place.wikidata}"
                        } else {
                            "https://en.wikipedia.org/wiki/Special:Search?search=" +
                                Uri.encode(place.name)
                        }
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                ) {
                    Text("Look it up", color = VantageColors.Amber,
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(5.dp))
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Share, null,
                        tint = VantageColors.Amber, modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (place.source == "index") "GeoNames · CC-BY" else "OSM · ODbL",
                    style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Dim,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun Banner(title: String, body: String, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Color(0xFF101920), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .padding(top = 3.dp)
                .size(5.dp)
                .background(accent, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = VantageColors.Ink,
                fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
                fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun TypeGlyph(kind: PlaceKind) {
    val letter = when (kind) {
        PlaceKind.PEAK -> "△"
        PlaceKind.CITY, PlaceKind.TOWN, PlaceKind.VILLAGE, PlaceKind.HAMLET -> "⌂"
        PlaceKind.WATER -> "≈"
        PlaceKind.LANDMARK -> "◈"
    }
    Text(letter, fontSize = 34.sp, color = kindColor(kind).copy(alpha = 0.35f),
        fontWeight = FontWeight.Light)
}
