package app.vantage.data

import android.database.sqlite.SQLiteDatabase
import app.vantage.model.Place
import app.vantage.model.PlaceKind
import io.airlift.compress.zstd.ZstdDecompressor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PackDatabase(val file: File) : AutoCloseable {
    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private val zstd = ZstdDecompressor()

    val manifest: PackManifest by lazy {
        db.rawQuery("SELECT json FROM manifest WHERE id='pack'", null).use { c ->
            if (c.moveToFirst()) PackManifest.parse(c.getString(0))
            else error("pack manifest missing")
        }
    }

    fun tileExtent(z: Int): IntArray? {
        db.rawQuery("SELECT MIN(x), MAX(x), MIN(y), MAX(y) FROM dem WHERE z=?", arrayOf(z.toString()))
            .use { c ->
                if (!c.moveToFirst() || c.isNull(0)) return null
                return intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            }
    }

    /** Reads one 600×600 int16 tile, decompressing in place. */
    fun tile(z: Int, x: Int, y: Int, size: Int): ShortArray? {
        db.rawQuery(
            "SELECT w,h,data FROM dem WHERE z=? AND x=? AND y=?",
            arrayOf(z.toString(), x.toString(), y.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val w = c.getInt(0)
            val h = c.getInt(1)
            if (w != size || h != size) return null
            val blob = c.getBlob(2)
            val raw = ByteArray(size * size * 2)
            val n = zstd.decompress(blob, 0, blob.size, raw, 0, raw.size)
            if (n != raw.size) return null
            val shorts = ShortArray(size * size)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }
    }

    fun places(): List<Place> {
        val out = ArrayList<Place>(12_000)
        db.rawQuery(
            "SELECT id,kind,name,name_local,lat,lon,ele,pop,rank,wikidata,desc FROM places",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Place(
                    id = c.getInt(0),
                    kind = PlaceKind.fromPack(c.getString(1)),
                    name = c.getString(2) ?: "",
                    localName = c.getString(3) ?: "",
                    lat = c.getDouble(4),
                    lon = c.getDouble(5),
                    ele = c.getDouble(6),
                    population = c.getInt(7),
                    rank = c.getFloat(8),
                    wikidata = c.getString(9),
                    description = c.getString(10),
                    source = "pack",
                )
            }
        }
        return out
    }

    override fun close() {
        db.close()
    }
}

data class GridBuffer(
    val data: ShortArray,
    val latNorth: Double,
    val lonWest: Double,
    val dlat: Double,
    val dlon: Double,
    val width: Int,
    val height: Int,
)

object TerrainAssembler {
    const val TILE_SIZE = 600

    fun tileOrigin(z: Int): Double = TILE_SIZE / 3600.0 * (4.0.pow(z))

    /**
     * Builds the rectangular grid the native core needs, aligned to pack tiles.
     * The fine grid covers the near field at 1", the coarse grid the rest at 4".
     */
    fun assemble(db: PackDatabase, z: Int, lat: Double, lon: Double, radiusKm: Double): GridBuffer? {
        val extent = db.tileExtent(z) ?: return null
        val step = tileOrigin(z)
        val latR = radiusKm / 111.0
        val lonR = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.15))
        var x0 = Math.floor((lon - lonR + 180.0) / step).toInt()
        var x1 = Math.ceil((lon + lonR + 180.0) / step).toInt()
        var y0 = Math.floor((90.0 - (lat + latR)) / step).toInt()
        var y1 = Math.ceil((90.0 - (lat - latR)) / step).toInt()
        x0 = x0.coerceIn(extent[0], extent[1] + 1)
        x1 = x1.coerceIn(extent[0], extent[1] + 1)
        y0 = y0.coerceIn(extent[2], extent[3] + 1)
        y1 = y1.coerceIn(extent[2], extent[3] + 1)
        if (x1 <= x0 || y1 <= y0) return null

        val width = (x1 - x0) * TILE_SIZE
        val height = (y1 - y0) * TILE_SIZE
        val full = ShortArray(width * height)
        for (gy in y0 until y1) {
            for (gx in x0 until x1) {
                val tile = db.tile(z, gx, gy, TILE_SIZE) ?: continue
                val rowBase = (gy - y0) * TILE_SIZE
                val colBase = (gx - x0) * TILE_SIZE
                for (r in 0 until TILE_SIZE) {
                    System.arraycopy(tile, r * TILE_SIZE, full,
                        (rowBase + r) * width + colBase, TILE_SIZE)
                }
            }
        }
        // Crop to the requested radius: replicated pack-edge tiles must not
        // extend the horizon beyond the advertised coverage.
        val latNorthFull = 90.0 - y0 * step
        val lonWestFull = x0 * step - 180.0
        val d = step / TILE_SIZE
        var r0 = Math.floor((latNorthFull - (lat + latR)) / d).toInt().coerceIn(0, height - 1)
        var r1 = Math.ceil((latNorthFull - (lat - latR)) / d).toInt().coerceIn(r0 + 1, height)
        var c0 = Math.floor((lon - lonR - lonWestFull) / d).toInt().coerceIn(0, width - 1)
        var c1 = Math.ceil((lon + lonR - lonWestFull) / d).toInt().coerceIn(c0 + 1, width)
        val cw = c1 - c0
        val ch = r1 - r0
        val cropped = ShortArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(full, (r0 + row) * width + c0, cropped, row * cw, cw)
        }
        return GridBuffer(
            data = cropped,
            latNorth = latNorthFull - r0 * d,
            lonWest = lonWestFull + c0 * d,
            dlat = d,
            dlon = d,
            width = cw,
            height = ch,
        )
    }
}

private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
