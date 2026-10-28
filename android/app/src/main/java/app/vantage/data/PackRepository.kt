package app.vantage.data

import android.content.Context
import app.vantage.BuildConfig
import app.vantage.model.Place
import app.vantage.model.PlaceKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class IndexEntry(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val pop: Int,
    val kind: Int,
    val name: String,
)

class WorldIndex(private val entries: List<IndexEntry>) {
    val size: Int get() = entries.size

    fun nearby(lat: Double, lon: Double, maxKm: Double = 160.0, limit: Int = 350): List<Place> {
        val maxM = maxKm * 1000.0
        val out = ArrayList<Place>(limit)
        for ((i, e) in entries.withIndex()) {
            val d = app.vantage.geo.Geo.distanceM(lat, lon, e.lat, e.lon)
            if (d > maxM) continue
            val kind = when (e.kind) {
                0 -> PlaceKind.PEAK
                1 -> PlaceKind.CITY
                else -> PlaceKind.TOWN
            }
            out += Place(
                id = -1 - i,
                kind = kind,
                name = e.name,
                localName = e.name,
                lat = e.lat,
                lon = e.lon,
                ele = e.ele,
                population = e.pop,
                rank = if (kind == PlaceKind.PEAK) 0.5f else 0.2f,
                wikidata = null,
                description = null,
                source = "index",
            )
        }
        return out.sortedBy { app.vantage.geo.Geo.distanceM(lat, lon, it.lat, it.lon) }.take(limit)
    }

    companion object {
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte(),
            'D'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte(), 0, 0)

        fun parse(bytes: ByteArray): WorldIndex {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) error("not an AR_Horizon world index")
            val version = buf.int
            require(version == 1) { "unsupported index version $version" }
            val count = buf.int
            val out = ArrayList<IndexEntry>(max(0, count))
            repeat(count) {
                val lat = buf.float.toDouble()
                val lon = buf.float.toDouble()
                val ele = buf.float.toDouble()
                val pop = buf.int
                val kind = buf.get().toInt() and 0xff
                val n = buf.get().toInt() and 0xff
                val nameBytes = ByteArray(n)
                buf.get(nameBytes)
                out += IndexEntry(lat, lon, ele, pop, kind, String(nameBytes, Charsets.UTF_8))
            }
            return WorldIndex(out)
        }
    }
}

class PackRepository(private val context: Context) {
    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("vantage_packs", Context.MODE_PRIVATE)

    @Synchronized
    fun ensureBundled(): File {
        val dest = File(dir, "alps.pack.sqlite")
        val marker = prefs.getInt("bundled_version", -1)
        if (!dest.exists() || dest.length() == 0L || marker != 1) {
            val tmp = File(dir, "alps.pack.sqlite.part")
            context.assets.open("packs/alps.pack.sqlite").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() > 1_000_000) {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    error("could not install the bundled pack")
                }
                prefs.edit().putInt("bundled_version", 1).apply()
            } else {
                tmp.delete()
                error("bundled pack is missing")
            }
        }
        return dest
    }

    fun installed(): List<InstalledPack> {
        val bundled = runCatching { ensureBundled() }.getOrNull()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }
            ?.mapNotNull { file ->
                runCatching {
                    PackDatabase(file).use { db ->
                        InstalledPack(
                            manifest = db.manifest,
                            path = file.absolutePath,
                            sizeBytes = file.length(),
                            bundled = bundled != null && file.absolutePath == bundled.absolutePath,
                        )
                    }
                }.getOrNull()
            }
            ?.sortedByDescending { it.bundled }
            ?: emptyList()
    }

    fun open(path: String): PackDatabase = PackDatabase(File(path))

    fun loadWorldIndex(): WorldIndex = runCatching {
        val bytes = context.assets.open("index/world.idx").use { it.readBytes() }
        WorldIndex.parse(bytes)
    }.getOrElse { WorldIndex(emptyList()) }

    fun addDownloadedPack(tmp: File, id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            .ifBlank { "imported" }
        var dest = File(dir, "$safe.sqlite")
        // Never let an imported manifest id replace the bundled pack.
        if (dest.absolutePath.equals(bundledFile().absolutePath, ignoreCase = true)) {
            dest = File(dir, "$safe-imported.sqlite")
        }
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "could not install pack" }
        return dest
    }

    fun deletePack(path: String): Boolean {
        val f = File(path)
        if (f.absolutePath.equals(bundledFile().absolutePath, ignoreCase = true)) return false
        return f.delete()
    }

    private fun bundledFile() = File(dir, "alps.pack.sqlite")
}

class VantageSettings(context: Context) {
    private val prefs = context.getSharedPreferences("vantage", Context.MODE_PRIVATE)

    var eyeHeight: Float
        get() = prefs.getFloat("eye_height", 1.7f)
        set(value) = prefs.edit().putFloat("eye_height", value.coerceIn(0.5f, 40f)).apply()

    var seenOnboarding: Boolean
        get() = prefs.getBoolean("onboarding", false)
        set(value) = prefs.edit().putBoolean("onboarding", value).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("show_hidden", true)
        set(value) = prefs.edit().putBoolean("show_hidden", value).apply()

    var packPath: String?
        get() = prefs.getString("pack_path", null)
        set(value) = prefs.edit().putString("pack_path", value).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", BuildConfig.PACK_BASE_URL) ?: ""
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()
}
