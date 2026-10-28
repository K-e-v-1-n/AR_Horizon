package app.vantage.data

import org.json.JSONObject

data class PackManifest(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val version: String,
    val demSource: String,
    val demAttribution: String,
    val placesAttribution: String,
    val qualityScore: Double,
    val placeCount: Int,
    val detail: String,
) {
    companion object {
        fun parse(json: String): PackManifest {
            val o = JSONObject(json)
            val c = o.optJSONArray("center")
            val b = o.optJSONArray("bounds")
            val q = o.optJSONObject("quality")
            val counts = o.optJSONObject("counts")
            return PackManifest(
                id = o.optString("id", "unknown"),
                name = o.optString("name", "Terrain pack"),
                centerLat = c?.optDouble(0, 0.0) ?: 0.0,
                centerLon = c?.optDouble(1, 0.0) ?: 0.0,
                radiusKm = o.optDouble("radius_km", 60.0),
                minLat = b?.optDouble(0, -90.0) ?: -90.0,
                minLon = b?.optDouble(1, -180.0) ?: -180.0,
                maxLat = b?.optDouble(2, 90.0) ?: 90.0,
                maxLon = b?.optDouble(3, 180.0) ?: 180.0,
                version = o.optString("version", ""),
                demSource = o.optString("dem_source", "Copernicus GLO-30"),
                demAttribution = o.optString("dem_attribution", ""),
                placesAttribution = o.optString("places_attribution", ""),
                qualityScore = q?.optDouble("score", 0.8) ?: 0.8,
                placeCount = counts?.optInt("places", 0) ?: 0,
                detail = q?.optString("note", "") ?: "",
            )
        }
    }

    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon
}

data class InstalledPack(
    val manifest: PackManifest,
    val path: String,
    val sizeBytes: Long,
    val bundled: Boolean,
)
