package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

class GestureSyncTest {
    private class Stomp(val at: Double, val amp: Double = 30.0)

    private fun accel(t: Double, stomps: List<Stomp>): Double {
        var v = 9.8
        for (s in stomps) {
            val u = t - s.at
            if (u >= 0) v += s.amp * (1 - exp(-u / 0.004)) * exp(-u / 0.03) * cos(2 * PI * 18 * u)
        }
        return v
    }

    private val ms = ClockSyncDefaults.MS
    private val base = 5_000_000_000_000L
    private val offL = 100.0
    private val offR = 102.3
    private val ppm = 40.0

    private fun leftClock(t: Double) = offL + t
    private fun rightClock(t: Double) = offR + t * (1 + ppm * 1e-6)

    private fun sample(seq: Int, ms: Long, a: Double) = SensorSample(seq, ms, 0f, 0f, a.toFloat(), 0f, 0f, 0f)

    private fun capture(side: String) = SensorCapture("c$side", "s", side, "addr", 0, "done", 0, 0, "")

    /** Flash at 100 Hz for [duration] seconds of true time, each boot on its own clock. */
    private fun flash(side: String, clock: (Double) -> Double, stomps: List<Stomp>, duration: Double, seed: Long): List<StoredFlash> {
        val rng = Random(seed)
        val cap = capture(side)
        return (0 until (duration * 100).toInt()).map { i ->
            val t = i / 100.0
            val us = Math.round(clock(t) * 1e6)
            val a = accel(t, stomps) + rng.nextGaussian() * 0.15
            StoredFlash(cap, i, FlashRecord(us, sample(-1, us / 1000, a), ByteArray(16)))
        }
    }

    private fun live(side: String, clock: (Double) -> Double, stomps: List<Stomp>, duration: Double, seed: Long): List<StoredLive> {
        val rng = Random(seed)
        return (0 until (duration * 50).toInt()).map { i ->
            val t = i / 50.0
            StoredLive(side, 1_000_000L + (t * 1000).toLong(), sample(i and 0xffff, Math.round(clock(t) * 1000), accel(t, stomps) + rng.nextGaussian() * 0.15))
        }
    }

    private fun pings(side: String, clock: (Double) -> Double, trueTimes: List<Double>, biasSec: Double = 0.0) =
        trueTimes.mapIndexed { i, t ->
            val mid = base + (t * 1e9).toLong()
            StoredClockPair(side, i, mid - 15 * ms, mid + 15 * ms, Math.round((clock(t) + biasSec) * 1e6), 30 * ms, true)
        }

    private val both = listOf(Stomp(5.0), Stomp(110.0))
    private val session = SkiSession("s", 1_000_000L, 1_200_000L, 0, 0, null, null)

    private fun data(
        l: List<StoredFlash>, r: List<StoredFlash>, pairs: List<StoredClockPair> = emptyList(),
        liveL: List<StoredLive> = emptyList(), liveR: List<StoredLive> = emptyList(),
    ) = SessionData(session, liveL + liveR, l + r, listOf(capture("L"), capture("R")), emptyList(), clockPairs = pairs)

    private fun expectedOffset(tLeftSensor: Double): Double {
        val t = tLeftSensor - offL
        return rightClock(t) - tLeftSensor
    }

    private val burst = (1..8).map { it.toDouble() } + (112..119).map { it.toDouble() }

    private fun standard(withPings: Boolean, rBias: Double = 0.0, stompsL: List<Stomp> = both, stompsR: List<Stomp> = both, duration: Double = 120.0): SessionData {
        val p = if (withPings) pings("L", ::leftClock, burst) + pings("R", ::rightClock, burst, rBias) else emptyList()
        return data(flash("L", ::leftClock, stompsL, duration, 1), flash("R", ::rightClock, stompsR, duration, 2), p)
    }

    @Test fun recoversOffsetAndDriftWithPings() {
        val report = GestureSync.analyse(standard(true))
        assertTrue(report.reason, report.ok)
        val r = report.result!!
        assertEquals(expectedOffset(r.refTimeSec), r.offsetSec, 0.02)
        val endRef = r.end!!.refTimeSec
        assertEquals(expectedOffset(endRef), r.offsetAt(endRef), 0.02)
        assertTrue(report.windowsUsedPings)
        assertNotNull(report.disagreementMs)
        assertTrue("ping and stomp should agree, got ${report.disagreementMs}", GestureSync.agrees(report, 20.0)!!)
    }

    @Test fun recoversWithoutPingsUsingTheDefaultWindows() {
        val report = GestureSync.analyse(standard(false))
        assertTrue(report.reason, report.ok)
        assertFalse(report.windowsUsedPings)
        assertNull(report.pingOffsetSec)
        assertNull(report.disagreementMs)
        assertEquals(expectedOffset(report.result!!.refTimeSec), report.result.offsetSec, 0.02)
        assertTrue(GestureSync.summaryLine(report).contains("no ping pairs"))
    }

    @Test fun reportsDisagreementWithABiasedPingEstimate() {
        // The right boot's ping reads 8 ms late, so the stomp offset is about 8 ms less than the ping offset.
        val report = GestureSync.analyse(standard(true, rBias = 0.008))
        assertTrue(report.reason, report.ok)
        assertEquals(-8.0, report.disagreementMs!!, 4.0)
        val line = GestureSync.summaryLine(report)
        assertTrue(line, line.startsWith("Boot clocks: stomp sync says") && line.contains("ping sync says"))
    }

    @Test fun usesLiveSamplesWhenThereIsNoFlash() {
        val d = data(emptyList(), emptyList(), emptyList(),
            live("L", ::leftClock, both, 120.0, 3), live("R", ::rightClock, both, 120.0, 4))
        val report = GestureSync.analyse(d)
        assertTrue(report.reason, report.ok)
        assertEquals(expectedOffset(report.result!!.refTimeSec), report.result.offsetSec, 0.04)
    }

    @Test fun flashWinsInsideItsSpanAndLiveFillsTheRest() {
        val l = flash("L", ::leftClock, both, 120.0, 1).filter { it.index in 200..11_000 }
        val d = data(l, emptyList(), emptyList(), live("L", ::leftClock, both, 120.0, 3), emptyList())
        val s = GestureSync.series(d, "L")!!
        val inside = s.timesSec.count { it in leftClock(2.5)..leftClock(109.9) }
        assertEquals(10_801, l.size)
        assertTrue("inside the flash span only flash should remain: $inside", inside in 10_730..10_750)
        assertTrue(s.timesSec.first() < leftClock(1.0))   // live fills the start
        assertTrue(s.timesSec.last() > leftClock(115.0))  // and the end
    }

    @Test fun unwrapsFlashTimestampsNearTheLiveClock() {
        val clock = { t: Double -> 5000.0 + t }   // 5000 s: the 32-bit microsecond counter has wrapped once
        val cap = capture("L")
        val flashL = (0 until 1000).map { i ->
            val us = Math.round(clock(i / 100.0) * 1e6) and 0xffff_ffffL
            StoredFlash(cap, i, FlashRecord(us, sample(-1, us / 1000, 9.8), ByteArray(16)))
        }
        val d = data(flashL, emptyList(), emptyList(), live("L", clock, emptyList(), 10.0, 5), emptyList())
        val s = GestureSync.series(d, "L")!!
        assertEquals(5000.0, s.timesSec.first(), 0.05)
        assertEquals(5009.99, s.timesSec.last(), 0.05)
    }

    @Test fun noStompIsRefused() {
        val report = GestureSync.analyse(standard(true, stompsL = emptyList(), stompsR = emptyList()))
        assertFalse(report.ok)
        assertEquals(GestureSync.Status.REFUSED, report.status)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, report.reject)
        val line = GestureSync.summaryLine(report)
        assertTrue(line, line.contains("could not be measured") && line.contains("Ping sync says"))
    }

    @Test fun aStompOnOneBootOnlyIsRefused() {
        val report = GestureSync.analyse(standard(true, stompsR = emptyList()))
        assertFalse(report.ok)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, report.reject)
    }

    @Test fun aMissingEndStompIsRefused() {
        val report = GestureSync.analyse(standard(false, stompsL = listOf(Stomp(5.0)), stompsR = listOf(Stomp(5.0))))
        assertFalse(report.ok)
        assertEquals(ClockAlign.Reject.NO_TRANSIENT, report.reject)
    }

    @Test fun aDoubleStompIsAmbiguous() {
        val twice = listOf(Stomp(5.0), Stomp(5.4), Stomp(110.0))
        val report = GestureSync.analyse(standard(true, stompsL = twice, stompsR = twice))
        assertFalse(report.ok)
        assertEquals(ClockAlign.Reject.AMBIGUOUS, report.reject)
        assertNotNull(report.reason)
    }

    @Test fun aTooShortSessionIsRefusedBeforeAligning() {
        val report = GestureSync.analyse(standard(true, stompsL = listOf(Stomp(5.0), Stomp(25.0)), stompsR = listOf(Stomp(5.0), Stomp(25.0)), duration = 30.0))
        assertEquals(GestureSync.Status.TOO_SHORT, report.status)
        assertNull(report.result)
        assertTrue(GestureSync.summaryLine(report).contains("shorter than"))
    }

    @Test fun aMissingBootIsReported() {
        val d = data(flash("L", ::leftClock, both, 120.0, 1), emptyList())
        val report = GestureSync.analyse(d)
        assertEquals(GestureSync.Status.MISSING_BOOT, report.status)
        assertTrue(report.reason!!.contains("left"))
        assertEquals(GestureSync.Status.MISSING_BOOT, GestureSync.analyse(data(emptyList(), emptyList())).status)
    }

    @Test fun jsonCarriesTheResultThePingComparisonAndTheNote() {
        val ok = GestureSync.toJson(GestureSync.analyse(standard(true)))
        assertEquals("measured", ok.getString("status"))
        assertTrue(ok.getBoolean("experimental"))
        assertTrue(ok.getString("note").contains("Experimental"))
        assertTrue(ok.has("drift_ppm") && ok.has("offset_s"))
        assertTrue(ok.getJSONObject("ping").getBoolean("available"))
        assertFalse(ok.isNull("stomp_minus_ping_ms"))

        val refused = GestureSync.toJson(GestureSync.analyse(standard(false, stompsL = emptyList(), stompsR = emptyList())))
        assertEquals("refused", refused.getString("status"))
        assertEquals("no_transient", refused.getString("reject"))
        assertFalse(refused.has("offset_s"))
        assertFalse(refused.getJSONObject("ping").getBoolean("available"))
    }
}
