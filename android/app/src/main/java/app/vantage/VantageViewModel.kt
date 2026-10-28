package app.vantage

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vantage.data.InstalledPack
import app.vantage.data.PackDatabase
import app.vantage.data.PackRepository
import app.vantage.data.VantageSettings
import app.vantage.engine.VantageEngine
import app.vantage.model.LabelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class RemotePack(
    val id: String,
    val name: String,
    val radiusKm: Double,
    val sizeBytes: Long,
    val quality: Double,
    val centerLat: Double,
    val centerLon: Double,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val id: String,
        val name: String,
        val fraction: Float,
        val received: Long,
        val total: Long,
    ) : DownloadState

    data class Failed(val message: String) : DownloadState
}

data class UiState(
    val packs: List<InstalledPack> = emptyList(),
    val catalogue: List<RemotePack> = emptyList(),
    val catalogueLoading: Boolean = false,
    val download: DownloadState = DownloadState.Idle,
    val arAvailable: Boolean = false,
    val cameraGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val serverUrl: String = "",
    val eyeHeight: Float = 1.7f,
    val showHidden: Boolean = true,
    val onboarding: Boolean = true,
)

class VantageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PackRepository(app)
    private val settings = VantageSettings(app)
    val engine = VantageEngine(app, repo, settings)
    val state = engine.state

    private val _ui = MutableStateFlow(
        UiState(
            serverUrl = settings.serverUrl,
            eyeHeight = settings.eyeHeight,
            showHidden = settings.showHidden,
            onboarding = !settings.seenOnboarding,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        engine.start()
        viewModelScope.launch(Dispatchers.IO) { refreshPacks() }
    }

    fun setPermissions(camera: Boolean, location: Boolean) {
        _ui.update { it.copy(cameraGranted = camera, locationGranted = location) }
        if (location) engine.onResume()
    }

    fun setArAvailability(available: Boolean) {
        _ui.update { it.copy(arAvailable = available) }
    }

    fun onResume() = engine.onResume()
    fun onPause() = engine.onPause()

    fun refreshPacks() {
        val packs = repo.installed()
        _ui.update { it.copy(packs = packs) }
    }

    fun loadCatalogue() {
        if (_ui.value.catalogueLoading) return
        if (settings.serverUrl.isBlank()) {
            message("No catalogue server set. Import a pack file, or paste a direct pack URL.")
            return
        }
        _ui.update { it.copy(catalogueLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val base = settings.serverUrl.trimEnd('/')
                val body = httpGet("$base/v1/catalogue")
                val arr = JSONObject(body).getJSONArray("packs")
                val out = ArrayList<RemotePack>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = o.optJSONArray("center")
                    out += RemotePack(
                        id = o.getString("id"),
                        name = o.optString("name", o.getString("id")),
                        radiusKm = o.optDouble("radius_km", 80.0),
                        sizeBytes = o.optLong("size_bytes", 0L),
                        quality = o.optDouble("quality", 0.8),
                        centerLat = c?.optDouble(0, 0.0) ?: 0.0,
                        centerLon = c?.optDouble(1, 0.0) ?: 0.0,
                    )
                }
                out
            }.onSuccess { list ->
                _ui.update { it.copy(catalogue = list, catalogueLoading = false) }
                if (list.isEmpty()) message("No packs on the server yet")
            }.onFailure { t ->
                _ui.update { it.copy(catalogueLoading = false) }
                message("Catalogue unavailable: ${t.message ?: "offline"}")
            }
        }
    }

    fun downloadPack(pack: RemotePack) {
        if (_ui.value.download is DownloadState.Downloading) return
        val base = settings.serverUrl.trimEnd('/')
        viewModelScope.launch(Dispatchers.IO) {
            try {
                downloadToTemp(
                    URL("$base/v1/packs/${pack.id}/download"),
                    pack.id,
                    pack.name,
                    pack.sizeBytes,
                )
            } catch (t: Throwable) {
                failDownload(t)
            }
        }
    }

    fun downloadPackFile(rawUrl: String) {
        if (_ui.value.download is DownloadState.Downloading) return
        val clean = rawUrl.trim()
        if (clean.isBlank()) {
            message("Paste a direct URL to a .sqlite pack first")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = "direct-${clean.hashCode()}"
                downloadToTemp(URL(clean), id, "pack file", 0L)
            } catch (t: Throwable) {
                failDownload(t)
            }
        }
    }

    fun importPack(uri: Uri) {
        if (_ui.value.download is DownloadState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(getApplication<Application>().filesDir, "packs").apply { mkdirs() }
            val tmp = File(dir, "import-${System.currentTimeMillis()}.part")
            try {
                _ui.update {
                    it.copy(download = DownloadState.Downloading(
                        "import", "pack file", 0f, 0, 0,
                    ))
                }
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        var received = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            received += n
                            _ui.update {
                                it.copy(download = DownloadState.Downloading(
                                    "import", "pack file", 0f, received, 0,
                                ))
                            }
                        }
                    }
                } ?: error("could not open the selected file")
                installPackFile(tmp, "Imported")
            } catch (t: Throwable) {
                tmp.delete()
                _ui.update { it.copy(download = DownloadState.Failed(t.message ?: "import failed")) }
                message("Import failed: ${t.message ?: "unreadable file"}")
            }
        }
    }

    private fun downloadToTemp(url: URL, id: String, label: String, sizeHint: Long) {
        val dir = File(getApplication<Application>().filesDir, "packs").apply { mkdirs() }
        val tmp = File(dir, "$id.part")
        _ui.update {
            it.copy(download = DownloadState.Downloading(id, label, 0f, 0, sizeHint))
        }
        try {
            var existing = if (tmp.exists()) tmp.length() else 0L
            var conn = openConnection(url, existing)
            conn.connect()
            if (conn.responseCode == 416 && existing > 0) {
                conn.disconnect()
                tmp.delete()
                existing = 0L
                conn = openConnection(url, 0L)
                conn.connect()
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val append = conn.responseCode == 206 && existing > 0
            val total = if (conn.contentLengthLong > 0) {
                conn.contentLengthLong + if (append) existing else 0L
            } else {
                sizeHint
            }
            try {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, append).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var received = if (append) existing else 0L
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            received += n
                            val frac = if (total > 0) received.toFloat() / total else 0f
                            _ui.update {
                                it.copy(download = DownloadState.Downloading(
                                    id, label, frac.coerceIn(0f, 1f), received, total,
                                ))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            installPackFile(tmp, label)
        } catch (t: Throwable) {
            failDownload(t)
        }
    }

    private fun openConnection(url: URL, existing: Long): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "AR_Horizon/0.1")
            setRequestProperty("Accept", "application/octet-stream")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

    private fun failDownload(t: Throwable) {
        _ui.update { it.copy(download = DownloadState.Failed(t.message ?: "download failed")) }
        message("Download failed: ${t.message ?: "network"}")
    }

    private fun installPackFile(tmp: File, label: String) {
        if (tmp.length() < 1_000_000) {
            tmp.delete()
            error("not an AR_Horizon pack")
        }
        val manifest = try {
            PackDatabase(tmp).use { db ->
                val m = db.manifest
                val extent = db.tileExtent(0)
                require(extent != null) { "pack has no terrain tiles" }
                require(db.tile(0, extent[0], extent[2], 600) != null) {
                    "pack has an unreadable terrain tile"
                }
                m
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw IllegalArgumentException("not a readable AR_Horizon pack", t)
        }
        val dest = repo.addDownloadedPack(tmp, manifest.id)
        settings.packPath = dest.absolutePath
        engine.switchPack(dest.absolutePath)
        refreshPacks()
        _ui.update { it.copy(download = DownloadState.Idle) }
        message(if (label == "Imported") "${manifest.name} imported" else "${manifest.name} installed")
    }

    fun selectPack(pack: InstalledPack) {
        settings.packPath = pack.path
        engine.switchPack(pack.path)
        message("${pack.manifest.name} active")
    }

    fun deletePack(pack: InstalledPack) {
        if (pack.bundled) {
            message("The bundled pack stays installed")
            return
        }
        if (repo.deletePack(pack.path)) {
            val active = settings.packPath
            if (active == pack.path) {
                val bundled = _ui.value.packs.firstOrNull { it.bundled }
                if (bundled != null) selectPack(bundled)
            }
            viewModelScope.launch(Dispatchers.IO) { refreshPacks() }
            message("Pack deleted")
        } else {
            message("Could not delete pack")
        }
    }

    fun setServerUrl(value: String) {
        settings.serverUrl = value
        _ui.update { it.copy(serverUrl = settings.serverUrl) }
    }

    fun setEyeHeight(metres: Float) {
        settings.eyeHeight = metres
        _ui.update { it.copy(eyeHeight = settings.eyeHeight) }
        if (state.value.pack != null) {
            settings.packPath?.let { engine.switchPack(it) }
        }
    }

    fun setShowHidden(show: Boolean) {
        settings.showHidden = show
        _ui.update { it.copy(showHidden = show) }
        engine.setShowHidden(show)
    }

    fun resetAlignment() = engine.resetAlignment()

    fun dismissOnboarding() {
        settings.seenOnboarding = true
        _ui.update { it.copy(onboarding = false) }
    }

    fun nudgeYaw(deltaDeg: Double) = engine.nudgeYaw(deltaDeg)

    fun selectLabel(label: LabelItem?) = engine.selectLabel(label)
    fun clearSelection() = engine.clearSelection()

    fun profileSnapshot(): FloatArray? = engine.syntheticProfile()

    fun setViewport(width: Int, height: Int) = engine.setViewport(width, height)

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun photoSaved(ok: Boolean) {
        message(if (ok) "Saved to Pictures/AR_Horizon" else "Could not save the photo")
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
