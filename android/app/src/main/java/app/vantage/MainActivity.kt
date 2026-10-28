package app.vantage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import app.vantage.ui.VantageApp
import app.vantage.ui.theme.VantageTheme
import app.vantage.util.PhotoExporter
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: VantageViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val camera = result[Manifest.permission.CAMERA]
            ?: has(Manifest.permission.CAMERA)
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: has(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION]
            ?: has(Manifest.permission.ACCESS_COARSE_LOCATION)
        vm.setPermissions(camera, fine || coarse)
        if (!camera) vm.message("Camera off — compass panorama is still usable")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        refreshAr()
        setContent {
            VantageTheme {
                VantageApp(
                    vm = vm,
                    onRequestPermissions = ::requestPermissions,
                    onCapture = {
                        PhotoExporter.capture(this) { ok -> vm.photoSaved(ok) }
                    },
                )
            }
        }
        vm.setPermissions(
            has(Manifest.permission.CAMERA),
            has(Manifest.permission.ACCESS_FINE_LOCATION) ||
                has(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        lifecycleScope.launch {
            delay(600)
            if (!has(Manifest.permission.CAMERA) ||
                !has(Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                requestPermissions()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.setPermissions(
            has(Manifest.permission.CAMERA),
            has(Manifest.permission.ACCESS_FINE_LOCATION) ||
                has(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        vm.onResume()
        refreshAr()
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }

    private fun requestPermissions() {
        permissions.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshAr() {
        val availability = runCatching {
            ArCoreApk.getInstance().checkAvailability(this)
        }.getOrDefault(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE)
        val supported = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
        vm.setArAvailability(supported)
        if (availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        ) {
            runCatching { ArCoreApk.getInstance().requestInstall(this, true) }
        }
    }
}
