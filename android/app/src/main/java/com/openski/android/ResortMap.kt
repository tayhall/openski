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
    /** The ways projected and gridded for matching; built on first use and reused (not part of equality). */
    internal val index: ResortIndex by lazy { ResortIndex(ways) }

    companion object {
        /** Required wherever names or geometry from the map are shown or exported (Open Database Licence). */
        const val ATTRIBUTION = "(c) OpenStreetMap contributors"
    }
}

/**
 * A flat local frame in metres (x east, y north) around an origin, used by the day splitter and the piste matcher.
 * East-west distances use the cosine of the origin's latitude, so their scale is off by about tan(latitude) times
 * the north-south offset from the origin in radians: at 46 degrees about 0.1 % (1 m per km) 7 km north or south of
 * the origin, and 0.2 % at 15 km. Callers centre the frame on the area they measure ([around]). Longitudes are not
 * unwrapped: an area crossing the 180 degree meridian is not supported.
 */
class LocalFrame(originLat: Double, private val originLon: Double) {
    private val lat0 = originLat
    private val mPerDegLat = EARTH_M * PI / 180.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(originLat))

    fun x(lon: Double) = (lon - originLon) * mPerDegLon
    fun y(lat: Double) = (lat - lat0) * mPerDegLat

    companion object {
        const val EARTH_M = 6_371_000.0

        /** A frame centred on the bounding box of [points] (finite ones only); the origin when there are none. */
        fun around(points: Iterable<LatLon>): LocalFrame {
            var minLat = Double.POSITIVE_INFINITY; var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY; var maxLon = Double.NEGATIVE_INFINITY
            for (p in points) {
                if (!p.lat.isFinite() || !p.lon.isFinite()) continue
                minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
                minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
            }
            return if (minLat > maxLat) LocalFrame(0.0, 0.0) else LocalFrame((minLat + maxLat) / 2, (minLon + maxLon) / 2)
        }

        fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1; val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }
    }
}
