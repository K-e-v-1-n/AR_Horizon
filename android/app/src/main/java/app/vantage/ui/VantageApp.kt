package app.vantage.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vantage.UiState
import app.vantage.VantageViewModel
import app.vantage.ar.ArController
import app.vantage.ui.theme.VantageColors

private enum class Screen { AR, LIST, PACKS, LICENCES }

@Composable
fun VantageApp(
    vm: VantageViewModel,
    onRequestPermissions: () -> Unit,
    onCapture: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.AR) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val arController = remember { ArController(context, vm.engine) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importPack(uri)
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    DisposableEffect(Unit) {
        onDispose { arController.onDestroy() }
    }

    BackHandler(enabled = screen != Screen.AR || state.selected != null || ui.onboarding) {
        when {
            ui.onboarding -> vm.dismissOnboarding()
            state.selected != null -> vm.clearSelection()
            screen != Screen.AR -> screen = Screen.AR
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VantageColors.Background),
    ) {
        when (screen) {
            Screen.AR -> ArScreen(
                state = state,
                ui = ui,
                arController = arController,
                profileSnapshot = vm::profileSnapshot,
                onNudge = vm::nudgeYaw,
                onSelect = vm::selectLabel,
                onList = { screen = Screen.LIST },
                onPacks = { screen = Screen.PACKS },
                onReset = vm::resetAlignment,
                onCapture = onCapture,
                onRequestPermissions = onRequestPermissions,
                onViewport = vm::setViewport,
            )

            Screen.LIST -> ListScreen(
                state = state,
                onBack = { screen = Screen.AR },
                onSelect = vm::selectLabel,
                onReset = vm::resetAlignment,
            )

            Screen.PACKS -> PackScreen(
                ui = ui,
                engine = state,
                onBack = { screen = Screen.AR },
                onSelectPack = vm::selectPack,
                onDeletePack = vm::deletePack,
                onEyeHeight = vm::setEyeHeight,
                onShowHidden = vm::setShowHidden,
                onReset = vm::resetAlignment,
                onLoadCatalogue = vm::loadCatalogue,
                onDownload = vm::downloadPack,
                onDownloadUrl = vm::downloadPackFile,
                onImportPack = { importLauncher.launch(arrayOf("*/*")) },
                onServerUrl = vm::setServerUrl,
                onAttribution = { screen = Screen.LICENCES },
            )

            Screen.LICENCES -> AttributionScreen(onBack = { screen = Screen.PACKS })
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 86.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = VantageColors.SurfaceRaised,
                contentColor = VantageColors.Ink,
            )
        }

        state.selected?.let { selected ->
            PlaceSheet(label = selected, onDismiss = vm::clearSelection)
        }

        if (ui.onboarding) {
            OnboardingOverlay(
                cameraGranted = ui.cameraGranted,
                locationGranted = ui.locationGranted,
                onStart = vm::dismissOnboarding,
                onRequestPermissions = onRequestPermissions,
            )
        }
    }
}
