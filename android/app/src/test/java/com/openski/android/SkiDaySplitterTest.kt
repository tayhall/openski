package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Day splitting against synthetic days with known truth. Not covered: real phone GPS (multipath under lift
 * cables, tunnels, dropouts of minutes), real barometer drift with weather, drag lifts, chairlifts with flat or
 * downhill stretches, walking uphill on foot, skinning, very slow beginners, and fix rates other than 1 Hz.
 */
class SkiDaySplitterTest {
    private val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY)

    private fun describe(split: SkiDaySplit) = split.segments.joinToString("\n") { s ->
        "${s.kind} ${(s.startMs - SyntheticSkiDay.T0_MS) / 1000}..${(s.endMs - SyntheticSkiDay.T0_MS) / 1000} s " +
            "dist=${s.distanceM.toInt()} vert=${s.verticalM?.toInt()} mean=${s.meanSpeedMps} top=${s.topSpeedMps} ${s.notes}"
    }

    private fun assertMatchesTruth(split: SkiDaySplit, truth: List<SyntheticSkiDay.Truth>, boundaryS: Double, verticalFraction: Double) {
        val text = describe(split)
        assertEquals(text, truth.map { it.kind }, split.segments.map { it.kind })
        for ((s, tr) in split.segments.zip(truth)) {
            // The first segment starts at the first fix and the last ends at the last fix, so only inner boundaries move.
            assertEquals("start of ${tr.kind}\n$text", tr.startMs / 1000.0, s.startMs / 1000.0, boundaryS)
            assertEquals("end of ${tr.kind}\n$text", tr.endMs / 1000.0, s.endMs / 1000.0, boundaryS)
            if (tr.kind != SegmentKind.REST) {
                assertEquals("vertical of ${tr.kind}\n$text", tr.verticalM, s.verticalM!!, abs(tr.verticalM) * verticalFraction + 15)
            }
        }
    }

    @Test fun aFullDaySplitsIntoThreeDescentsTwoLiftsAndRestsWithGpsAltitude() {
        val split = SkiDaySplitter.split(day.fixes)
        assertEquals(AltitudeSource.GPS, split.altitudeSource)
        assertMatchesTruth(split, day.truth, boundaryS = 20.0, verticalFraction = 0.12)
        assertTrue(split.notes.contains(SkiDaySplitter.NOTE_GPS_ALTITUDE))
        assertTrue(split.notes.contains(SkiDaySplitter.NOTE_NO_MAP))
        split.segments.forEach {
            assertTrue(it.notes.contains(SkiDaySplitter.NOTE_GPS_ALTITUDE))
            assertNull(it.mapConfirmed)
        }
        // Segments tile the day: each starts where the previous ended.
        split.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.lastIndex, b.firstIndex) }
        assertEquals(0, split.segments.first().firstIndex)
        assertEquals(split.fixes.size - 1, split.segments.last().lastIndex)
    }

    @Test fun descentNumbersAreCloseToTruth() {
        val split = SkiDaySplitter.split(day.fixes)
        val descents = split.segments.filter { it.kind == SegmentKind.DESCENT }
        val truths = day.truth.filter { it.kind == SegmentKind.DESCENT }
        for ((s, tr) in descents.zip(truths)) {
            // Distance is measured on 5 s smoothed positions, which cut the turn wiggle: expect 75 to 105 % of the path.
            assertTrue("distance ${s.distanceM} vs path ${tr.pathM}", s.distanceM in tr.pathM * 0.75..tr.pathM * 1.05)
            assertTrue("top ${s.topSpeedMps}", s.topSpeedMps!! in 7.0..11.5)
            assertTrue("mean ${s.meanSpeedMps}", s.meanSpeedMps!! in 6.0..10.5)
        }
    }

    @Test fun theStopMidDescentAndTheStoppedLiftStayInsideOneSegmentAndAreNoted() {
        val split = SkiDaySplitter.split(day.fixes)
        val lift1 = split.segments.filter { it.kind == SegmentKind.LIFT }[0]
        val descent2 = split.segments.filter { it.kind == SegmentKind.DESCENT }[1]
        assertTrue(describe(split), lift1.notes.any { it.startsWith("includes") && it.endsWith("s stopped") })
        assertTrue(describe(split), descent2.notes.any { it.startsWith("includes") && it.endsWith("s stopped") })
        assertEquals(2.5, lift1.meanSpeedMps!!, 0.4)
    }

    @Test fun aBarometerGivesTighterBoundariesAndVerticalsEvenWithPoorGpsAltitude() {
        val noisy = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, seed = 11, gpsAltNoiseM = 15.0)
        val split = SkiDaySplitter.split(noisy.fixes, noisy.baro)
        assertEquals(AltitudeSource.BAROMETER, split.altitudeSource)
        assertTrue(split.segments.all { it.notes.contains(SkiDaySplitter.NOTE_BARO_ALTITUDE) })
        assertMatchesTruth(split, noisy.truth, boundaryS = 15.0, verticalFraction = 0.05)
    }

    @Test fun aBarometerThatCoversTooLittleOfTheDayFallsBackToGpsAltitude() {
        val partial = day.baro.take(day.baro.size / 2)
        val split = SkiDaySplitter.split(day.fixes, partial)
        assertEquals(AltitudeSource.GPS, split.altitudeSource)
        assertTrue(split.notes.contains(SkiDaySplitter.NOTE_BARO_SHORT))
        assertMatchesTruth(split, day.truth, boundaryS = 20.0, verticalFraction = 0.12)
    }

    @Test fun otherNoiseSeedsGiveTheSameSegments() {
        for (seed in listOf(1L, 2L, 3L, 4L, 5L)) {
            val d = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, seed = seed)
            assertMatchesTruth(SkiDaySplitter.split(d.fixes), d.truth, boundaryS = 20.0, verticalFraction = 0.12)
        }
    }

    @Test fun reportedSpeedsAreUsedForTopSpeed() {
        val d = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, reportSpeed = true)
        val split = SkiDaySplitter.split(d.fixes)
        val top = split.segments.filter { it.kind == SegmentKind.DESCENT }.map { it.topSpeedMps!! }
        // Truth: 10, 10 and 9 m/s with 0.2 m/s noise on each reported speed; the held value sits just under the peak.
        top.zip(listOf(10.0, 10.0, 9.0)).forEach { (measured, truth) -> assertEquals(truth, measured, 0.5) }
    }

    @Test fun withoutAltitudeMovingTimeIsDescentUnlessOnAMappedLiftLine() {
        val d = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, withAltitude = false)
        val bare = SkiDaySplitter.split(d.fixes)
        assertEquals(AltitudeSource.NONE, bare.altitudeSource)
        assertTrue(bare.notes.contains(SkiDaySplitter.NOTE_NO_ALTITUDE))
        assertFalse(describe(bare), bare.segments.any { it.kind == SegmentKind.LIFT })
        bare.segments.forEach { assertNull(it.verticalM) }

        val map = mapFrom(d)
        val withMap = SkiDaySplitter.split(d.fixes, map = map)
        assertEquals(describe(withMap), d.truth.map { it.kind }, withMap.segments.map { it.kind })
    }

    @Test fun aMapConfirmsLiftsAndDescents() {
        val split = SkiDaySplitter.split(day.fixes, map = mapFrom(day))
        assertMatchesTruth(split, day.truth, boundaryS = 20.0, verticalFraction = 0.12)
        split.segments.forEach { s ->
            if (s.kind == SegmentKind.REST) assertNull(s.mapConfirmed)
            else assertEquals(describe(split), true, s.mapConfirmed)
        }
        assertFalse(split.notes.contains(SkiDaySplitter.NOTE_NO_MAP))
    }

    @Test fun aLiftAwayFromAnyMappedLiftLineIsNotConfirmed() {
        // Only pistes in the map: lifts keep their kind (altitude says so) but carry an unconfirmed note.
        val pistesOnly = ResortMap(mapFrom(day).ways.filter { it.kind == WayKind.PISTE })
        val split = SkiDaySplitter.split(day.fixes, map = pistesOnly)
        val lifts = split.segments.filter { it.kind == SegmentKind.LIFT }
        assertEquals(2, lifts.size)
        lifts.forEach { l ->
            assertEquals(false, l.mapConfirmed)
            assertTrue(l.notes.any { it.startsWith("not confirmed") })
        }
    }

    @Test fun missingAndNonFiniteFieldsDoNotBreakTheSplit() {
        val fixes = day.fixes.mapIndexed { i, f ->
            when (i % 7) {
                0 -> f.copy(altM = Double.NaN)
                1 -> f.copy(altM = null)
                2 -> f.copy(speedMps = Double.NaN)
                3 -> f.copy(verticalAccuracyM = Float.NaN)   // unknown quality: altitude refused
                else -> f
            }
        } + GpsFix(day.fixes.last().timeMs + 500, Double.NaN, 7.0, 1.0, null, 5f, null)
        val baro = day.baro.mapIndexed { i, b -> if (i % 5 == 0) b.copy(altM = Double.NaN) else b }
        val split = SkiDaySplitter.split(fixes, baro)
        assertEquals(AltitudeSource.BAROMETER, split.altitudeSource)
        assertEquals(describe(split), day.truth.map { it.kind }, split.segments.map { it.kind })
        val gpsOnly = SkiDaySplitter.split(fixes)
        assertEquals(describe(gpsOnly), day.truth.map { it.kind }, gpsOnly.segments.map { it.kind })
    }

    @Test fun tooFewFixesGiveNoSegments() {
        assertTrue(SkiDaySplitter.split(emptyList()).segments.isEmpty())
        assertTrue(SkiDaySplitter.split(day.fixes.take(1)).segments.isEmpty())
        val two = SkiDaySplitter.split(day.fixes.take(2))
        assertEquals(1, two.segments.size)
        assertEquals(SegmentKind.REST, two.segments[0].kind)
    }

    @Test fun aShortPolePushUphillDoesNotMakeALift() {
        val groups = listOf(
            listOf(SyntheticSkiDay.Phase(SegmentKind.REST, 90, 0.0, 0.0)),
            listOf(SyntheticSkiDay.Phase(SegmentKind.DESCENT, 120, 10.0, -2.0, turns = true),
                SyntheticSkiDay.Phase(SegmentKind.DESCENT, 25, 2.5, 0.8),
                SyntheticSkiDay.Phase(SegmentKind.DESCENT, 120, 10.0, -2.0, turns = true)),
            listOf(SyntheticSkiDay.Phase(SegmentKind.REST, 90, 0.0, 0.0)))
        val d = SyntheticSkiDay.build(groups)
        val split = SkiDaySplitter.split(d.fixes)
        assertEquals(describe(split), listOf(SegmentKind.REST, SegmentKind.DESCENT, SegmentKind.REST), split.segments.map { it.kind })
    }

    @Test fun aDescentStraightIntoALiftWithNoQueueSplitsOnVerticalSpeed() {
        val groups = listOf(
            listOf(SyntheticSkiDay.Phase(SegmentKind.REST, 90, 0.0, 0.0)),
            listOf(SyntheticSkiDay.Phase(SegmentKind.DESCENT, 200, 10.0, -2.0, turns = true)),
            listOf(SyntheticSkiDay.Phase(SegmentKind.LIFT, 300, 2.5, 1.2, headingDeg = 0.0)),
            listOf(SyntheticSkiDay.Phase(SegmentKind.REST, 90, 0.0, 0.0)))
        for (seed in 1L..5L) {
            val d = SyntheticSkiDay.build(groups, seed = seed)
            assertMatchesTruth(SkiDaySplitter.split(d.fixes), d.truth, boundaryS = 20.0, verticalFraction = 0.12)
        }
    }

    @Test fun aSignalGapInsideALiftIsBridgedAndNoted() {
        val lift1 = day.truth[3]
        // Drop two minutes of fixes in the middle of lift 1 (a tunnel or a gondola cabin).
        val mid = (lift1.startMs + lift1.endMs) / 2
        val fixes = day.fixes.filter { it.timeMs < mid - 30_000 || it.timeMs > mid + 90_000 }
        val split = SkiDaySplitter.split(fixes)
        assertEquals(describe(split), day.truth.map { it.kind }, split.segments.map { it.kind })
        val lift = split.segments.filter { it.kind == SegmentKind.LIFT }[0]
        assertTrue(lift.notes.any { it.contains("without signal") })
    }

    @Test fun everyConfigFieldIsExported() {
        val fields = SkiDayConfig::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(fields.size, SkiDayConfig().asMap().size)
    }

    companion object {
        /** A map whose pistes and lifts follow the day's truth centre lines. */
        fun mapFrom(d: SyntheticSkiDay.Day): ResortMap = ResortMap(d.truth.zip(d.lines).withIndex()
            .filter { it.value.first.kind != SegmentKind.REST }
            .map { (i, p) ->
                val kind = if (p.first.kind == SegmentKind.LIFT) WayKind.LIFT else WayKind.PISTE
                ResortWay(1000L + i, kind, "${kind.name.lowercase()} $i", null,
                    if (kind == WayKind.PISTE) "intermediate" else null, if (kind == WayKind.LIFT) "chair_lift" else null, p.second)
            })
    }
}
