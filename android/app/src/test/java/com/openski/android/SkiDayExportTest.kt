package com.openski.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiDayExportTest {
    private val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY)

    @Test fun aDayWithAMapCarriesNamesScoresThresholdsAndAttribution() {
        val map = SkiDaySplitterTest.mapFrom(day)
        val split = SkiDaySplitter.split(day.fixes, map = map)
        val json = JSONObject(SkiDayExport.toJson(split, PisteMatcher.matchAll(split, map)).toString())
        assertEquals(SkiDayExport.FORMAT, json.getString("format"))
        assertTrue(json.getBoolean("experimental"))
        assertTrue(json.getString("note").startsWith("EXPERIMENTAL"))
        assertEquals(ResortMap.ATTRIBUTION, json.getString("attribution"))
        assertEquals("gps", json.getString("altitude_source"))
        assertEquals(SkiDayConfig().asMap().size, json.getJSONObject("thresholds").length())
        assertEquals(0.8, json.getJSONObject("thresholds").getDouble("rest_enter_mps"), 0.0)
        val segments = json.getJSONArray("segments")
        assertEquals(split.segments.size, segments.length())
        val first = segments.getJSONObject(0)
        assertEquals("rest", first.getString("kind"))
        assertEquals("not_attempted", first.getJSONObject("match").getString("status"))
        val descent = segments.getJSONObject(1)
        assertEquals("descent", descent.getString("kind"))
        val match = descent.getJSONObject("match")
        assertEquals("matched", match.getString("status"))
        val way = match.getJSONArray("ways").getJSONObject(0)
        assertEquals(1001L, way.getLong("osm_id"))
        assertEquals("piste 1", way.getString("name"))
        assertEquals("intermediate", way.getString("difficulty"))
        assertTrue(way.isNull("ref"))
        assertTrue(way.getDouble("score") > 0.5)
        assertTrue(descent.getDouble("vertical_m") < 0)
        assertEquals("2026-01-10T09:00:00Z", first.getString("start_utc"))
    }

    @Test fun withoutAMatchThereIsNoAttribution() {
        val split = SkiDaySplitter.split(day.fixes)
        val json = SkiDayExport.toJson(split)
        assertTrue(json.isNull("attribution"))
        val far = ResortMap(listOf(ResortWay(1, WayKind.PISTE, "Far", null, null, null,
            listOf(LatLon(47.0, 8.0), LatLon(47.01, 8.0)))))
        val unmatched = SkiDayExport.toJson(split, PisteMatcher.matchAll(split, far))
        assertTrue(unmatched.isNull("attribution"))
        assertEquals("unmatched", unmatched.getJSONArray("segments").getJSONObject(1).getJSONObject("match").getString("status"))
    }

    @Test fun nonFiniteNumbersBecomeNull() {
        val s = SkiDaySegment(SegmentKind.DESCENT, 0, 1000, 0, 1, Double.NaN, null, Double.POSITIVE_INFINITY, null, null, emptyList())
        val split = SkiDaySplit(emptyList(), listOf(s), AltitudeSource.NONE, emptyList(), 0, SkiDayConfig())
        val m = WayMatch(MatchStatus.UNMATCHED, emptyList(), Double.NaN, Double.NaN)
        val json = JSONObject(SkiDayExport.toJson(split, listOf(m)).toString())
        val seg = json.getJSONArray("segments").getJSONObject(0)
        assertTrue(seg.isNull("distance_m"))
        assertTrue(seg.isNull("vertical_m"))
        assertTrue(seg.isNull("mean_speed_mps"))
        assertTrue(seg.isNull("map_confirmed"))
        assertTrue(seg.getJSONObject("match").isNull("score"))
        assertFalse(json.toString().contains("NaN"))
        assertEquals(JSONObject.NULL, SkiDayExport.json(Float.NaN))
    }
}
