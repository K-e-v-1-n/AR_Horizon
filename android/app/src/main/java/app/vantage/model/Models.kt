package app.vantage.model

import app.vantage.core.NativeCore

enum class PlaceKind(val id: Int, val label: String, val plural: String) {
    PEAK(0, "Peak", "Peaks"),
    CITY(1, "City", "Cities"),
    TOWN(2, "Town", "Towns"),
    VILLAGE(3, "Village", "Villages"),
    HAMLET(4, "Hamlet", "Hamlets"),
    WATER(5, "Water", "Lakes"),
    LANDMARK(6, "Landmark", "Landmarks");

    companion object {
        fun fromId(id: Int): PlaceKind = entries.firstOrNull { it.id == id } ?: LANDMARK
        fun fromPack(kind: String): PlaceKind = when (kind) {
            "peak" -> PEAK
            "city" -> CITY
            "town" -> TOWN
            "village" -> VILLAGE
            "hamlet" -> HAMLET
            "water" -> WATER
            else -> LANDMARK
        }
    }
}

data class Place(
    val id: Int,
    val kind: PlaceKind,
    val name: String,
    val localName: String,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val population: Int,
    val rank: Float,
    val wikidata: String?,
    val description: String?,
    val source: String = "pack",
) {
    val nativeKind: Int get() = kind.id
}

data class LatLon(val lat: Double, val lon: Double)

data class LabelItem(
    val place: Place,
    val x: Float,
    val y: Float,
    val azDeg: Double,
    val elevDeg: Double,
    val distM: Double,
    val targetEle: Double,
    val visible: Boolean,
    val hasTerrain: Boolean,
    val score: Float,
    val boxW: Float,
    val boxH: Float,
)

data class LabelFrame(
    val labels: List<LabelItem>,
    val headingDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val hfovDeg: Double,
    val width: Int,
    val height: Int,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class Mode(val label: String) {
    LOADING("building terrain"),
    TERRAIN_LOCKED("terrain-locked"),
    TERRAIN_ONLY("terrain-aware"),
    APPROXIMATE("approximate"),
    NO_DATA("no terrain here"),
    ERROR("error"),
}

enum class Alignment(val label: String) {
    LOCKED("skyline lock"),
    ALIGNING("aligning"),
    MANUAL("drag to align"),
    COMPASS("compass"),
    UNAVAILABLE("no compass"),
}

data class CameraPose(
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val hfovDeg: Double,
    val width: Int,
    val height: Int,
)

data class SilhouetteProfile(
    val elevRad: FloatArray,
    val originLat: Double,
    val originLon: Double,
)

fun labelsFromNative(
    packed: FloatArray,
    places: List<Place>,
): List<LabelItem> {
    if (packed.size < NativeCore.STRIDE) return emptyList()
    val n = packed[0].toInt().coerceAtLeast(0)
    val out = ArrayList<LabelItem>(n)
    for (i in 0 until n) {
        val o = (i + 1) * NativeCore.STRIDE
        if (o + NativeCore.STRIDE > packed.size) break
        val index = packed[o].toInt()
        val place = places.getOrNull(index) ?: continue
        out += LabelItem(
            place = place,
            x = packed[o + 4],
            y = packed[o + 5],
            azDeg = packed[o + 1].toDouble(),
            elevDeg = packed[o + 2].toDouble(),
            distM = packed[o + 3].toDouble(),
            targetEle = packed[o + 11].toDouble(),
            visible = packed[o + 6] > 0.5f,
            hasTerrain = packed[o + 7] > 0.5f,
            score = packed[o + 8],
            boxW = packed[o + 9],
            boxH = packed[o + 10],
        )
    }
    return out
}
