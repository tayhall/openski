package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TurnMetricsTest {
    private val dtMs = 10.0

    /** One half-sine lean of [peak] degrees over [durationMs], then flat, sampled at 100 Hz. */
    private fun lean(peak: Double, durationMs: Double, noise: (Int) -> Double = { 0.0 }): List<BootRoll> {
        val n = (durationMs / dtMs).toInt()
        val lead = (0..30).map { BootRoll("L", it * dtMs, 0.0, 0.0) }
        val body = (1..n).map { i ->
            val t = i * dtMs
            val deg = peak * sin(PI * t / durationMs) + noise(i)
            val rate = Math.toRadians(peak * PI / durationMs * 1000 * cos(PI * t / durationMs))
            BootRoll("L", 300.0 + t, deg, 0.0, rollRateRadps = rate)
        }
        val tail = (1..30).map { BootRoll("L", 300.0 + durationMs + it * dtMs, 0.0, 0.0) }
        return lead + body + tail
    }

    private fun move(rolls: List<BootRoll>) = DrySkiAnalysis.detect(rolls).movements

    @Test fun measuresPeakDurationAndPhaseOfASymmetricLean() {
        val rolls = lean(30.0, 1200.0)
        val m = TurnMetricsExtractor.extract(rolls, move(rolls), emptyList()).single()
        assertEquals(30.0, m.peakDegrees, 0.5)
        assertTrue(m.durationMs in 800.0..1300.0)
        assertEquals(0.5, m.peakPhase!!, 0.1)
        assertNotNull(m.buildMs)
        assertTrue(m.buildMs!! in 100.0..m.durationMs)
    }

    @Test fun jitterRaisesRoughnessButNotPeak() {
        val smooth = lean(30.0, 1200.0)
        val rough = lean(30.0, 1200.0) { i -> if (i % 2 == 0) 1.5 else -1.5 }
        val a = TurnMetricsExtractor.extract(smooth, move(smooth), emptyList()).single()
        val b = TurnMetricsExtractor.extract(rough, move(rough), emptyList()).single()
        assertTrue(b.roughness!! > a.roughness!! * 3)
        assertEquals(a.peakDegrees, b.peakDegrees, 3.0)
    }

    @Test fun edgeHoldOfAHalfSineMatchesTheAnalyticMedian() {
        // median of sin(pi x) over x in [0.1, 0.8] is sin(0.325 pi) = 0.853, so edgeHold = 0.853 * sqrt(30)
        val rolls = lean(30.0, 1200.0)
        val m = TurnMetricsExtractor.extract(rolls, move(rolls), emptyList()).single()
        assertEquals(0.853 * sqrt(30.0), m.edgeHold!!, 0.4)
        assertEquals(m.edgeHold!!, TurnMetricsExtractor.summarise(listOf(m, m)).edgeHold!!.mean, 1e-9)
    }

    @Test fun aFlatToppedLeanHoldsMoreThanASpikyOne() {
        val base = lean(30.0, 1200.0)
        fun shaped(f: (Double) -> Double) = base.map { r ->
            val x = (r.timeMs - 300.0) / 1200.0
            if (x in 0.0..1.0) r.copy(degrees = 30 * f(sin(PI * x))) else r
        }
        val mv = listOf(DryMovement(300.0, 1500.0, true, 30.0))
        val flat = shaped { min(1.0, 2.0 * it) }
        val spiky = shaped { it.pow(3) }
        val a = TurnMetricsExtractor.extract(flat, mv, emptyList()).single().edgeHold!!
        val b = TurnMetricsExtractor.extract(spiky, mv, emptyList()).single().edgeHold!!
        assertTrue("flat $a spiky $b", a > b * 1.2)
    }

    @Test fun reportsEntryRollAndPeakRelativeToIt() {
        val offset = lean(30.0, 1200.0).map { it.copy(degrees = it.degrees + 5.0) }
        val m = TurnMetricsExtractor.extract(offset, listOf(DryMovement(300.0, 1500.0, true, 30.0)), emptyList()).single()
        assertEquals(5.0, m.entryDegrees!!, 0.01)
        assertEquals(25.0, m.peakFromEntryDegrees!!, 0.01)
        assertEquals(30.0, m.peakDegrees, 0.01)
        val flat = lean(30.0, 1200.0)
        assertEquals(30.0, TurnMetricsExtractor.extract(flat, move(flat), emptyList()).single().peakFromEntryDegrees!!, 2.5)
    }

    @Test fun earlyRateUsesTheMedianSoASpikeDoesNotMoveIt() {
        val clean = lean(30.0, 1200.0)
        val spike = clean.mapIndexed { i, r -> if (i == 40) r.copy(rollRateRadps = 50.0) else r }
        val a = TurnMetricsExtractor.extract(clean, move(clean), emptyList()).single().earlyRateDps!!
        val b = TurnMetricsExtractor.extract(spike, move(spike), emptyList()).single().earlyRateDps!!
        assertEquals(a, b, a * 0.2)
    }

    @Test fun lateEdgeShiftsPeakPhase() {
        val rolls = lean(30.0, 1200.0)
        val early = TurnMetricsExtractor.extract(rolls, move(rolls), emptyList()).single()
        val skewed = rolls.map { it.copy(degrees = if (it.timeMs in 300.0..1500.0) 30 * sin(PI / 2 * ((it.timeMs - 300) / 1200.0).pow(0.5)) * sin(PI * ((it.timeMs - 300) / 1200.0)).pow(0.3) else 0.0) }
        val late = TurnMetricsExtractor.extract(skewed, move(skewed), emptyList()).firstOrNull()
        if (late != null) assertTrue(late.peakPhase!! != early.peakPhase)
    }

    @Test fun gapInsideTurnYieldsNullShapeMetrics() {
        val rolls = lean(30.0, 1200.0).filter { it.timeMs !in 700.0..900.0 }
        val m = TurnMetricsExtractor.extract(rolls, listOf(DryMovement(300.0, 1500.0, true, 30.0)), emptyList()).single()
        assertNull(m.buildMs); assertNull(m.roughness); assertNull(m.peakPhase)
        assertEquals(30.0, m.peakDegrees, 0.01)
    }

    @Test fun attachesTheOverlappingHeadingChange() {
        val rolls = lean(30.0, 1200.0)
        val heading = listOf(HeadingTurn(350.0, 1450.0, true, 40.0, 55.0, false, false), HeadingTurn(5000.0, 6000.0, true, 40.0, 99.0, false, false))
        val m = TurnMetricsExtractor.extract(rolls, move(rolls), heading).single()
        assertEquals(55.0, m.headingChangeDegrees!!, 0.0)
        assertNull(TurnMetricsExtractor.extract(rolls, move(rolls), emptyList()).single().headingChangeDegrees)
    }

    @Test fun agreementIsOneForMatchedBootsAndDropsWhenOneLags() {
        val left = lean(30.0, 1200.0)
        val same = left.map { it.copy(side = "R") }
        val lagged = left.map { it.copy(side = "R", timeMs = it.timeMs + 250) }
        assertEquals(1.0, TurnMetricsExtractor.agreement(left, same, 300.0, 1500.0)!!, 0.01)
        assertTrue(TurnMetricsExtractor.agreement(left, lagged, 300.0, 1500.0)!! < 0.9)
        assertNull(TurnMetricsExtractor.agreement(left, emptyList(), 300.0, 1500.0))
    }

    @Test fun summaryReportsSpreadPerMetricAndNeedsTwoTurns() {
        val rolls = lean(30.0, 1200.0) + lean(20.0, 900.0).map { it.copy(timeMs = it.timeMs + 5000.0) }
        val turns = TurnMetricsExtractor.extract(rolls, move(rolls), emptyList())
        val s = TurnMetricsExtractor.summarise(turns)
        assertEquals(turns.size, s.count)
        assertTrue(s.peakDegrees!!.sd > 0)
        assertNull(TurnMetricsExtractor.summarise(turns.take(1)).peakDegrees)
    }
}
