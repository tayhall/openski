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

    @Test fun headingDirectionLabelFollowsTheSignOfTheRateOnly() {
        assertEquals("LEFT", HeadingDirection.label(true))
        assertEquals("RIGHT", HeadingDirection.label(false))
    }

    @Test fun aNearRailSampleInsideALobeMarksTheTurn() {
        fun roll(t: Double, rate: Double, quality: Int = 0) = BootRoll("L", t, 0.0, 0.0, headingRateRadps = rate, quality = quality)
        fun stream(railAt: Int?, q: Int = 2): List<BootRoll> {
            val rolls = ArrayList<BootRoll>()
            var t = 0.0
            repeat(50) { rolls += roll(t, -1.0); t += 20.0 }
            repeat(75) { i -> rolls += roll(t, 1.0, if (i == railAt) q else 0); t += 20.0 }
            repeat(50) { rolls += roll(t, -1.0); t += 20.0 }
            repeat(75) { rolls += roll(t, 1.0); t += 20.0 }
            return rolls
        }
        val clean = HeadingTurnAnalysis.detect(stream(null))
        assertTrue(clean.isNotEmpty())
        assertTrue(clean.none { it.nearRail })
        listOf(2, 1).forEach { q ->
            val flagged = HeadingTurnAnalysis.detect(stream(30, q))
            assertEquals(clean.size, flagged.size)
            assertTrue("quality $q", flagged.any { it.nearRail })
        }
    }

    @Test fun impactGatedSamplesAreSkippedLikeTheFirmware() {
        fun roll(t: Double, rate: Double, gated: Boolean = false) =
            BootRoll("L", t, 0.0, 0.0, headingRateRadps = rate, gated = gated)
        // A spike lands inside a positive lobe; the firmware never feeds it to the filters.
        val spikes = setOf(60, 61, 62)
        fun stream(dropSpikes: Boolean, markGated: Boolean): List<BootRoll> {
            val rolls = ArrayList<BootRoll>()
            var t = 0.0
            repeat(50) { rolls += roll(t, -1.0); t += 20.0 }
            repeat(125) { i ->
                val spike = i in spikes
                if (!(spike && dropSpikes)) rolls += roll(t, if (spike) -14.0 else 1.0, spike && markGated)
                t += 20.0
            }
            repeat(50) { rolls += roll(t, -1.0); t += 20.0 }
            repeat(75) { rolls += roll(t, 1.0); t += 20.0 }
            return rolls
        }
        val reference = HeadingTurnAnalysis.detect(stream(dropSpikes = true, markGated = false))
        val gated = HeadingTurnAnalysis.detect(stream(dropSpikes = false, markGated = true))
        val ungated = HeadingTurnAnalysis.detect(stream(dropSpikes = false, markGated = false))
        assertTrue(reference.isNotEmpty())
        assertEquals(reference, gated.map { it.copy(skippedSamples = false) })
        assertTrue(gated.any { it.skippedSamples })
        assertNotEquals("the spike must matter when it is not gated", reference, ungated)
    }

    @Test fun impactGateMatchesFirmwareLimits() {
        assertTrue(BootOrientation.impactGated(2.6 * 9.80665, 0.0))
        assertTrue(BootOrientation.impactGated(0.2 * 9.80665, 0.0))
        assertTrue(BootOrientation.impactGated(9.80665, 12.5))
        assertFalse(BootOrientation.impactGated(9.80665, 3.0))
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
