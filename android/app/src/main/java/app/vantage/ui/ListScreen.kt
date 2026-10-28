package app.vantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.engine.EngineState
import app.vantage.geo.Geo
import app.vantage.model.LabelItem
import app.vantage.ui.theme.VantageColors

@Composable
fun ListScreen(
    state: EngineState,
    onBack: () -> Unit,
    onSelect: (LabelItem) -> Unit,
    onReset: () -> Unit,
) {
    val labels = state.frame?.labels?.sortedBy { it.azDeg } ?: emptyList()
    Column(
        Modifier
            .fillMaxSize()
            .background(VantageColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = VantageColors.Ink)
            }
            Column(Modifier.weight(1f)) {
                MetaLabel("EVERYTHING IN VIEW")
                Text(
                    if (labels.isEmpty()) "No features" else "${labels.size} features",
                    style = MaterialTheme.typography.headlineMedium,
                    color = VantageColors.Ink,
                )
            }
            IconButton(onClick = onReset) {
                Icon(Icons.Rounded.Refresh, "Reset alignment", tint = VantageColors.Muted)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "${Math.round(state.frame?.headingDeg ?: 0.0)}°",
                style = MaterialTheme.typography.displaySmall,
                color = VantageColors.Amber,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                Geo.compassLabel(state.frame?.headingDeg ?: 0.0),
                style = MaterialTheme.typography.titleLarge,
                color = VantageColors.Muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "sorted by bearing",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalRule(Modifier.padding(horizontal = 24.dp))

        if (labels.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing on this bearing", style = MaterialTheme.typography.titleLarge,
                    color = VantageColors.Muted)
                Spacer(Modifier.height(8.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium,
                    color = VantageColors.Dim)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp,
                ),
            ) {
                items(labels, key = { "${it.place.id}-${it.place.name}" }) { label ->
                    FeatureRow(label, onClick = { onSelect(label) })
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(label: LabelItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color(0xFF0B1217), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindDot(label.place.kind, size = 7, hidden = !label.visible)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (label.visible) VantageColors.Ink else VantageColors.Muted,
                    fontWeight = if (label.place.kind.name == "PEAK") FontWeight.SemiBold
                    else FontWeight.Medium,
                    maxLines = 1,
                )
                if (!label.visible) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Warning, "Hidden by ridge",
                        tint = VantageColors.Dim, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                label.place.kind.label + if (label.place.ele > 0) " · " +
                    Geo.formatElevation(label.place.ele) else "",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Geo.formatDistance(label.distM),
                style = MaterialTheme.typography.titleMedium,
                color = VantageColors.Ink,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${Math.round(label.azDeg)}° ${Geo.compassLabel(label.azDeg)}",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Muted,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(2.dp)
                .height(30.dp)
                .background(kindColor(label.place.kind).copy(alpha = 0.25f),
                    RoundedCornerShape(2.dp)),
        )
    }
}
