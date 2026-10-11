package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HeadingTurnAnalysisTest {
    private fun point(t: Double, gx: Double = 0.0, gy: Double = 0.0, gz: Double = 0.0, az: Float = 9.81f) =
        TimelinePoint("L", t, SensorSample(0, t.toLong(), 0f, 0f, az, gx.toFloat(), gy.toFloat(), gz.toFloat()), "live")

    private val rest = (0..100).map { point(it * 20.0) }
    private fun calibration() = BootOrientation.calibrate(rest, "L", 0.0, 0, 1)!!

    @Test fun commandedHeadingRateIsRecoveredWhileLevel() {
        val spin = (1..100).map { point(2000 + it * 20.0, gz = 0.5, az = 12f) }
        val rolls = BootOrientation.estimate(rest + spin, calibration())
        assertTrue(rolls.size > 50)
        rolls.filter { it.timeMs > 2000 }.forEach { assertEquals(0.5, it.headingRateRadps, 1e-6) }
    }

    @Test fun commandedHeadingRateIsRecoveredWhenTheBootIsLeaned() {
        // Lean 30 degrees about the forward (x) axis: roll 30 puts the vertical at (0, sin30, cos30) in the sensor frame.
        val lean = (1..50).map { point(2000 + it * 20.0, gx = PI / 6, az = 12f) }
        val g = Vector3(0.0, sin(PI / 6), cos(PI / 6))
        val rate = 0.5
        val spin = (1..100).map { point(3000 + it * 20.0, gx = rate * g.x, gy = rate * g.y, gz = rate * g.z, az = 12f) }
        val rolls = BootOrientation.estimate(rest + lean + spin, calibration())
        val leaned = rolls.filter { it.timeMs > 3000 }
        assertTrue(leaned.size > 50)
        leaned.forEach {
            assertEquals(30.0, it.degrees, 0.05)
            assertEquals(rate, it.headingRateRadps, 1e-5)
        }
    }

    @Test fun alternatingRotationGivesAlternatingTurns() {
        val spin = (1..400).map { i ->
            val t = 2000 + i * 20.0
            point(t, gz = sin(2 * PI * (t - 2000) / 2000.0) * 1.0, az = 12f)
        }
        val turns = HeadingTurnAnalysis.detect(BootOrientation.estimate(rest + spin, calibration()))
        assertTrue("got ${turns.size}", turns.size >= 3)
        turns.zipWithNext().forEach { (a, b) ->
            assertNotEquals(a.positive, b.positive)
            assertTrue(b.startMs >= a.startMs)
        }
    }

    @Test fun aSegmentChangeDoesNotEmitATurnSpanningIt() {
        fun roll(t: Double, rate: Double, segment: Int) = BootRoll("L", t, 0.0, 0.0, segment = segment, headingRateRadps = rate)
        val rolls = ArrayList<BootRoll>()
        var t = 0.0
        repeat(50) { rolls += roll(t, -1.0, 0); t += 20.0 }   // 1.0 s negative
        repeat(75) { rolls += roll(t, 1.0, 0); t += 20.0 }    // 1.5 s positive, then the segment changes
        val boundary = t
        repeat(50) { rolls += roll(t, 1.0, 1); t += 20.0 }    // positive continues in the next segment
        repeat(75) { rolls += roll(t, -1.0, 1); t += 20.0 }
        repeat(50) { rolls += roll(t, 1.0, 1); t += 20.0 }
        val turns = HeadingTurnAnalysis.detect(rolls)
        assertTrue(turns.isNotEmpty())
        assertTrue(turns.none { it.startMs < boundary && it.endMs > boundary })
    }

    @Test fun summaryReportsCountMedianDurationAndCadence() {
        val turns = listOf(
            HeadingTurn(0.0, 1000.0, true, 30.0, 20.0, false, false),
            HeadingTurn(1000.0, 2500.0, false, -30.0, -20.0, false, false),
            HeadingTurn(2500.0, 3500.0, true, 30.0, 20.0, false, false))
        val s = HeadingTurnAnalysis.summarise(turns)
        assertEquals(3, s.count)
        assertEquals(1000.0, s.medianDurationMs!!, 1e-9)
        assertEquals(60000.0 / 1250.0, s.cadencePerMinute!!, 1e-9)
        assertNull(HeadingTurnAnalysis.summarise(emptyList()).medianDurationMs)
    }
}
