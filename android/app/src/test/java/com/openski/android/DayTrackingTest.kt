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
        // Standing at the top: the slower interval comes once the 30 s mean has been still for two minutes.
        val stopStart = t
        while (!p.stationary) { p.onFix(fix(t, -6.0, 0.2)); t += 1000 }
        val slowedAfter = t - 1000 - stopStart
        assertTrue("slowed after $slowedAfter ms", slowedAfter >= DayTrackingConfig.STILL_AFTER_MS &&
            slowedAfter <= DayTrackingConfig.STILL_AFTER_MS + DayTrackingConfig.STILL_WINDOW_MS)
        t += 4000
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, -6.0, 1.0)))   // under the move threshold
        t += 5000
        assertEquals(1_000L, p.onFix(fix(t, -6.0, 2.5)))   // the lift starts
    }

    @Test fun aQueueCreepingForwardSlowsDownAndStaysSlowed() {
        // 0.3 m/s for ten minutes, no reported speed (chord speeds over 5 s), with a centimetre of jitter.
        val p = CadencePlanner()
        var t = 0L; var y = 0.0
        val intervals = ArrayList<Pair<Long, Long>>()
        while (t <= 600_000L) {
            intervals.add(t to p.onFix(fix(t, y + (if ((t / 1000) % 2 == 0L) 0.01 else -0.01))))
            val step = p.intervalMs
            t += step; y -= 0.3 * step / 1000.0
        }
        val firstSlow = intervals.first { it.second == DayTrackingConfig.STATIONARY_INTERVAL_MS }.first
        assertTrue("slowed at $firstSlow", firstSlow <= DayTrackingConfig.STILL_AFTER_MS + DayTrackingConfig.STILL_WINDOW_MS + 10_000)
        // It stays slowed until it has crept the exit radius (150 m at 0.3 m/s is 500 s).
        val back = intervals.firstOrNull { it.first > firstSlow && it.second == 1_000L }?.first
        assertTrue("back to 1 s at $back", back == null || back - firstSlow >= (DayTrackingConfig.STATIONARY_EXIT_RADIUS_M / 0.3 * 1000).toLong() - 10_000)
    }

    @Test fun driftingFarCountsAsMovingAndABriskWalkNeverSlows() {
        val p = CadencePlanner()
        var t = 0L
        while (!p.stationary) { p.onFix(fix(t, 0.0, 0.1)); t += 1000 }
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, 100.0, 0.1)))   // inside the exit radius
        t += 5000
        assertEquals(1_000L, p.onFix(fix(t, 200.0, 0.1)))   // beyond it, whatever the speed says
        // A walk at 1 m/s (over the still threshold) never slows; a 0.5 m/s stroll does, which the splitter reads as rest anyway.
        val walk = CadencePlanner()
        var y = 0.0; t = 0
        repeat(400) { assertEquals(1_000L, walk.onFix(fix(t, y, 1.0))); y += 1.0; t += 1000 }
    }

    @Test fun poorFixesAndBackwardTimesDoNotSteerTheInterval() {
        val p = CadencePlanner()
        var t = 0L
        while (!p.stationary) { p.onFix(fix(t, 0.0, 0.1)); t += 1000 }
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t, 500.0, 30.0, accuracy = 60f)))   // inaccurate jump
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t - 10_000, 0.0, 30.0)))           // out of order
        assertEquals(DayTrackingConfig.STATIONARY_INTERVAL_MS, p.onFix(fix(t + 1000, Double.NaN, 30.0)))
    }

    @Test fun withoutReportedSpeedTheChordBetweenFixesIsUsed() {
        val p = CadencePlanner()
        var t = 0L
        // The first fix has no speed at all; the chord speeds of a phone lying still are zero.
        while (!p.stationary && t <= DayTrackingConfig.STILL_AFTER_MS + 10_000) { p.onFix(fix(t, 0.0)); t += 1000 }
        assertTrue(p.stationary)
        t += 4000
        assertEquals(1_000L, p.onFix(fix(t, 20.0)))   // 20 m in 5 s = 4 m/s, inside the radius but fast
    }

    @Test fun aSyntheticDaySlowsOnlyWhileStill() {
        // FULL_DAY plus a five-minute lunch at the end: every lift and descent fix sees the one-second interval, and
        // the lunch slows down.
        val groups = SyntheticSkiDay.FULL_DAY + listOf(listOf(SyntheticSkiDay.Phase(SegmentKind.REST, 300, 0.0, 0.0)))
        val day = SyntheticSkiDay.build(groups, reportSpeed = true)
        val p = CadencePlanner()
        val intervals = day.fixes.map { p.onFix(it) }
        day.truth.filter { it.kind != SegmentKind.REST }.forEach { seg ->
            day.fixes.indices.filter { day.fixes[it].timeMs in (seg.startMs + 5_000)..seg.endMs }.forEach { i ->
                assertEquals("fix at ${(day.fixes[i].timeMs - SyntheticSkiDay.T0_MS) / 1000} s", 1_000L, intervals[i])
            }
        }
        val lunch = day.truth.last()
        assertTrue(day.fixes.indices.any { day.fixes[it].timeMs >= lunch.startMs && intervals[it] == DayTrackingConfig.STATIONARY_INTERVAL_MS })
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

    /** A writer that fails while [failing] is true and records what it stored, chunk by chunk. */
    private class Writer(var failing: Boolean = false) {
        val stored = ArrayList<DayFix>()
        val chunks = ArrayList<Int>()
        var finished = 0
        fun write(rows: List<DayFix>) { if (failing) throw IllegalStateException("database is locked"); chunks.add(rows.size); stored.addAll(rows) }
        fun finish() { finished++ }
    }

    @Test fun theBacklogWritesInChunksAndBacksOffAfterAFailure() {
        val log = FixBacklog(cap = 100, minBackoffMs = 5_000, maxBackoffMs = 20_000, chunkRows = 4)
        val w = Writer(failing = true)
        log.add((1..10).map(::row))
        assertTrue(!log.flush(0, w::write))
        assertEquals(1, log.failures); assertEquals("database is locked", log.lastError); assertEquals(5_000L, log.backoff)
        assertTrue(!log.due(4_999)); assertTrue(log.due(5_000))
        assertTrue(!log.flush(1_000, w::write))   // inside the backoff: not even tried
        assertEquals(1, log.failures)
        assertTrue(!log.flush(5_000, w::write)); assertEquals(10_000L, log.backoff)
        assertTrue(!log.flush(15_000, w::write)); assertEquals(20_000L, log.backoff)
        assertTrue(!log.flush(35_000, w::write)); assertEquals(20_000L, log.backoff)   // capped
        w.failing = false
        log.add(listOf(row(11)))
        assertTrue(log.flush(55_000, w::write))
        assertEquals((1..11).map(::row), w.stored)   // in order, each row once
        assertEquals(listOf(4, 4, 3), w.chunks)
        assertEquals(0, log.failures); assertNull(log.lastError); assertEquals(0, log.size)
    }

    @Test fun aFullBacklogDropsTheOldestAndCountsThem() {
        val log = FixBacklog(cap = 5, chunkRows = 2)
        log.add((1..4).map(::row)); log.add((5..8).map(::row))
        assertEquals(5, log.size); assertEquals(3, log.dropped)
        val w = Writer()
        assertTrue(log.flush(0, w::write))
        assertEquals((4..8).map(::row), w.stored)
    }

    @Test fun stoppingWithAFailingWriterKeepsTheDayOpen() {
        val log = FixBacklog(chunkRows = 2)
        log.add((1..3).map(::row))
        val w = Writer(failing = true)
        val pauses = ArrayList<Int>()
        val outcome = DayCloser.close(log, w::write, w::finish, attempts = 3, pause = { pauses.add(it) })
        assertTrue(outcome is CloseOutcome.KeptOpen)
        assertEquals(3, (outcome as CloseOutcome.KeptOpen).unwritten)
        assertTrue(outcome.reason, outcome.reason.startsWith("could not save the last 3 fixes (database is locked)"))
        assertEquals(listOf(1, 2), pauses)   // three attempts, pausing between them
        assertEquals(0, w.finished)          // never closed with fixes unsaved
        // Once the writer recovers, the same backlog closes the day with every fix stored.
        w.failing = false
        assertEquals(CloseOutcome.Closed, DayCloser.close(log, w::write, w::finish))
        assertEquals((1..3).map(::row), w.stored); assertEquals(1, w.finished)
    }

    @Test fun stoppingSucceedsOnARetryAndReportsAFailedFinish() {
        val log = FixBacklog(); log.add(listOf(row(1)))
        var calls = 0
        val flaky = { rows: List<DayFix> -> calls++; if (calls == 1) throw IllegalStateException("busy") else Unit }
        var finished = false
        assertEquals(CloseOutcome.Closed, DayCloser.close(log, flaky, { finished = true }))
        assertTrue(finished); assertEquals(2, calls)
        val empty = DayCloser.close(FixBacklog(), { }, { throw IllegalStateException("disk full") })
        assertEquals(CloseOutcome.KeptOpen("could not mark the day as ended (disk full); the day stays open", 0), empty)
    }

    @Test fun onlyAFreshLastPositionIsUsedAroundMe() {
        assertTrue(PositionAge.fresh(0)); assertTrue(PositionAge.fresh(PositionAge.MAX_AGE_MS))
        assertTrue(!PositionAge.fresh(PositionAge.MAX_AGE_MS + 1)); assertTrue(!PositionAge.fresh(-1))
        assertEquals(5 * 60 * 1000L, PositionAge.MAX_AGE_MS)
    }

    @Test fun anOrphanedOpenSessionStopsBlockingDownloads() {
        val now = 10_000_000L
        assertTrue(RecordingGuard.blocks(true, true, null, now))
        assertTrue(!RecordingGuard.blocks(true, false, now, now))   // the running service is the authority
        assertTrue(!RecordingGuard.blocks(false, false, null, now))
        assertTrue(RecordingGuard.blocks(false, false, now - 60_000, now))
        assertTrue(!RecordingGuard.blocks(false, false, now - RecordingGuard.STALE_RECORDING_MS - 1, now))
    }

    @Test fun theConfigIsConsistent() {
        assertTrue(DayTrackingConfig.MOVE_MPS > DayTrackingConfig.STILL_MPS)
        assertTrue(DayTrackingConfig.STATIONARY_INTERVAL_MS > DayTrackingConfig.MOVING_INTERVAL_MS)
        // The slow interval must stay far inside the splitter's hard signal gap.
        assertTrue(DayTrackingConfig.STATIONARY_INTERVAL_MS / 1000.0 < SkiDayConfig().maxSignalGapS / 10)
        assertEquals(RunTrack.MAX_ACCURACY_M, DayTrackingConfig.CADENCE_MAX_ACCURACY_M, 0f)
    }
}
