package com.openski.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiDayReportTest {
    private val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY)
    private val truthDescents = day.truth.filter { it.kind == SegmentKind.DESCENT }
    private val truthLifts = day.truth.filter { it.kind == SegmentKind.LIFT }

    @Test fun withoutAMapTheDayIsSplitButNothingIsNamed() {
        val r = SkiDayReport.build(day.fixes, emptyList(), null)
        assertFalse(r.mapUsed)
        assertNull(r.attribution)
        assertNull(r.matches)
        val t = r.totals
        assertEquals(3, t.runs); assertEquals(2, t.lifts)
        assertEquals(listOf("Run 1", "Run 2", "Run 3"), r.segments.filter { it.kind == SegmentKind.DESCENT }.map { it.title })
        assertEquals(listOf("Lift 1", "Lift 2"), r.segments.filter { it.kind == SegmentKind.LIFT }.map { it.title })
        assertTrue(r.segments.all { it.wayLabel == null && it.grade == null && it.subtitle == null && it.boot == null })
        // Vertical descended against the truth (GPS altitude: within 3 m + 3 % + 15 m per run, as the splitter tests allow).
        val truthDown = truthDescents.sumOf { -it.verticalM }
        assertEquals(truthDown, t.descendedM!!, 3 * (3 + 15.0) + 0.03 * truthDown)
        val truthUp = truthLifts.sumOf { it.verticalM }
        assertEquals(truthUp, t.climbedM!!, 2 * (3 + 15.0) + 0.03 * truthUp)
        assertEquals(truthDescents.sumOf { (it.endMs - it.startMs) / 1000.0 }, t.skiingS, 3 * 20.0)
        assertEquals(truthLifts.sumOf { (it.endMs - it.startMs) / 1000.0 }, t.liftS, 2 * 20.0)
        // Every second of the day is in exactly one segment kind (no long gaps in this day).
        assertEquals((day.fixes.last().timeMs - day.fixes.first().timeMs) / 1000.0, t.skiingS + t.liftS + t.restS, 1e-6)
        val longest = r.segments[t.longestRunIndex!!]
        assertEquals(SegmentKind.DESCENT, longest.kind)
        assertEquals(r.segments.filter { it.kind == SegmentKind.DESCENT }.maxOf { it.segment.distanceM }, t.longestRunM!!, 0.0)
        assertNotNull(t.topSpeedMps)
    }

    @Test fun withAMapRunsAndLiftsAreNamedWithGradesAndAttribution() {
        val map = SkiDaySplitterTest.mapFrom(day)
        val r = SkiDayReport.build(day.fixes, emptyList(), map)
        assertTrue(r.mapUsed)
        assertEquals(ResortRepository.ATTRIBUTION, r.attribution)
        val runs = r.segments.filter { it.kind == SegmentKind.DESCENT }
        assertEquals(listOf("piste 1", "piste 5", "piste 9"), runs.map { it.wayLabel })
        assertTrue(runs.all { it.grade == "intermediate" })
        assertEquals("piste 1 · intermediate", runs[0].subtitle)
        val lifts = r.segments.filter { it.kind == SegmentKind.LIFT }
        assertEquals(listOf("lift 3", "lift 7"), lifts.map { it.wayLabel })
        assertTrue(lifts.all { it.grade == "chair lift" })
        assertTrue(r.segments.filter { it.kind == SegmentKind.REST }.all { it.match == null && it.subtitle == null })
    }

    @Test fun anUnmatchedMapStillCountsAsUsedButNamesNothing() {
        val far = ResortMap(listOf(ResortWay(1, WayKind.PISTE, "Far", null, "easy", null, listOf(LatLon(47.0, 8.0), LatLon(47.01, 8.0)))))
        val r = SkiDayReport.build(day.fixes, emptyList(), far)
        assertTrue(r.mapUsed)
        assertEquals(ResortRepository.ATTRIBUTION, r.attribution)
        assertTrue(r.segments.all { it.wayLabel == null })
        assertTrue(r.segments.filter { it.kind != SegmentKind.REST }.all { it.match?.status == MatchStatus.UNMATCHED })
    }

    @Test fun bootNumbersAttachOnlyToDescentsWithOverlappingData() {
        val second = truthDescents[1]
        // A sensor session that covers the second descent only, with movements every 1.5 s.
        val starts = generateSequence(second.startMs + 1000.0) { it + 1500.0 }.takeWhile { it < second.endMs }.toList()
        val ev = SessionBootEvents("s", second.startMs - 30_000, second.endMs + 30_000, mapOf("L" to starts, "R" to starts.map { it + 80 }),
            emptyMap(), emptyMap(), emptyList(), emptyList(), null)
        val asked = ArrayList<Pair<Long, Long>>()
        val r = SkiDayReport.build(day.fixes, emptyList(), null, { a, b -> asked.add(a to b); DayBootStats.forRange(listOf(ev), a, b) })
        val runs = r.segments.filter { it.kind == SegmentKind.DESCENT }
        assertEquals(3, asked.size)   // asked once per descent, never for lifts or rests
        assertNull(runs[0].boot); assertNull(runs[2].boot)
        val boot = runs[1].boot!!
        val inRun = starts.count { it >= runs[1].segment.startMs && it < runs[1].segment.endMs }
        assertEquals(inRun, boot.movements["L"])
        assertTrue("most movements fall in the detected run", inRun >= starts.size - 10)
        assertTrue(r.segments.filter { it.kind != SegmentKind.DESCENT }.all { it.boot == null })
        // Without the lambda nothing is attached.
        assertTrue(SkiDayReport.build(day.fixes, emptyList(), null).segments.all { it.boot == null })
    }

    @Test fun storedFixesWithBarometerUseTheBarometer() {
        val rows = day.fixes.zip(day.baro).map { (f, b) -> DayFix(f, b.altM) }
        val r = SkiDayReport.build(rows, null)
        assertEquals(AltitudeSource.BAROMETER, r.split.altitudeSource)
        assertEquals(3, r.totals.runs)
        assertEquals(day.baro.size, SkiDayReport.baroSeries(rows).size)
        assertEquals(AltitudeSource.GPS, SkiDayReport.build(day.fixes.map { DayFix(it) }, null).split.altitudeSource)
    }

    @Test fun withoutAltitudeThereIsNoVerticalAndTooFewFixesGiveAnEmptyDay() {
        val flat = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, withAltitude = false)
        val r = SkiDayReport.build(flat.fixes, emptyList(), null)
        assertNull(r.totals.descendedM); assertNull(r.totals.climbedM)
        val empty = SkiDayReport.build(day.fixes.take(1), emptyList(), null)
        assertTrue(empty.segments.isEmpty())
        assertEquals(0, empty.totals.runs)
        assertNull(empty.totals.longestRunIndex); assertNull(empty.totals.topSpeedMps)
        assertNull(empty.totals.descendedM)   // too few fixes: no altitude source, so no vertical rather than zero
    }

    @Test fun wayNamesFallBackToRefThenUnnamed() {
        fun way(name: String?, ref: String?, kind: WayKind = WayKind.PISTE) = ResortWay(1, kind, name, ref, null, null, emptyList())
        assertEquals("Combe", SkiDayReport.wayName(way(" Combe ", "12")))
        assertEquals("Piste 12", SkiDayReport.wayName(way("", "12")))
        assertEquals("Lift C4", SkiDayReport.wayName(way(null, "C4", WayKind.LIFT)))
        assertEquals("unnamed lift", SkiDayReport.wayName(way(null, " ", WayKind.LIFT)))
        val a = MatchedWay(way("Blue 12", null).copy(difficulty = "novice"), 0.3, 0.9, 0, 3)
        val b = MatchedWay(way("Combe", null).copy(osmId = 2, difficulty = "advanced"), 0.6, 0.9, 4, 9)
        val m = WayMatch(MatchStatus.MATCHED, listOf(a, b, a.copy(firstIndex = 10)), 1.0, 0.9)
        assertEquals("Blue 12 → Combe → Blue 12", SkiDayReport.label(m))
        assertEquals("advanced", SkiDayReport.grade(SegmentKind.DESCENT, m))
        assertEquals("easy", SkiDayReport.grade(SegmentKind.DESCENT, WayMatch(MatchStatus.MATCHED, listOf(a), 1.0, 0.9)))
        assertEquals("gondola", SkiDayReport.grade(SegmentKind.LIFT, WayMatch(MatchStatus.MATCHED,
            listOf(MatchedWay(way("G3", null, WayKind.LIFT).copy(aerialway = "gondola"), 1.0, 0.9, 0, 1)), 1.0, 0.9)))
    }

    private fun finiteEverywhere(v: Any?): Boolean = when (v) {
        is JSONObject -> v.keys().asSequence().all { finiteEverywhere(v.get(it)) }
        is org.json.JSONArray -> (0 until v.length()).all { finiteEverywhere(v.get(it)) }
        is Double -> v.isFinite()
        is Float -> v.isFinite()
        else -> true
    }

    @Test fun theDayDocumentCarriesTagsBootNumbersResortAndAttribution() {
        val map = SkiDaySplitterTest.mapFrom(day)
        val second = truthDescents[1]
        val ev = SessionBootEvents("s", second.startMs, second.endMs, mapOf("L" to listOf(second.startMs + 5_000.0)),
            emptyMap(), emptyMap(), emptyList(), emptyList(), "refused: no phone motion rows")
        val r = SkiDayReport.build(day.fixes, day.baro, map, { a, b -> DayBootStats.forRange(listOf(ev), a, b) })
        val runs = r.segments.filter { it.kind == SegmentKind.DESCENT }
        val dayTags = DayTags(snow = "packed", crowd = "busy")
        val skiDay = SkiDay("d", "Test day", day.fixes.first().timeMs, day.fixes.last().timeMs, DaySource.PHONE, "osm-way-1",
            "Good day", dayTags, 1L)
        val tags = listOf(RunTag(runs[0].segment.startMs + 2_000, DayTags(drill = "Edge rolls"), "first"),
            RunTag(runs[0].segment.startMs - 3_600_000, DayTags(), "orphan"))
        val json = JSONObject(SkiDayExport.dayJson(skiDay, r, tags, "Synthetic Peak", day.baro.take(3)).toString())
        assertEquals(SkiDayExport.FORMAT, json.getString("format"))
        assertEquals(ResortRepository.ATTRIBUTION, json.getString("attribution"))
        assertEquals("Synthetic Peak", json.getJSONObject("resort").getString("name"))
        assertTrue(json.getJSONObject("resort").getBoolean("map_used"))
        val d = json.getJSONObject("day")
        assertEquals("Test day", d.getString("title")); assertEquals("phone", d.getString("source"))
        assertEquals("packed", d.getJSONObject("tags").getString("snow"))
        assertTrue(d.getJSONObject("tags").isNull("visibility"))
        assertEquals("Good day", d.getString("notes"))
        val segs = json.getJSONArray("segments")
        val firstRun = (0 until segs.length()).map { segs.getJSONObject(it) }.first { it.getString("kind") == "descent" }
        assertEquals("Run 1", firstRun.getString("title"))
        assertEquals("piste 1", firstRun.getString("name"))
        assertEquals("intermediate", firstRun.getString("grade"))
        assertEquals("first", firstRun.getJSONObject("run_tags").getString("note"))
        assertTrue(firstRun.isNull("boot"))
        val secondRun = segs.getJSONObject(runs[1].index)
        assertEquals(1, secondRun.getJSONObject("boot").getJSONObject("movements").getInt("L"))
        assertEquals("torso metrics: refused: no phone motion rows", secondRun.getJSONObject("boot").getJSONArray("notes").getString(0))
        assertEquals("orphan", json.getJSONArray("unattached_run_tags").getJSONObject(0).getString("note"))
        assertEquals(3, json.getJSONObject("totals").getInt("runs"))
        assertEquals(3, json.getJSONObject("barometer").getJSONArray("samples").length())
        assertEquals(DayBootStats.NOTE, json.getString("boot_note"))
        assertTrue(Regex("NaN|Infinity").find(json.toString()) == null)
        assertTrue("every number in the document is finite", finiteEverywhere(json))
        // Without a map or resort: no attribution and no resort block.
        val plain = JSONObject(SkiDayExport.dayJson(skiDay.copy(resortId = null), SkiDayReport.build(day.fixes, emptyList(), null)).toString())
        assertTrue(plain.isNull("attribution")); assertTrue(plain.isNull("resort"))
    }
}
