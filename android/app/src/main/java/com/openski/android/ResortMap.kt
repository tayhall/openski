package com.openski.android

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** A latitude/longitude pair in degrees (WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/** What a resort way is: a downhill piste or a lift line. */
enum class WayKind { PISTE, LIFT }

/**
 * One piste or lift from OpenStreetMap, as cached on the phone. [points] follow the way's own direction: by OSM
 * convention a piste is drawn downhill and a lift line from bottom to top (not every mapper follows it).
 * [difficulty] is the raw `piste:difficulty` value and [aerialway] the raw `aerialway` type (null for pistes).
 */
data class ResortWay(
    val osmId: Long, val kind: WayKind, val name: String?, val ref: String?, val difficulty: String?,
    val aerialway: String?, val points: List<LatLon>,
)

/** The cached pistes and lifts of one resort. Pure data: filled from OpenStreetMap elsewhere, never fetched here. */
data class ResortMap(val ways: List<ResortWay>) {
    companion object {
        /** Required wherever names or geometry from the map are shown or exported (Open Database Licence). */
        const val ATTRIBUTION = "(c) OpenStreetMap contributors"
    }
}

/**
 * A flat local frame in metres (x east, y north) around an origin, good to well under a metre across a resort
 * (tens of kilometres). Used by the day splitter and the piste matcher.
 */
class LocalFrame(originLat: Double, private val originLon: Double) {
    private val lat0 = originLat
    private val mPerDegLat = EARTH_M * PI / 180.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(originLat))

    fun x(lon: Double) = (lon - originLon) * mPerDegLon
    fun y(lat: Double) = (lat - lat0) * mPerDegLat

    companion object {
        const val EARTH_M = 6_371_000.0
        fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1; val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }
    }
}
