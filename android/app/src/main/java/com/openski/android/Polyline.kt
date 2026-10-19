package com.openski.android

import kotlin.math.roundToLong

/**
 * The encoded polyline format (delta-encoded, zigzag, 5-bit chunks offset by 63) at 1e-6 degree precision, so a
 * point survives a round trip to within 0.5e-6 degrees (about 5 cm). Used to store resort geometry as text.
 */
object Polyline {
    const val FACTOR = 1e6

    fun encode(points: List<LatLon>): String {
        val out = StringBuilder(points.size * 8)
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * FACTOR).roundToLong()
            val lon = (p.lon * FACTOR).roundToLong()
            write(lat - lastLat, out)
            write(lon - lastLon, out)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    /** The points of [text], or null if it is not a whole, valid encoding. */
    fun decode(text: String): List<LatLon>? {
        val points = mutableListOf<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < text.length) {
            val dLat = read(text, index) ?: return null
            index = dLat.second
            val dLon = read(text, index) ?: return null
            index = dLon.second
            lat += dLat.first
            lon += dLon.first
            val p = LatLon(lat / FACTOR, lon / FACTOR)
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return null
            points.add(p)
        }
        return points
    }

    private fun write(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** One value starting at [start] and the index after it, or null when the text ends mid-value or holds a bad character. */
    private fun read(text: String, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= text.length || shift > 60) return null
            val b = text[i++].code - 63
            if (b !in 0..63) return null
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }
}
