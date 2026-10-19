package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTrackingTest {
    // Barometer --------------------------------------------------------------------------------------------------

    @Test fun pressureConvertsWithTheStandardAtmosphere() {
        assertEquals(0.0, Barometer.altitudeM(1013.25)!!, 1e-9)
        // Standard atmosphere tables: 898.76 hPa at 1000 m, 794.95 hPa at 2000 m, 701.12 hPa at 3000 m.
        assertEquals(1000.0, Barometer.altitudeM(898.76)!!, 1.5)
        assertEquals(2000.0, Barometer.altitudeM(794.95)!!, 2.0)
        assertEquals(3000.0, Barometer.altitudeM(701.12)!!, 3.0)
        // Only differences matter: a different sea-level pressure shifts every altitude by nearly the same amount.
        val shift = Barometer.altitudeM(800.0, 1020.0)!! - Barometer.altitudeM(800.0)!!
        val shift2 = Barometer.altitudeM(810.0, 1020.0)!! - Barometer.altitudeM(810.0)!!
        assertEquals(shift, shift2, 2.0)
        assertTrue(Barometer.altitudeM(800.0)!! > Barometer.altitudeM(801.0)!!)
    }

    @Test fun implausiblePressuresGiveNoAltitude() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0, 299.0, 1101.0).forEach { assertNull("$it", Barometer.altitudeM(it)) }
        assertNull(Barometer.altitudeM(900.0, 0.0))
        assertNull(Barometer.altitudeM(900.0, Double.NaN))
    }

    @Test fun aFixTakesTheMeanOfRecentPressureReadings() {
        val w = BaroWindow(maxAgeMs = 5_000)
        assertNull(w.altitudeAt(1_000))
        w.add(800.0, 1_000); w.add(802.0, 2_000); w.add(Double.NaN, 2_500); w.add(10.0, 2_600)
        assertEquals(Barometer.altitudeM(801.0)!!, w.altitudeAt(3_000)!!, 1e-9)
        // Older than the window: only the 2 s reading is left at 6.5 s, none at 8 s.
        assertEquals(Barometer.altitudeM(802.0)!!, w.altitudeAt(6_500)!!, 1e-9)
        assertNull(w.altitudeAt(8_000))
        // A reading from the future (clock order) is not used.
        assertNull(BaroWindow().apply { add(900.0, 10_000) }.altitudeAt(9_000))
    }

    // Cadence -----------------------------------------------------------------------------------------------------

    private fun fix(t: Long, y: Double, speed: Double? = null, accuracy: Float = 5f) =
        GpsFix(SyntheticSkiDay.T0_MS + t, SyntheticSkiDay.lat(y), SyntheticSkiDay.lon(0.0), 2000.0, speed, accuracy, 5f)

    @Test fun twoStillMinutesSlowTheFixesAndOneFastFixRestoresThem() {
        val p = CadencePlanner()
        var t = 0L
        // Skiing.
        repeat(60) { assertEquals(1_000L, p.onFix(fix(t, -t / 100.0, 10.0))); t += 1000 }
        // Standing in a queue, shuffling at 0.3 m/s inside the still radius.
        val queueStart = t
        while (t - queueStart < DayTrackingConfig.STILL_AFTER_MS) { assertEquals("at ${t - queueStart}", 1_000L, p.onFix(fix(t, -6.0, 0.3))); t += 1000 }
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, -6.0, 0.2)))
        assertTrue(p.stationary)
        t += 5000
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, -6.0, 1.0)))   // under the move threshold
        t += 5000
        assertEquals(1_000L, p.onFix(fix(t, -6.0, 2.5)))   // the lift starts
    }

    @Test fun leavingTheStillRadiusCountsAsMovingWhateverTheSpeed() {
        val p = CadencePlanner()
        var t = 0L
        while (t <= DayTrackingConfig.STILL_AFTER_MS) { p.onFix(fix(t, 0.0, 0.1)); t += 1000 }
        assertTrue(p.stationary)
        assertEquals(1_000L, p.onFix(fix(t, 40.0, 0.1)))
        // A slow walk that keeps leaving the radius never slows down.
        val walk = CadencePlanner()
        var y = 0.0; t = 0
        repeat(400) { assertEquals(1_000L, walk.onFix(fix(t, y, 0.5))); y += 0.5; t += 1000 }
    }

    @Test fun poorFixesAndBackwardTimesDoNotSteerTheInterval() {
        val p = CadencePlanner()
        var t = 0L
        while (t <= DayTrackingConfig.STILL_AFTER_MS) { p.onFix(fix(t, 0.0, 0.1)); t += 1000 }
        assertTrue(p.stationary)
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, 500.0, 30.0, accuracy = 60f)))   // inaccurate jump
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t - 10_000, 0.0, 30.0)))           // out of order
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t + 1000, Double.NaN, 30.0)))
    }

    @Test fun withoutReportedSpeedTheChordBetweenFixesIsUsed() {
        val p = CadencePlanner()
        var t = 0L
        // The first fix has no speed at all, so stillness starts one fix later.
        while (t <= DayTrackingConfig.STILL_AFTER_MS + 1000) { p.onFix(fix(t, 0.0)); t += 1000 }
        assertTrue(p.stationary)
        t += 4000
        assertEquals(1_000L, p.onFix(fix(t, 20.0)))   // 20 m in 5 s = 4 m/s, inside the radius but fast
    }

    @Test fun aSyntheticDaySlowsOnlyInLongStops() {
        // FULL_DAY has rests of 100 to 150 s: with a 120 s threshold only the longest ones slow down, and every
        // descent and lift fix sees the one-second interval.
        val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY, reportSpeed = true)
        val p = CadencePlanner()
        val intervals = day.fixes.map { p.onFix(it) }
        day.truth.filter { it.kind != SegmentKind.REST }.forEach { seg ->
            day.fixes.indices.filter { day.fixes[it].timeMs in (seg.startMs + 5_000)..seg.endMs }.forEach { i ->
                assertEquals("fix at ${(day.fixes[i].timeMs - SyntheticSkiDay.T0_MS) / 1000} s", 1_000L, intervals[i])
            }
        }
        assertTrue(intervals.any { it == DayTrackingConfig.STATIONARY_INTERVAL_MS })
    }

    // Batching ----------------------------------------------------------------------------------------------------

    private fun row(i: Int) = DayFix(GpsFix(i.toLong(), 46.0, 7.0, null, null, 5f, null), null)

    @Test fun fixesAreWrittenInBatchesBySizeOrAge() {
        val b = FixBatcher(maxRows = 3, maxAgeMs = 10_000)
        assertNull(b.add(row(1), 0)); assertNull(b.add(row(2), 1000))
        assertEquals(listOf(row(1), row(2), row(3)), b.add(row(3), 2000))
        assertEquals(0, b.pending)
        assertNull(b.add(row(4), 20_000))
        assertEquals(listOf(row(4), row(5)), b.add(row(5), 30_000))   // the oldest waited 10 s
        assertNull(b.add(row(6), 31_000))
        assertEquals(listOf(row(6)), b.drain())
        assertTrue(b.drain().isEmpty())
    }

    @Test fun aFailedBatchIsRetriedFirst() {
        val b = FixBatcher(maxRows = 3, maxAgeMs = 60_000)
        val failed = listOf(row(1), row(2), row(3))
        b.requeue(failed, 0)
        assertEquals(3, b.pending)
        assertEquals(failed + row(4), b.add(row(4), 1000))
        b.requeue(emptyList(), 2000)
        assertEquals(0, b.pending)
    }

    @Test fun theConfigIsConsistent() {
        assertTrue(DayTrackingConfig.MOVE_MPS > DayTrackingConfig.STILL_MPS)
        assertTrue(DayTrackingConfig.STATIONARY_INTERVAL_MS > DayTrackingConfig.MOVING_INTERVAL_MS)
        // The slow interval must stay far inside the splitter's hard signal gap.
        assertTrue(DayTrackingConfig.STATIONARY_INTERVAL_MS / 1000.0 < SkiDayConfig().maxSignalGapS / 10)
        assertEquals(RunTrack.MAX_ACCURACY_M, DayTrackingConfig.CADENCE_MAX_ACCURACY_M, 0f)
    }
}
