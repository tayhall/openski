package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MountSlipMonitorTest {
    private val fs = 50.0
    private val rest = (0..100).map { TimelinePoint("L", it * 20.0, SensorSample(0, (it * 20).toLong(), 0f, 0f, 9.81f, 0f, 0f, 0f), "live") }
    private val calibration = BootOrientation.calibrate(rest, "L", 0.0, 0, 1)!!

    /** Alternating roll about the forward axis (sensor X), 2.6 s cycle; from [slipAtMs] the gyro axis is yawed and force offset. */
    private fun run(seconds: Double = 60.0, amplitude: Float = 0.8f, slipAtMs: Double = Double.MAX_VALUE,
                    yawDeg: Double = 0.0, forceY: Float = 0f): List<TimelinePoint> {
        val n = (seconds * fs).toInt()
        return (0 until n).map { i ->
            val t = 2000.0 + i * 1000.0 / fs
            val rate = amplitude * sin(2 * PI * (t / 1000.0) / 2.6).toFloat()
            val slipped = t >= slipAtMs
            val yaw = if (slipped) Math.toRadians(yawDeg) else 0.0
            val sample = SensorSample(i, t.toLong(), 0f, if (slipped) forceY else 0f, 9.81f,
                (rate * cos(yaw)).toFloat(), (rate * sin(yaw)).toFloat(), 0f)
            TimelinePoint("L", t, sample, "live")
        }
    }

    @Test fun steadyTurningSetsABaselineAndRaisesNoFlag() {
        val result = MountSlipMonitor.check(rest + run(), calibration)
        assertTrue(result.available)
        assertTrue(result.windowsChecked > 10)
        assertFalse(result.flagged)
    }

    @Test fun yawSlipOfFifteenDegreesIsFlaggedSoonAfterItHappens() {
        val result = MountSlipMonitor.check(rest + run(slipAtMs = 30_000.0, yawDeg = 15.0), calibration)
        assertTrue(result.flagged)
        val first = result.flags.first()
        assertEquals(MountSlipKind.GYRO_AXIS, first.kind)
        // The first flagged window straddles the slip, so it sees only part of the shift; later windows see all of it.
        assertTrue(abs(first.axisDeviationDeg!!) > MountSlipMonitor.AXIS_LIMIT_DEG)
        assertEquals(15.0, result.maxAxisDeviationDeg!!, 1.0)
        assertTrue("flagged at ${first.timeMs}", first.timeMs in 30_000.0..40_000.0)
        assertTrue(result.flags.none { it.timeMs < 30_000.0 })
    }

    @Test fun smallYawBelowTheLimitIsNotFlagged() {
        assertFalse(MountSlipMonitor.check(rest + run(slipAtMs = 30_000.0, yawDeg = 4.0), calibration).flagged)
    }

    @Test fun meanForceShiftIsFlaggedWhenTheAxisHoldsStill() {
        val result = MountSlipMonitor.check(rest + run(slipAtMs = 30_000.0, forceY = 2.5f), calibration)
        assertTrue(result.flagged)
        assertTrue(result.flags.all { it.kind == MountSlipKind.MEAN_FORCE })
    }

    @Test fun standingStillHasNoBaselineAndRaisesNothing() {
        val result = MountSlipMonitor.check(rest + run(amplitude = 0.02f), calibration)
        assertFalse(result.available)
        assertFalse(result.flagged)
    }

    @Test fun gapsAndRailSamplesAreExcludedRatherThanCounted() {
        val rail = run().map { it.copy(sample = it.sample.copy(quality = SensorSample.QUALITY_RAIL_MASK)) }
        assertFalse(MountSlipMonitor.check(rest + rail, calibration).available)
        val gapped = run().filter { (it.timeMs % 1000.0) < 100.0 }
        assertFalse(MountSlipMonitor.check(rest + gapped, calibration).available)
    }

    @Test fun otherBootsSamplesAreIgnored() {
        val other = run().map { it.copy(side = "R") }
        assertFalse(MountSlipMonitor.check(other, calibration).available)
    }
}
