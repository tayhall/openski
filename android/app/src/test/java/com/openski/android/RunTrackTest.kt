package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class RunTrackTest {
    private val metresPerDegreeLat = 111_194.93

    /** One fix per second moving north at [speed] m/s from lat 46, lon 7, starting at [startMs]. */
    private fun line(count: Int, speed: Double, startMs: Long = 0, startLat: Double = 46.0,
                     accuracy: Float = 5f, reportSpeed: Boolean = false): List<GpsFix> =
        (0 until count).map { i ->
            GpsFix(startMs + i * 1000L, startLat + speed * i / metresPerDegreeLat, 7.0, null,
                if (reportSpeed) speed else null, accuracy, null)
        }

    @Test fun distanceAlongAMeridianIsSummedBetweenFixes() {
        val s = RunTrack.summarize(line(60, 10.0))
        assertEquals(590.0, s.distanceM, 6.0)
        assertEquals(10.0, s.topSpeedMps!!, 0.3)
        assertEquals(10.0, s.avgSpeedMps!!, 0.3)
        assertEquals(59, s.movingSeconds)
        assertEquals(60, s.usableFixes)
        assertEquals(60, s.totalFixes)
    }

    @Test fun aFixWithPoorAccuracyIsDropped() {
        val fixes = line(60, 10.0).toMutableList()
        fixes.add(30, GpsFix(29_500, 47.0, 8.0, null, null, 80f, null))
        val s = RunTrack.summarize(fixes)
        assertEquals(60, s.usableFixes)
        assertEquals(61, s.totalFixes)
        assertEquals(590.0, s.distanceM, 6.0)
    }

    @Test fun aFixWithNoReportedAccuracyIsDroppedNotTreatedAsPerfect() {
        val fix = RunTrack.fixFrom(1000, 46.0, 7.0, null, null, null, null, nowMs = 5)
        assertTrue(fix.accuracyM > RunTrack.MAX_ACCURACY_M)
        assertEquals(0, RunTrack.summarize(listOf(fix)).usableFixes)
    }

    @Test fun anImpossibleJumpIsDroppedEvenWithGoodAccuracy() {
        val fixes = line(60, 10.0).toMutableList()
        fixes[30] = fixes[30].copy(lat = 46.05)   // about 5.5 km away for one second
        val s = RunTrack.summarize(fixes)
        assertEquals(59, s.usableFixes)
        assertEquals(590.0, s.distanceM, 12.0)
    }

    @Test fun duplicateAndBackwardsTimestampsAreDroppedWithoutCrashing() {
        val fixes = line(10, 10.0).toMutableList()
        fixes.add(fixes[5].copy())                       // same time as an earlier fix
        fixes.add(5, fixes[2].copy(timeMs = 1_000))      // out of order
        val s = RunTrack.summarize(fixes)
        assertEquals(10, s.usableFixes)
        assertFalse(s.distanceM.isNaN())
    }

    @Test fun topSpeedMustBeHeldForTwoSeconds() {
        val base = line(60, 10.0, reportSpeed = true)
        val spike = base.mapIndexed { i, f -> if (i == 20) f.copy(speedMps = 30.0) else f }
        assertEquals(10.0, RunTrack.summarize(spike).topSpeedMps!!, 0.01)
        val held = base.mapIndexed { i, f -> if (i in 30..32) f.copy(speedMps = 20.0) else f }
        assertEquals(20.0, RunTrack.summarize(held).topSpeedMps!!, 0.01)
    }

    @Test fun standingStillIsNeitherDistanceNorMovingTime() {
        val still = (0 until 30).map { GpsFix(it * 1000L, 46.0, 7.0, null, 0.0, 5f, null) }
        val s = RunTrack.summarize(still)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0, s.movingSeconds)
        assertNull(s.avgSpeedMps)
    }

    @Test fun aLongGapIsNotBridged() {
        val first = line(30, 10.0)
        val second = line(30, 10.0, startMs = 89_000, startLat = 46.0 + 10.0 * 89 / metresPerDegreeLat)
        val s = RunTrack.summarize(first + second)
        assertEquals(580.0, s.distanceM, 12.0)      // 29 + 29 segments, not the 60 s hole
    }

    @Test fun oneOrTwoFixesGiveNoSpeedsAndNoCrash() {
        val one = RunTrack.summarize(line(1, 10.0))
        assertEquals(1, one.usableFixes); assertNull(one.topSpeedMps); assertNull(one.avgSpeedMps)
        val two = RunTrack.summarize(line(2, 10.0))
        assertNull(two.topSpeedMps)
        assertEquals(0, RunTrack.summarize(emptyList()).usableFixes)
    }

    @Test fun verticalDropUsesSmoothedAltitudeAndNeedsTenFixesWithAccuracy() {
        fun withAlt(count: Int, accuracy: Float?) = line(count, 10.0).mapIndexed { i, f ->
            f.copy(altM = 2000.0 - 5.0 * i, verticalAccuracyM = accuracy)
        }
        assertEquals(85.0, RunTrack.summarize(withAlt(20, 5f)).verticalDropM!!, 0.01)
        assertNull(RunTrack.summarize(withAlt(20, null)).verticalDropM)
        assertNull(RunTrack.summarize(withAlt(9, 5f)).verticalDropM)
    }

    @Test fun reportExplainsWhyThereAreNoNumbers() {
        assertEquals("No GPS in demo runs", RunTrack.report(true, false, null).headline)
        assertEquals("GPS was off for this run", RunTrack.report(false, false, null).headline)
        assertEquals("No GPS fix during this run", RunTrack.report(false, true, null).headline)
        val empty = TrackSummary(0.0, null, null, 0, null, 0, 12)
        assertEquals("No GPS fix during this run", RunTrack.report(false, true, empty).headline)
        assertTrue(RunTrack.report(true, false, null).rows.isEmpty())
    }

    @Test fun reportFormatsNumbers() {
        val s = TrackSummary(1234.0, 15.0, 8.0, 100, 210.4, 95, 100)
        val r = RunTrack.report(false, true, s)
        assertEquals("On the hill", r.headline)
        assertEquals(
            listOf("Distance" to "1.23 km", "Top speed" to "54 km/h", "Average speed" to "29 km/h",
                "Vertical drop" to "210 m", "Fixes" to "95 of 100 usable"), r.rows)
        assertEquals("Phone GPS readings, not a measure of technique.", r.note)
        val short = RunTrack.report(false, true, s.copy(distanceM = 590.4, verticalDropM = null, topSpeedMps = null, avgSpeedMps = null))
        assertEquals("590 m", short.rows.first { it.first == "Distance" }.second)
        assertEquals("not available", short.rows.first { it.first == "Vertical drop" }.second)
        assertEquals("not available", short.rows.first { it.first == "Top speed" }.second)
    }
}
