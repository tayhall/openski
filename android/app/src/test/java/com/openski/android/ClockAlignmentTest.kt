package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic cases for [ClockAlignment]: each boot's timeline is its sensor clock plus an anchor (true phone time plus
 * a per-boot transport bias), so the correct shift of the right boot is known exactly: bias left minus bias right.
 */
class ClockAlignmentTest {
    private val phone0 = 1_792_000_000_000.0
    private val leftClock0 = 1_000_000L          // ms, left sensor clock at phone0
    private val rightClock0 = 1_003_700L         // ms, the right boot booted 3.7 s earlier
    private val biasL = 18.0
    private val biasR = 58.0                     // the right timeline is 40 ms late
    private val trueShift = biasL - biasR        // -40 ms
    private val offsetSec = (rightClock0 - leftClock0) / 1000.0

    private fun sample(ms: Long) = SensorSample(0, ms, 0f, 0f, 9.8f, 0f, 0f, 0f)

    /** One boot's live timeline: every 20 ms for [seconds], anchor = timeline minus sensor clock. */
    private fun boot(side: String, clock0: Long, bias: Double, seconds: Int = 100, segmentJumpMs: Double = 0.0,
                     wrapAtMs: Long? = null): List<TimelinePoint> =
        (0 until seconds * 50).map { i ->
            val clock = clock0 + i * 20L
            val phone = phone0 + i * 20.0
            val second = segmentJumpMs != 0.0 && i >= seconds * 25
            // A 32-bit clock wrap: the stored timestamp restarts, the timeline adds the wrap back.
            val raw = if (wrapAtMs != null && clock >= wrapAtMs) Math.round(clock - ClockAlignment.WRAP_MS) else clock
            val timeline = phone + bias + (if (second) segmentJumpMs else 0.0)
            TimelinePoint(side, timeline, sample(raw), "live")
        }

    private fun timeline(rightJump: Double = 0.0, rightWrapAt: Long? = null) =
        boot("L", leftClock0, biasL) + boot("R", rightClock0, biasR, segmentJumpMs = rightJump, wrapAtMs = rightWrapAt)

    private fun stompResult(offset: Double = offsetSec, driftPpm: Double = 0.0, sigma: Double = 0.001) =
        ClockAlign.Result(true, null, ClockAlign.EndFit(1005.0, offset, 0.95, sigma),
            ClockAlign.EndFit(1090.0, offset + driftPpm * 1e-6 * 85.0, 0.95, sigma),
            offset, 1005.0, driftPpm, sigma, 15.0)

    private fun report(stomp: ClockAlign.Result?, ping: Double? = null, pingUnc: Double = 0.010,
                       status: GestureSync.Status = if (stomp != null) GestureSync.Status.MEASURED else GestureSync.Status.REFUSED): GestureSync.Report {
        val dis = if (stomp != null && ping != null) (stomp.offsetSec - ping) * 1000 else null
        return GestureSync.Report(status, if (stomp == null) ClockAlign.Reject.NO_TRANSIENT else null, stomp ?: ClockAlign.Result.rejected(ClockAlign.Reject.NO_TRANSIENT),
            if (stomp == null) "no clear stomp at one end in one of the boots" else null,
            ping, if (ping != null) 1005.0 else null, if (ping != null) pingUnc else null,
            ping, if (ping != null) 1090.0 else null, dis, ping != null)
    }

    @Test fun aGoodStompAgreeingWithThePingsIsUsedAndRemovesTheTimelineBias() {
        val a = ClockAlignment.estimate(timeline(), report(stompResult(), ping = offsetSec + 0.004))
        assertEquals(ClockAlignment.Source.STOMP, a.source)
        assertTrue(a.applied)
        assertFalse(a.disagreement)
        assertEquals(trueShift, a.shiftMs!!, 1e-6)
        assertEquals(-4.0, a.stompMinusPingMs!!, 1e-6)
        // 2 sigma of 1 ms plus a little drift uncertainty.
        assertTrue("uncertainty ${a.uncertaintyMs}", a.uncertaintyMs!! in 2.0..4.0)
        val tR = phone0 + 50_000.0 + biasR
        assertEquals(phone0 + 50_000.0 + biasL, a.rightToLeft(tR), 1e-6)
        assertTrue(a.reason, a.reason.contains("agrees"))
    }

    @Test fun aStompWithoutPingsIsStillUsed() {
        val a = ClockAlignment.estimate(timeline(), report(stompResult()))
        assertEquals(ClockAlignment.Source.STOMP, a.source)
        assertEquals(trueShift, a.shiftMs!!, 1e-6)
        assertNull(a.stompMinusPingMs)
        assertTrue(a.reason.contains("no ping"))
    }

    @Test fun aRefusedStompFallsBackToThePingsWithTheirUncertainty() {
        val a = ClockAlignment.estimate(timeline(), report(null, ping = offsetSec + 0.005, pingUnc = 0.012))
        assertEquals(ClockAlignment.Source.PING, a.source)
        assertEquals(trueShift - 5.0, a.shiftMs!!, 1e-6)
        // 12 ms ping bound plus the drift error beyond the references: sqrt(12^2 + 12^2) / 85 s times the 10 s past the end.
        assertEquals(12.0 + Math.sqrt(288.0) / 85_000.0 * 10_000.0, a.uncertaintyMs!!, 0.05)
        assertTrue(a.reason, a.reason.contains("pings only") && a.reason.contains("no clear stomp"))
        assertEquals("refused", a.stompStatus)
    }

    @Test fun aStompAndPingDisagreementUsesThePingsFlagsItAndWidensTheUncertainty() {
        val a = ClockAlignment.estimate(timeline(), report(stompResult(), ping = offsetSec - 0.060, pingUnc = 0.010))
        assertEquals(ClockAlignment.Source.PING, a.source)
        assertTrue(a.disagreement)
        assertEquals(60.0, a.stompMinusPingMs!!, 1e-6)
        // Tolerance: ping uncertainty 10 + 2 sigma 2 + margin 5 = 17 ms.
        assertEquals(17.0, a.toleranceMs!!, 1e-6)
        assertTrue("uncertainty ${a.uncertaintyMs}", a.uncertaintyMs!! >= 60.0)
        assertEquals(trueShift + 60.0, a.shiftMs!!, 1e-6)
        assertTrue(a.reason, a.reason.startsWith("DISAGREEMENT"))
        @Suppress("UNCHECKED_CAST") val m = a.toMap()
        assertEquals(true, m["disagreement"]); assertEquals("ping", m["source"]); assertEquals(60.0, m["stomp_minus_ping_ms"] as Double, 1e-6)
    }

    @Test fun noStompAndNoPingsAppliesNothing() {
        val a = ClockAlignment.estimate(timeline(), report(null))
        assertEquals(ClockAlignment.Source.NONE, a.source)
        assertFalse(a.applied)
        assertNull(a.shiftMs); assertNull(a.uncertaintyMs)
        assertEquals(12345.0, a.rightToLeft(12345.0), 0.0)
        assertTrue(a.reason, a.reason.contains("live-clock timeline"))
        val rolls = listOf(BootRoll("R", 10.0, 1.0, 0.0))
        assertSame(rolls, ClockAlignment.alignRolls(rolls, a))
        assertEquals(false, a.toMap()["applied"])
    }

    @Test fun aRightTimelineWithTwoSegmentsIsRefusedNotGuessed() {
        val a = ClockAlignment.estimate(timeline(rightJump = 12.0), report(stompResult(), ping = offsetSec))
        assertEquals(ClockAlignment.Source.NONE, a.source)
        assertTrue(a.reason, a.reason.contains("more than one live segment"))
        assertNotNull(ClockAlignment.anchor(timeline(rightJump = 12.0), "R")!!.spreadMs.takeIf { it > 11.9 })
    }

    @Test fun aClockResetIsNotAligned() {
        val a = ClockAlignment.estimate(timeline(), report(null, status = GestureSync.Status.CLOCK_RESET))
        assertEquals(ClockAlignment.Source.NONE, a.source)
        assertTrue(a.reason.contains("clock"))
    }

    @Test fun anImplausibleShiftIsRefused() {
        val a = ClockAlignment.estimate(timeline(), report(null, ping = offsetSec + 5.0))
        assertEquals(ClockAlignment.Source.NONE, a.source)
        assertTrue(a.reason, a.reason.contains("more than 1000 ms"))
    }

    @Test fun aMissingBootAppliesNothing() {
        val a = ClockAlignment.estimate(boot("L", leftClock0, biasL), report(null, ping = offsetSec))
        assertEquals(ClockAlignment.Source.NONE, a.source)
        assertTrue(a.reason.contains("right boot has no samples"))
    }

    @Test fun aWrapInTheRightClockIsFoldedAway() {
        // The right clock wraps 30 s in; its timeline keeps counting, so the anchor must stay single-valued.
        val wrapAt = rightClock0 + 30_000L
        val tl = timeline(rightWrapAt = wrapAt)
        assertEquals(0.0, ClockAlignment.anchor(tl, "R")!!.spreadMs, 1.0)
        val a = ClockAlignment.estimate(tl, report(stompResult()))
        assertEquals(ClockAlignment.Source.STOMP, a.source)
        assertEquals(trueShift, a.shiftMs!!, 1.0)
    }

    @Test fun driftStretchesTheShiftAcrossTheSession() {
        // The right clock gains 100 ppm on the left: 100 s later the right boot reads 10 ms further ahead.
        val ppm = 100.0
        val left = boot("L", leftClock0, biasL)
        val right = (0 until 5000).map { i ->
            val phoneMs = i * 20.0
            val clock = rightClock0 + phoneMs * (1 + ppm * 1e-6)
            // The right timeline is its own clock plus one anchor, so it stretches with the clock.
            TimelinePoint("R", phone0 + biasR + (clock - rightClock0), sample(Math.round(clock)), "live")
        }
        val tl = left + right
        val a = ClockAlignment.estimate(tl, report(stompResult(offset = offsetSec + 5.0 * ppm * 1e-6, driftPpm = ppm)))
        assertEquals(ppm, a.driftPpm!!, 1e-9)
        for (phoneMs in listOf(5_000.0, 50_000.0, 95_000.0)) {
            val tR = phone0 + biasR + phoneMs * (1 + ppm * 1e-6)
            assertEquals("at $phoneMs ms", phone0 + biasL + phoneMs, a.rightToLeft(tR), 1e-3)
        }
    }

    @Test fun pingsAloneComeFromTheTimelinesWhenTheReportHasNone() {
        // A short recording: the report did not run the stomp and carries no ping offset; the ClockSyncs do.
        val syncs = listOf("L" to leftClock0, "R" to rightClock0).associate { (side, clock0) ->
            side to ClockSync(side).also { s ->
                for (k in 0 until 8) {
                    val tSend = 7_200_000_000_000L + k * 100_000_000L
                    val sensorUs = (clock0 * 1000L) + k * 100_000L + 5_000L
                    s.add(PingPair(side, k, tSend, tSend + 10_000_000L, sensorUs))
                }
            }
        }
        val tooShort = GestureSync.Report(GestureSync.Status.TOO_SHORT, null, null, "the recording is shorter than 45 s",
            null, null, null, null, null, null, false)
        val a = ClockAlignment.estimate(timeline(), tooShort, syncs)
        assertEquals(a.reason, ClockAlignment.Source.PING, a.source)
        // Both boots' sensors were read at the same phone time, so the ping offset is the clock difference.
        assertEquals(trueShift, a.shiftMs!!, 1.0)
        assertTrue(a.reason.contains("shorter than 45 s"))
    }

    @Test fun aStompWithANonFiniteSigmaIsNotUsedAndNothingNonFiniteIsReported() {
        val nanSigma = ClockAlign.Result(true, null, ClockAlign.EndFit(1005.0, offsetSec, 0.95, Double.NaN),
            ClockAlign.EndFit(1090.0, offsetSec, 0.95, Double.NaN), offsetSec, 1005.0, 0.0, Double.NaN, Double.NaN)
        val withPings = ClockAlignment.estimate(timeline(), report(nanSigma, ping = offsetSec + 0.002))
        assertEquals(ClockAlignment.Source.PING, withPings.source)
        assertTrue(withPings.reason, withPings.reason.contains("non-finite"))
        assertNull(withPings.toleranceMs)
        val alone = ClockAlignment.estimate(timeline(), report(nanSigma))
        assertEquals(ClockAlignment.Source.NONE, alone.source)
        for (a in listOf(withPings, alone)) {
            val json = TurnMetricsExport.toJson(a.toMap()).toString()
            assertFalse(json, json.contains("NaN") || json.contains("Infinity"))
        }
    }

    @Test fun aPingFallbackOnABootUpForHoursMapsInThePingFrame() {
        // Both boots have been up for hours: their live clocks sit 3 and 5 wraps below the 64-bit ping clocks.
        val syncs = listOf(Triple("L", leftClock0, 3L), Triple("R", rightClock0, 5L)).associate { (side, clock0, k) ->
            side to ClockSync(side).also { s ->
                for (i in 0 until 8) {
                    val tSend = 7_200_000_000_000L + i * 100_000_000L
                    val sensorUs = ((clock0 + k * ClockAlignment.WRAP_MS) * 1000.0).toLong() + i * 100_000L + 5_000L
                    s.add(PingPair(side, i, tSend, tSend + 10_000_000L, sensorUs))
                }
            }
        }
        val tooShort = GestureSync.Report(GestureSync.Status.TOO_SHORT, null, null, "the recording is shorter than 45 s",
            null, null, null, null, null, null, false)
        val a = ClockAlignment.estimate(timeline(), tooShort, syncs)
        assertEquals(a.reason, ClockAlignment.Source.PING, a.source)
        assertEquals(trueShift, a.shiftMs!!, 1.0)
        // Without the frame change the left lookup would be 3 wraps (3.6 hours) from every left ping and miss badly.
        assertTrue("uncertainty ${a.uncertaintyMs}", a.uncertaintyMs!! < 30.0)
        assertEquals(1000.0 + 3 * ClockAlignment.WRAP_MS / 1000.0, ClockAlignment.toPingFrame(1000.0, syncs["L"]), 1e-6)
    }

    // --- agreement pairs flag timing finer than the alignment uncertainty -------------------------------------------

    private fun lean(side: String, delayMs: Double = 0.0) = (0..200).map { i ->
        val t = i * 10.0
        val u = t - 300.0 - delayMs
        val inLean = u in 0.0..1200.0
        BootRoll(side, t, if (inLean) 30.0 * sin(PI * u / 1200.0) else 0.0, 0.0,
            rollRateRadps = if (inLean) Math.toRadians(30.0 * PI / 1.2 * cos(PI * u / 1200.0)) else 0.0)
    }

    @Test fun agreementPairsFlagTimingLimitedFiguresOnlyWhenTheUncertaintyMatters() {
        val move = DryMovement(350.0, 1450.0, true, 30.0)
        val unknown = TurnMetricsExport.agreementPairs(lean("L"), lean("R"), listOf(move), listOf(move), true, true)
        assertNull(unknown[0].timingLimited); assertNull(unknown[0].timingSensitivity)
        val tight = TurnMetricsExport.agreementPairs(lean("L"), lean("R"), listOf(move), listOf(move), true, true, 1.0)
        assertEquals(false, tight[0].timingLimited)
        assertTrue(tight[0].timingSensitivity!! < TurnMetricsExport.TIMING_SENSITIVITY_LIMIT)
        val loose = TurnMetricsExport.agreementPairs(lean("L"), lean("R"), listOf(move), listOf(move), true, true, 150.0)
        assertEquals(true, loose[0].timingLimited)
        // The base figures do not depend on the uncertainty.
        assertEquals(tight[0].derivativeSimilarity!!, loose[0].derivativeSimilarity!!, 0.0)
    }
}
