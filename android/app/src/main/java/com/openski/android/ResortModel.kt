package com.openski.android

/** A WGS84 position in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/** What an OpenStreetMap way in a resort map is: a downhill piste or an aerialway (lift). */
enum class WayKind { PISTE, LIFT }

/**
 * One piste or lift line from OpenStreetMap. [difficulty] is the normalised `piste:difficulty`, [aerialway] the raw
 * `aerialway` value, [points] the line in way order (a piste way is drawn downhill). Experimental.
 */
data class ResortWay(
    val osmId: Long,
    val kind: WayKind,
    val name: String?,
    val ref: String?,
    val difficulty: String?,
    val aerialway: String?,
    val points: List<LatLon>,
)
