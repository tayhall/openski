package com.openski.android

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/** A latitude/longitude box in degrees, in Overpass order (south, west, north, east). */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south in -90.0..90.0 && north in -90.0..90.0 && south <= north) { "Bad latitude range $south..$north" }
        require(west in -180.0..180.0 && east in -180.0..180.0 && west <= east) { "Bad longitude range $west..$east (antimeridian boxes are not supported)" }
    }

    val centre: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)

    companion object {
        /** The box that encloses a circle of [radiusM] metres around [centre] (spherical earth; good to well under 1% at resort scale). */
        fun around(centre: LatLon, radiusM: Double): GeoBox {
            require(centre.lat in -MAX_CENTRE_LAT..MAX_CENTRE_LAT && centre.lon in -180.0..180.0) { "Centre out of range: $centre" }
            require(radiusM > 0 && radiusM.isFinite()) { "Radius must be positive" }
            val dLat = radiusM / EARTH_RADIUS_M * 180 / PI
            val dLon = dLat / cos(centre.lat * PI / 180)
            return GeoBox((centre.lat - dLat).coerceAtLeast(-90.0), (centre.lon - dLon).coerceAtLeast(-180.0),
                (centre.lat + dLat).coerceAtMost(90.0), (centre.lon + dLon).coerceAtMost(180.0))
        }

        const val EARTH_RADIUS_M = 6_371_008.8
        /** Box centres must lie within this latitude (degrees), where the longitude span stays meaningful. */
        const val MAX_CENTRE_LAT = 85.0
    }
}

/**
 * Overpass QL builders for resort data (experimental). One bounded query per resort: downhill pistes (ways and
 * relations, so area pistes come with their member ways), aerialway lines and aerialway stations, JSON output with the
 * server timeout written into the query. Pure strings; nothing here touches the network.
 */
object OverpassQuery {
    /** Server-side timeout for a resort download, in seconds. */
    const val AREA_TIMEOUT_S = 60
    /** Server-side timeout for a name search, in seconds. */
    const val SEARCH_TIMEOUT_S = 25
    /** The smallest and largest radius a resort download may ask for, in metres. */
    const val MIN_RADIUS_M = 500
    const val MAX_RADIUS_M = 25_000
    /** The most results a name search returns. */
    const val SEARCH_LIMIT = 20
    /** Longest name pattern sent to the server. */
    const val MAX_NAME_LENGTH = 60

    /** Pistes, lifts and lift stations within [radiusM] metres of [centre], as a bounding box (the radius is clamped). */
    fun area(centre: LatLon, radiusM: Int): String =
        box(GeoBox.around(centre, radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M).toDouble()))

    /** Pistes, lifts and lift stations inside [box]. */
    fun box(box: GeoBox): String {
        val b = bbox(box)
        return buildString {
            append("[out:json][timeout:").append(AREA_TIMEOUT_S).append("];\n")
            append("(\n")
            append("  way[\"piste:type\"=\"downhill\"]").append(b).append(";\n")
            append("  relation[\"piste:type\"=\"downhill\"]").append(b).append(";\n")
            append("  way[\"aerialway\"][\"aerialway\"!~\"^(station|pylon)$\"]").append(b).append(";\n")
            append("  node[\"aerialway\"=\"station\"]").append(b).append(";\n")
            append("  way[\"aerialway\"=\"station\"]").append(b).append(";\n")
            append(");\n")
            append("out meta geom;\n")
        }
    }

    /**
     * Resorts whose name matches [name] (case-insensitive): objects tagged `landuse=winter_sports` or `site=piste`,
     * optionally only inside [within]. Ways and relations come back with `bounds`, nodes with their position.
     */
    fun search(name: String, within: GeoBox? = null): String {
        val pattern = namePattern(name)
        val b = within?.let(::bbox) ?: ""
        return buildString {
            append("[out:json][timeout:").append(SEARCH_TIMEOUT_S).append("];\n")
            append("(\n")
            append("  nwr[\"landuse\"=\"winter_sports\"][\"name\"~\"").append(pattern).append("\",i]").append(b).append(";\n")
            append("  nwr[\"site\"=\"piste\"][\"name\"~\"").append(pattern).append("\",i]").append(b).append(";\n")
            append(");\n")
            append("out body bb qt ").append(SEARCH_LIMIT).append(";\n")
        }
    }

    /**
     * A safe regular expression for a typed resort name: letters, digits, spaces, hyphens and apostrophes are kept,
     * any other character becomes `.` (any one character), so nothing the user types can end the string or the
     * expression. Blank or too-short input is rejected.
     */
    fun namePattern(name: String): String {
        val trimmed = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)
        require(trimmed.length >= 2) { "Type at least two characters" }
        return trimmed.map { c -> if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '\'') c else '.' }.joinToString("")
    }

    private fun bbox(box: GeoBox) = "(" + listOf(box.south, box.west, box.north, box.east)
        .joinToString(",") { String.format(Locale.ROOT, "%.6f", it) } + ")"
}
