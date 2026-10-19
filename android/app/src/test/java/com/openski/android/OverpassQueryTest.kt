package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassQueryTest {
    @Test fun boxQueryIsExactAndBounded() {
        val q = OverpassQuery.box(GeoBox(46.0, 7.0, 46.1, 7.2))
        val b = "(46.000000,7.000000,46.100000,7.200000)"
        assertEquals("""[out:json][timeout:60];
(
  way["piste:type"="downhill"]$b;
  relation["piste:type"="downhill"]$b;
  way["aerialway"]["aerialway"!~"^(station|pylon)${'$'}"]$b;
  node["aerialway"="station"]$b;
);
out meta geom;
""", q)
    }

    @Test fun areaQueryUsesTheEnclosingBox() {
        val box = GeoBox.around(LatLon(46.0, 7.0), 5_000.0)
        // 5 km is 0.04497 degrees of latitude and 0.04497 / cos(46 deg) = 0.06474 degrees of longitude.
        assertEquals(46.0 - 0.044966, box.south, 1e-5)
        assertEquals(46.0 + 0.044966, box.north, 1e-5)
        assertEquals(7.0 - 0.064731, box.west, 1e-5)
        assertEquals(7.0 + 0.064731, box.east, 1e-5)
        assertEquals(OverpassQuery.box(box), OverpassQuery.area(LatLon(46.0, 7.0), 5_000))
    }

    @Test fun radiusIsClamped() {
        assertEquals(OverpassQuery.area(LatLon(46.0, 7.0), OverpassQuery.MAX_RADIUS_M), OverpassQuery.area(LatLon(46.0, 7.0), 1_000_000))
        assertEquals(OverpassQuery.area(LatLon(46.0, 7.0), OverpassQuery.MIN_RADIUS_M), OverpassQuery.area(LatLon(46.0, 7.0), 1))
    }

    @Test fun numbersAreLocaleIndependent() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(OverpassQuery.box(GeoBox(46.5, 7.25, 46.75, 7.5)).contains("(46.500000,7.250000,46.750000,7.500000)"))
        } finally { java.util.Locale.setDefault(saved) }
    }

    @Test fun badCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GeoBox(46.1, 7.0, 46.0, 7.2) }
        assertThrows(IllegalArgumentException::class.java) { GeoBox.around(LatLon(89.0, 7.0), 1000.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoBox.around(LatLon(46.0, 7.0), 0.0) }
    }

    @Test fun searchQueryIsExact() {
        assertEquals("""[out:json][timeout:25];
(
  nwr["landuse"="winter_sports"]["name"~"Val d'Is.re",i];
  nwr["site"="piste"]["name"~"Val d'Is.re",i];
);
out body bb qt 20;
""", OverpassQuery.search("  Val  d'Is(re "))
    }

    @Test fun searchCanBeLimitedToABox() {
        val q = OverpassQuery.search("Example", GeoBox(46.0, 7.0, 46.1, 7.2))
        assertTrue(q.contains("[\"name\"~\"Example\",i](46.000000,7.000000,46.100000,7.200000);"))
    }

    @Test fun namePatternCannotEscapeTheString() {
        val p = OverpassQuery.namePattern("a\"];out;\\ (x)*.+")
        assertFalse(p.contains('"') || p.contains('\\') || p.contains(']') || p.contains(';') || p.contains('('))
        assertEquals("Saas-Fee", OverpassQuery.namePattern("Saas-Fee"))
        assertEquals("Les 3 Vallées", OverpassQuery.namePattern("Les 3 Vallées"))
        assertEquals(OverpassQuery.MAX_NAME_LENGTH, OverpassQuery.namePattern("x".repeat(200)).length)
        assertThrows(IllegalArgumentException::class.java) { OverpassQuery.namePattern(" a ") }
    }
}
