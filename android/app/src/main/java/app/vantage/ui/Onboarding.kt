package app.vantage.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vantage.ui.theme.VantageColors

@Composable
fun OnboardingOverlay(
    cameraGranted: Boolean,
    locationGranted: Boolean,
    onStart: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xCC04080B),
                        Color(0xF2070C11),
                        Color(0xFF070C11),
                    ),
                ),
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 28.dp, top = 72.dp, end = 28.dp),
        ) {
            Text(
                "AR_HORIZON",
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Amber,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "The horizon,\nlabelled.",
                style = MaterialTheme.typography.displaySmall,
                color = VantageColors.Ink,
                fontSize = 44.sp,
                lineHeight = 46.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Point the phone at a view. Every peak, town and lake you can actually see " +
                    "gets a name, distance and elevation — pinned to the world, offline.",
                style = MaterialTheme.typography.bodyLarge,
                color = VantageColors.Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            OnboardingRow("▲", "Terrain-aware",
                "A label only appears if its target clears the ridge. Hidden places sit dimmed " +
                    "on the skyline.")
            OnboardingRow("⌁", "Offline by design",
                "A region is 20–60 MB, downloaded once at home. Summit signal optional.")
            OnboardingRow("≈", "30 m, honestly",
                "Canopy and buildings read as bulk. Single trees do not. The app says so.")

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VantageColors.Amber,
                    contentColor = Color(0xFF1B1306),
                ),
            ) {
                Text("Start exploring", style = MaterialTheme.typography.labelLarge,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!cameraGranted || !locationGranted) {
                TextButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (!cameraGranted) "Enable camera and location"
                        else "Enable location for distances",
                        color = VantageColors.Glacier,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "Camera frames stay on the device. No account, no tracking.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = VantageColors.Dim,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun OnboardingRow(glyph: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 7.dp)) {
        Text(
            glyph,
            modifier = Modifier.width(30.dp).padding(top = 1.dp),
            color = VantageColors.Amber,
            fontSize = 17.sp,
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = VantageColors.Ink,
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = VantageColors.Muted,
                fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
