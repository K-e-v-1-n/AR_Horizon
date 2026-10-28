package app.vantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.DownloadState
import app.vantage.RemotePack
import app.vantage.UiState
import app.vantage.data.InstalledPack
import app.vantage.engine.EngineState
import app.vantage.geo.Geo
import app.vantage.ui.theme.VantageColors
import java.util.Locale

@Composable
fun PackScreen(
    ui: UiState,
    engine: EngineState,
    onBack: () -> Unit,
    onSelectPack: (InstalledPack) -> Unit,
    onDeletePack: (InstalledPack) -> Unit,
    onEyeHeight: (Float) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onReset: () -> Unit,
    onLoadCatalogue: () -> Unit,
    onDownload: (RemotePack) -> Unit,
    onDownloadUrl: (String) -> Unit,
    onImportPack: () -> Unit,
    onServerUrl: (String) -> Unit,
    onAttribution: () -> Unit,
) {
    var eye by remember(ui.eyeHeight) { mutableFloatStateOf(ui.eyeHeight) }
    var server by remember(ui.serverUrl) { mutableStateOf(ui.serverUrl) }
    var directUrl by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(VantageColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = VantageColors.Ink)
            }
            Column(Modifier.weight(1f)) {
                MetaLabel("OFFLINE TERRAIN")
                Text("Terrain packs", style = MaterialTheme.typography.headlineMedium,
                    color = VantageColors.Ink)
            }
            Text(
                "${ui.packs.size} installed",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim, fontSize = 9.sp,
            )
        }

        SectionHeading("INSTALLED")
        if (ui.packs.isEmpty()) {
            Text(
                "No packs. The bundled pack is being prepared.",
                color = VantageColors.Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        for (pack in ui.packs) {
            PackCard(
                pack = pack,
                active = engine.pack?.id == pack.manifest.id,
                onUse = { onSelectPack(pack) },
                onDelete = { onDeletePack(pack) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionHeading("VIEW")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Eye height", style = MaterialTheme.typography.titleMedium,
                        color = VantageColors.Ink)
                    Text(
                        "Above the ground. Raise it on a cable car, tower or balcony.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VantageColors.Muted, fontSize = 12.sp,
                    )
                }
                Text(
                    String.format(Locale.US, "%.1f m", eye),
                    style = MaterialTheme.typography.titleMedium,
                    color = VantageColors.Amber,
                )
            }
            Slider(
                value = eye,
                onValueChange = { eye = it },
                onValueChangeFinished = { onEyeHeight(eye) },
                valueRange = 0.5f..40f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = VantageColors.Amber,
                    activeTrackColor = VantageColors.Amber,
                    inactiveTrackColor = Color(0x22FFFFFF),
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hidden labels", style = MaterialTheme.typography.titleMedium,
                        color = VantageColors.Ink)
                    Text("Show places the ridge blocks, dimmed on the skyline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VantageColors.Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = ui.showHidden,
                    onCheckedChange = onShowHidden,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VantageColors.Background,
                        checkedTrackColor = VantageColors.Amber,
                        uncheckedTrackColor = Color(0x22FFFFFF),
                    ),
                )
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onReset) {
                Icon(Icons.Rounded.Refresh, null, tint = VantageColors.Amber,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text("Reset alignment", color = VantageColors.Amber,
                    style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeading("ADD TERRAIN")
        Panel {
            Text(
                "No server is required. The bundled pack works offline, and you can import " +
                    "a .sqlite pack copied to the phone or fetch one from a direct HTTPS link.",
                style = MaterialTheme.typography.bodyMedium,
                color = VantageColors.Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImportPack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VantageColors.Amber,
                        contentColor = Color(0xFF1B1306),
                    ),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import .sqlite", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onLoadCatalogue, enabled = !ui.catalogueLoading) {
                    if (ui.catalogueLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp), strokeWidth = 2.dp,
                            color = VantageColors.Glacier,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, null, tint = VantageColors.Glacier,
                            modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("Catalogue", color = VantageColors.Glacier,
                        style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = directUrl,
                onValueChange = { directUrl = it },
                label = { Text("Direct pack URL (optional)") },
                placeholder = { Text("https://…/alps.pack.sqlite") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { onDownloadUrl(directUrl) }) {
                Text("Download pack file", color = VantageColors.Glacier,
                    style = MaterialTheme.typography.labelLarge)
            }

            val dl = ui.download
            if (dl is DownloadState.Downloading) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (dl.total > 0) {
                        "${dl.name} · ${(dl.fraction * 100).toInt()}%"
                    } else {
                        "${dl.name} · ${formatBytes(dl.received)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VantageColors.Muted, fontSize = 9.sp,
                )
                Spacer(Modifier.height(5.dp))
                if (dl.total > 0) {
                    LinearProgressIndicator(
                        progress = { dl.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = VantageColors.Amber,
                        trackColor = Color(0x14FFFFFF),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = VantageColors.Amber,
                        trackColor = Color(0x14FFFFFF),
                    )
                }
            } else if (dl is DownloadState.Failed) {
                Spacer(Modifier.height(6.dp))
                Text(dl.message, style = MaterialTheme.typography.bodyMedium,
                    color = VantageColors.Danger, fontSize = 12.sp)
            }

            if (ui.catalogue.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                for (remote in ui.catalogue) {
                    RemotePackRow(remote, ui, onDownload)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Catalogue server (optional)") },
                placeholder = { Text("http://10.0.2.2:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onServerUrl(server) }) {
                Text("Save catalogue URL", color = VantageColors.Glacier,
                    style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeading("DATA")
        Panel {
            Text(
                "30 m surface model. Copernicus GLO-30 reads canopy and settlements as bulk, " +
                    "so a forest is a shape and a single tree is not. Labels that clear the " +
                    "horizon are computed against the same DEM you see.",
                style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
                fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAttribution) {
                Text("Sources and licences", color = VantageColors.Glacier,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = VantageColors.Dim,
        fontSize = 9.sp,
    )
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Color(0xFF0B1217), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PackCard(
    pack: InstalledPack,
    active: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = pack.manifest
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .background(if (active) Color(0xFF111C20) else Color(0xFF0B1217),
                RoundedCornerShape(20.dp))
            .border(1.dp, if (active) VantageColors.Amber.copy(alpha = 0.35f)
            else Color(0x10FFFFFF), RoundedCornerShape(20.dp))
            .clickable(onClick = onUse)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleLarge,
                        color = VantageColors.Ink)
                    if (pack.bundled) {
                        Spacer(Modifier.width(8.dp))
                        Badge("BUNDLED")
                    }
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Badge("ACTIVE", accent = VantageColors.Amber)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${m.demSource} · ${m.radiusKm.toInt()} km radius · " +
                        "${String.format(Locale.US, "%.1f", m.placeCount / 1000.0)}k places",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VantageColors.Muted, fontSize = 12.sp,
                )
            }
            if (!pack.bundled) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, "Delete pack", tint = VantageColors.Dim,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaLabel("QUALITY")
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { m.qualityScore.toFloat() },
                modifier = Modifier.height(3.dp).weight(1f),
                color = VantageColors.Success,
                trackColor = Color(0x14FFFFFF),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${(m.qualityScore * 100).toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Muted, fontSize = 9.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row {
            MetaText("SIZE", formatBytes(pack.sizeBytes))
            Spacer(Modifier.width(20.dp))
            MetaText("DEM", "GLO-30")
            Spacer(Modifier.width(20.dp))
            MetaText("VER", m.version)
        }
        if (m.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(m.detail, style = MaterialTheme.typography.bodyMedium,
                color = VantageColors.Dim, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Badge(text: String, accent: Color = VantageColors.Glacier) {
    Text(
        text,
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = accent, fontSize = 8.sp,
    )
}

@Composable
private fun MetaText(label: String, value: String) {
    Column {
        MetaLabel(label)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = VantageColors.Muted,
            fontSize = 10.sp, letterSpacing = 0.4.sp)
    }
}

@Composable
private fun RemotePackRow(remote: RemotePack, ui: UiState, onDownload: (RemotePack) -> Unit) {
    val download = ui.download
    val installed = ui.packs.firstOrNull { it.manifest.id == remote.id }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFF101920), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(remote.name, style = MaterialTheme.typography.titleMedium,
                color = VantageColors.Ink, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "${remote.radiusKm.toInt()} km · ${formatBytes(remote.sizeBytes)} · " +
                    "quality ${(remote.quality * 100).toInt()}",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim, fontSize = 9.sp,
            )
            if (download is DownloadState.Downloading && download.id == remote.id) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = VantageColors.Amber,
                    trackColor = Color(0x14FFFFFF),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            installed != null -> Icon(Icons.Rounded.CheckCircle, "Installed",
                tint = VantageColors.Success, modifier = Modifier.size(20.dp))
            download is DownloadState.Downloading && download.id == remote.id -> Icon(
                Icons.Rounded.Refresh, "Refresh", tint = VantageColors.Amber,
                modifier = Modifier.size(20.dp))
            else -> IconButton(onClick = { onDownload(remote) }) {
                Icon(Icons.Rounded.Refresh, "Download", tint = VantageColors.Glacier,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}
