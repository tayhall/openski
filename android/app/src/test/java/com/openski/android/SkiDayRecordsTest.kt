package com.openski.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class SkiDayRecordsTest {
    @Test fun tagsRoundTripThroughJson() {
        val tags = DayTags(snow = "packed", visibility = "flat light", crowd = null, drill = "Edge rolls")
        val json = tags.toJson()
        assertFalse(JSONObject(json).has("crowd"))
        assertEquals(tags, DayTags.fromJson(json))
        assertEquals(DayTags(), DayTags.fromJson(DayTags().toJson()))
        assertTrue(DayTags().isEmpty)
        assertEquals(listOf("Snow" to "packed", "Visibility" to "flat light", "Drill" to "Edge rolls"), tags.entries())
        // A value outside the offered lists still round-trips: stored text is free.
        assertEquals("spring snow", DayTags.fromJson(DayTags(snow = "spring snow").toJson()).snow)
    }

    @Test fun tagParsingIsLenient() {
        assertEquals(DayTags(), DayTags.fromJson(null))
        assertEquals(DayTags(), DayTags.fromJson(""))
        assertEquals(DayTags(), DayTags.fromJson("not json"))
        assertEquals(DayTags(), DayTags.fromJson("[1,2]"))
        assertEquals(DayTags(snow = "icy"), DayTags.fromJson("""{"snow":" icy ","crowd":3,"visibility":"  ","unknown":"x","drill":null}"""))
        val long = DayTags.fromJson(JSONObject().put("snow", "a".repeat(500) + "\u0007").toString()).snow!!
        assertEquals(DayTags.MAX_LENGTH, long.length)
        assertNull(DayTags.clean("\u0000\u0001 "))
    }

    @Test fun aDayNeedsAKnownSource() {
        assertThrows(IllegalArgumentException::class.java) { SkiDay("x", "", 0, null, "watch", null, "", DayTags(), 0) }
        assertTrue(SkiDay("x", "", 0, null, DaySource.PHONE, null, "", DayTags(), 0).open)
    }

    @Test fun fixesAreWrittenInSmallChunks() {
        val rows = (0 until 450).toList()
        assertEquals(listOf(200, 200, 50), DayRules.chunks(rows).map { it.size })
        assertEquals(rows, DayRules.chunks(rows).flatten())
        assertTrue(DayRules.chunks(emptyList<Int>()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { DayRules.chunks(rows, 0) }
    }

    @Test fun onlyFinitePositionsAreStorable() {
        fun f(lat: Double, lon: Double) = GpsFix(0, lat, lon, Double.NaN, null, Float.NaN, null)
        assertTrue(DayRules.storable(f(46.0, 7.0)))
        assertTrue(DayRules.storable(f(-90.0, 180.0)))
        listOf(f(Double.NaN, 7.0), f(46.0, Double.POSITIVE_INFINITY), f(91.0, 7.0), f(46.0, -181.0)).forEach { assertFalse(DayRules.storable(it)) }
    }

    private fun day(id: String, start: Long, end: Long?, source: String = DaySource.PHONE) =
        SkiDay(id, id, start, end, source, null, "", DayTags(), start)

    @Test fun theLatestOpenPhoneDayIsResumed() {
        assertNull(DayRules.resumable(emptyList()))
        assertNull(DayRules.resumable(listOf(day("closed", 1, 2), day("gpx", 5, null, DaySource.GPX))))
        assertEquals("b", DayRules.resumable(listOf(day("a", 1, null), day("b", 3, null), day("c", 9, 10)))!!.id)
    }

    @Test fun aDayLeftOpenOvernightIsClosedAtItsLastFixInsteadOfResumed() {
        val h = 3_600_000L
        val fresh = day("today", 20 * h, null)
        val stale = day("yesterday", 2 * h, null)
        val gpx = day("gpx", 21 * h, null, DaySource.GPX)
        val last = mapOf("today" to 21 * h, "yesterday" to 9 * h)
        val plan = DayRules.resumePlan(listOf(stale, fresh, gpx, day("closed", 1, 2)), { last[it.id] }, nowMs = 22 * h)
        assertEquals(fresh, plan.resume)
        assertEquals(listOf("yesterday" to 9 * h), plan.close)
        // The latest open day itself goes stale after 12 h without a fix, and closes at that fix.
        val later = DayRules.resumePlan(listOf(fresh), { last[it.id] }, nowMs = 21 * h + DayTrackingConfig.STALE_OPEN_DAY_MS + 1)
        assertNull(later.resume)
        assertEquals(listOf("today" to 21 * h), later.close)
        // A day without fixes is judged and closed by its start.
        val empty = DayRules.resumePlan(listOf(fresh), { null }, nowMs = 20 * h + 1000)
        assertEquals(fresh, empty.resume)
        assertEquals(listOf("today" to 20 * h), DayRules.resumePlan(listOf(fresh), { null }, nowMs = 40 * h).close)
    }

    @Test fun aRunTagAttachesToTheDescentContainingItsKey() {
        val tags = listOf(RunTag(1_000, DayTags(snow = "icy"), ""), RunTag(1_500, DayTags(crowd = "busy"), ""), RunTag(5_000, DayTags(), "late"))
        assertEquals(1_000L, DayRules.tagFor(900, 2_000, tags)!!.segmentStartMs)     // closest to the start
        assertEquals(1_500L, DayRules.tagFor(1_200, 2_000, tags)!!.segmentStartMs)
        assertEquals(5_000L, DayRules.tagFor(4_000, 5_000, tags)!!.segmentStartMs)   // the end is inclusive
        assertNull(DayRules.tagFor(2_001, 4_999, tags))
        assertTrue(RunTag(0, DayTags(), " ").isEmpty)
    }

    @Test fun notesAndTitlesAreCleaned() {
        assertEquals("Two\nlines", DayRules.cleanNote("  Two\nlines\u0007 "))
        assertEquals(DayRules.MAX_NOTE_LENGTH, DayRules.cleanNote("x".repeat(5000)).length)
        assertEquals("Title", DayRules.cleanTitle(" Ti\ntle "))
        assertEquals("Ski day 10 Jan 2026", DayRules.defaultTitle(SyntheticSkiDay.T0_MS, ZoneOffset.UTC, Locale.UK))
    }

    @Test fun anImportedGpxBecomesAClosedDay() {
        val fixes = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY).fixes
        val named = DayRules.gpxDay("g", GpxRead(fixes.shuffled(java.util.Random(3)), "Morning laps", 0, 0), 99, ZoneOffset.UTC)
        assertEquals(DaySource.GPX, named.source)
        assertEquals("Morning laps", named.title)
        assertEquals(fixes.first().timeMs, named.startedAtMs)
        assertEquals(fixes.last().timeMs, named.endedAtMs)
        assertFalse(named.open)
        assertEquals(99L, named.createdAtMs)
        val unnamed = DayRules.gpxDay("g", GpxRead(fixes, "  ", 0, 0), 0, ZoneOffset.UTC)
        assertTrue(unnamed.title, unnamed.title.startsWith("Imported day"))
        assertThrows(IllegalArgumentException::class.java) { DayRules.gpxDay("g", GpxRead(emptyList(), null, 3, 0), 0) }
        // GPX round trip into a day keeps every fix untouched.
        val read = GpxCodec.read(GpxCodec.write(fixes, "x"))
        assertEquals(fixes, read.fixes)
    }
}
