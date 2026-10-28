package app.vantage.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.ui.theme.VantageColors

@Composable
fun AttributionScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(VantageColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 36.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = VantageColors.Ink)
            }
            Column {
                MetaLabel("HONEST BY DEFAULT")
                Text("Sources and licences", style = MaterialTheme.typography.headlineMedium,
                    color = VantageColors.Ink)
            }
        }
        Spacer(Modifier.height(12.dp))

        Block(
            "TERRAIN",
            "Copernicus GLO-30 — a 30 m digital surface model (DSM), including canopy and " +
                "settlements as raised surface. Ocean and some country tiles are withheld from " +
                "the public release and fall back to coarser data.",
            "Copernicus DEM © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH " +
                "2014–2018, provided under COPERNICUS by the EU and ESA. All rights reserved.",
            "https://www.copernicus.eu/en/access-data/copernicus-data-space-ecosystem",
        )
        Block(
            "PLACES",
            "Peaks, settlements, lakes and landmarks come from OpenStreetMap. The derived " +
                "places database in each pack is offered under ODbL 1.0, as the licence requires.",
            "© OpenStreetMap contributors. Data available under the Open Database Licence.",
            "https://www.openstreetmap.org/copyright",
        )
        Block(
            "CITIES",
            "The bundled worldwide index of settlements uses the GeoNames cities15000 set, " +
                "so the app can name a distant city before any terrain pack is downloaded.",
            "GeoNames — CC BY 4.0.",
            "https://www.geonames.org/",
        )
        Block(
            "LOOK-UPS",
            "Wikipedia and Wikidata are never bundled. The info card links out, so long text " +
                "stays under CC BY-SA on the web and can go stale without an app update.",
            "Wikipedia / Wikidata — CC BY-SA 4.0.",
            "https://www.wikidata.org/",
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "WHAT THE MODEL CANNOT SEE",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = VantageColors.Amber,
            fontSize = 9.sp,
        )
        Text(
            "One 30 m cell is bigger than a house. A village behind dense forest may still " +
                "read as visible, and the building next to you is not occluded. AR_Horizon says " +
                "\"30 m surface model\" wherever that matters, and marks features the terrain " +
                "blocks as hidden rather than pretending they are in view.",
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
            fontSize = 13.sp, lineHeight = 20.sp,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "PRIVACY",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = VantageColors.Amber,
            fontSize = 9.sp,
        )
        Text(
            "Camera frames are used on the device to fit the horizon and are never uploaded. " +
                "Location stays on the device. There is no account, no analytics and no ad SDK. " +
                "The only network calls are the pack catalogue and downloads you ask for, plus " +
                "links you open yourself.",
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
            fontSize = 13.sp, lineHeight = 20.sp,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "SOFTWARE",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = VantageColors.Amber,
            fontSize = 9.sp,
        )
        Text(
            "AR_Horizon core: C++17, portable, no platform dependencies. " +
                "ARCore (Google), Jetpack Compose (Apache 2.0), Aircompressor (Apache 2.0), " +
                "zstd (BSD). The worldwide index format is documented in the repository.",
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
            fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}

@Composable
private fun Block(title: String, body: String, licence: String, url: String) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = VantageColors.Amber,
            fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
            fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(licence, style = MaterialTheme.typography.bodyMedium,
            color = VantageColors.Dim, fontSize = 11.sp, lineHeight = 16.sp)
        TextButton(
            onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text("Read the licence", color = VantageColors.Glacier,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Share, null, tint = VantageColors.Glacier,
                modifier = Modifier.width(13.dp))
        }
    }
}
