package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PolylineTest {
    @Test fun roundTripWithinOneMicrodegree() {
        val random = Random(12)
        repeat(50) {
            val points = List(random.nextInt(2, 200)) {
                LatLon(random.nextDouble(-90.0, 90.0), random.nextDouble(-180.0, 180.0))
            }
            val decoded = Polyline.decode(Polyline.encode(points))!!
            assertEquals(points.size, decoded.size)
            points.zip(decoded).forEach { (a, b) ->
                check(abs(a.lat - b.lat) <= 0.5e-6 + 1e-12 && abs(a.lon - b.lon) <= 0.5e-6 + 1e-12) { "$a -> $b" }
            }
        }
    }

    @Test fun exactMicrodegreesSurviveExactly() {
        val points = listOf(LatLon(46.010123, 7.010456), LatLon(46.010124, 7.010455), LatLon(-0.000001, 0.0), LatLon(90.0, -180.0), LatLon(-90.0, 180.0))
        assertEquals(points, Polyline.decode(Polyline.encode(points)))
    }

    @Test fun knownEncoding() {
        // The classic precision-5 example (38.5,-120.2),(40.7,-120.95),(43.252,-126.453) is "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
        // at precision 6 the same deltas scaled by 10 encode to this string.
        val points = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        val encoded = Polyline.encode(points)
        assertEquals(points, Polyline.decode(encoded))
        assertEquals("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", encoded)
    }

    @Test fun emptyAndMalformed() {
        assertEquals("", Polyline.encode(emptyList()))
        assertEquals(emptyList<LatLon>(), Polyline.decode(""))
        val good = Polyline.encode(listOf(LatLon(46.0, 7.0), LatLon(46.1, 7.1)))
        assertNull("cut mid-value", Polyline.decode(good.dropLast(1)))
        // A longitude of 0 encodes as the single character '?', so dropping it leaves one complete latitude value.
        val latOnly = Polyline.encode(listOf(LatLon(46.0, 0.0))).also { assertEquals('?', it.last()) }.dropLast(1)
        assertNull("lat without lon", Polyline.decode(latOnly))
        assertNull("character below the alphabet", Polyline.decode(" " + good))
        assertNull("out of range", Polyline.decode(Polyline.encode(listOf(LatLon(89.0, 0.0), LatLon(89.0, 0.0))) + Polyline.encode(listOf(LatLon(89.0, 0.0)))))
    }
}
