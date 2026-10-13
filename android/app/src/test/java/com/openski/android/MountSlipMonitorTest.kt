package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MountSlipMonitorTest {
    private val rest = (0..100).map { TimelinePoint("L", it * 20.0, SensorSample(0, (it * 20).toLong(), 0f, 0f, 9.81f, 0f, 0f, 0f), "live") }
    private val calibration = BootOrientation.calibrate(rest, "L", 0.0, 0, 1)!!

    /**
     * One stretch of simulated signal. Turning rolls about the forward axis (sensor X) as a sine of [rollAmp]
     * rad/s and [periodS]; [coupling] puts that fraction of the same rate on the lateral axis (a mount that is
     * not perfectly aligned). [walkAmp] adds a separate pitch-axis oscillation (walking or a chair ride).
     * From [slipAtMs] the roll axis is yawed by [yawDeg] and [forceY] is added to the lateral accelerometer;
     * [forceUntilMs] ends a transient force.
     */
    private fun segment(
        fromMs: Double, seconds: Double, rollAmp: Float = 0.8f, periodS: Double = 2.6, coupling: Float = 0f,
        walkAmp: Float = 0f, walkPeriodS: Double = 1.8, biasX: Float = 0f, biasY: Float = 0f,
        slipAtMs: Double = Double.MAX_VALUE, yawDeg: Double = 0.0, forceY: Float = 0f,
        forceUntilMs: Double = Double.MAX_VALUE, fs: Double = 50.0, side: String = "L",
    ): List<TimelinePoint> {
        val n = (seconds * fs).toInt()
        return (0 until n).map { i ->
            val t = fromMs + i * 1000.0 / fs
            val roll = rollAmp * sin(2 * PI * (t / 1000.0) / periodS).toFloat()
            val walk = walkAmp * sin(2 * PI * (t / 1000.0) / walkPeriodS).toFloat()
            val yaw = if (t >= slipAtMs) Math.toRadians(yawDeg) else 0.0
            val force = if (t >= slipAtMs && t < forceUntilMs) forceY else 0f
            val gx = roll * cos(yaw).toFloat() + biasX
            val gy = roll * sin(yaw).toFloat() + coupling * roll + walk + biasY
            TimelinePoint(side, t, SensorSample(i, t.toLong(), 0f, force, 9.81f, gx, gy, 0f), "live")
        }
    }

    private fun check(vararg parts: List<TimelinePoint>, using: BootCalibration = calibration) =
        MountSlipMonitor.check(rest + parts.toList().flatten(), using)

    @Test fun steadyTurningSetsABaselineAndRaisesNoFlag() {
        val result = check(segment(2000.0, 60.0))
        assertTrue(result.available)
        assertTrue(result.windowsChecked > 10)
        assertFalse(result.flagged)
    }

    @Test fun shortBurstsSeparatedByLongHolesDoNotMakeAWindow() {
        // One second of samples every four: each four-second window holds about 100 samples and no internal gap
        // over 250 ms, but only a quarter of it is data, so its mean and axis are not trustworthy.
        val bursts = (0 until 20).map { segment(2000.0 + 4000.0 * it, 1.0, fs = 100.0) }
        val result = check(*bursts.toTypedArray())
        assertFalse(result.available)
        assertFalse(result.flagged)
    }

    @Test fun yawSlipOfFifteenDegreesIsFlaggedSoonAfterItHappens() {
        val result = check(segment(2000.0, 60.0, slipAtMs = 30_000.0, yawDeg = 15.0))
        assertTrue(result.flagged)
        val first = result.flags.first()
        assertEquals(MountSlipKind.GYRO_AXIS, first.kind)
        // A flag needs two exceeding windows in a row, so it comes at most two steps after a window sees the slip.
        assertTrue(abs(first.axisDeviationDeg!!) > MountSlipMonitor.AXIS_LIMIT_DEG)
        assertEquals(15.0, result.maxAxisDeviationDeg!!, 1.0)
        assertTrue("flagged at ${first.timeMs}", first.timeMs in 30_000.0..42_000.0)
        assertTrue(result.flags.none { it.timeMs < 30_000.0 })
    }

    @Test fun smallYawBelowTheLimitIsNotFlagged() {
        assertFalse(check(segment(2000.0, 60.0, slipAtMs = 30_000.0, yawDeg = 4.0)).flagged)
    }

    @Test fun sustainedMeanForceShiftIsFlaggedWhenTheAxisHoldsStill() {
        val result = check(segment(2000.0, 60.0, slipAtMs = 30_000.0, forceY = 2.5f))
        assertTrue(result.flagged)
        assertTrue(result.flags.all { it.kind == MountSlipKind.MEAN_FORCE })
    }

    @Test fun aBriefLoadTransientIsNotFlagged() {
        // 3 s of extra lateral force pushes one 4 s window over the limit, and the next two back under it.
        val result = check(segment(2000.0, 60.0, slipAtMs = 30_000.0, forceY = 2.5f, forceUntilMs = 33_000.0))
        assertTrue(result.available)
        assertFalse(result.flagged)
        assertTrue("transient did exceed once", result.maxForceShiftG!! > MountSlipMonitor.FORCE_FLOOR_G)
    }

    @Test fun amplitudeAndCadenceChangesAreNotSlip() {
        val result = check(segment(2000.0, 30.0, rollAmp = 0.8f, periodS = 2.6),
            segment(32_000.0, 30.0, rollAmp = 1.6f, periodS = 1.3),
            segment(62_000.0, 20.0, rollAmp = 0.3f, periodS = 4.0))
        assertTrue(result.available)
        assertFalse(result.flagged)
    }

    @Test fun aSteadyMountCouplingBetweenAxesIsNotSlip() {
        val result = check(segment(2000.0, 60.0, coupling = 0.3f))
        assertTrue(result.available)
        assertFalse(result.flagged)
        assertEquals(0.0, result.maxAxisDeviationDeg!!, 1.0)
    }

    @Test fun walkingBeforeSkiingDoesNotSetTheBaseline() {
        val result = check(segment(2000.0, 20.0, rollAmp = 0f, walkAmp = 1.2f),
            segment(22_000.0, 60.0))
        assertTrue(result.available)
        assertFalse(result.flagged)
        assertEquals(0.0, result.maxAxisDeviationDeg!!, 3.0)
    }

    @Test fun aLiftRideAfterTurningIsSkippedNotFlagged() {
        val result = check(segment(2000.0, 30.0), segment(32_000.0, 30.0, rollAmp = 0f, walkAmp = 1.2f))
        assertTrue(result.available)
        assertFalse(result.flagged)
        assertTrue("other-activity windows skipped: ${result.windowsSkipped}", result.windowsSkipped > 5)
    }

    @Test fun yawSlipIsStillFlaggedAfterAWalkingGap() {
        val result = check(segment(2000.0, 30.0),
            segment(32_000.0, 12.0, rollAmp = 0f, walkAmp = 1.2f),
            segment(44_000.0, 40.0, slipAtMs = 44_000.0, yawDeg = 20.0))
        assertTrue(result.flagged)
        assertEquals(MountSlipKind.GYRO_AXIS, result.flags.first().kind)
    }

    @Test fun residualGyroBiasOnAStillBootIsNotMotion() {
        val still = check(segment(2000.0, 60.0, rollAmp = 0f, biasX = 0.15f, biasY = 0.12f))
        assertFalse(still.available)
        assertFalse(still.flagged)
    }

    @Test fun residualGyroBiasDuringTurningDoesNotMoveTheAxis() {
        val result = check(segment(2000.0, 60.0, biasX = 0.1f, biasY = 0.15f))
        assertTrue(result.available)
        assertFalse(result.flagged)
    }

    @Test fun onlyTheLatestCalibrationOfASideIsUsed() {
        // The sensor sits 15 degrees yawed for the whole run. Against the first calibration's frame that is a
        // permanent offset, but the baseline is taken from the run itself, so nothing is flagged; a second
        // calibration aligned to the slipped sensor must be the one reported.
        val yaw = Math.toRadians(15.0)
        val second = calibration.copy(timeMs = 2500.0, forward = Vector3(cos(yaw), sin(yaw), 0.0))
        val points = rest + segment(2000.0, 60.0, slipAtMs = 0.0, yawDeg = 15.0)
        val sides = MountSlipMonitor.checkSides(points, listOf(second, calibration))
        assertEquals(second.timeMs, sides.getValue("L").calibrationMs, 0.0)
        assertTrue(sides.getValue("L").available)
        assertFalse(sides.getValue("L").flagged)
        assertEquals(0.0, sides.getValue("L").maxAxisDeviationDeg!!, 1.0)
        // A slip after the first calibration is still seen when that is the frame in use.
        val late = rest + segment(2000.0, 60.0, slipAtMs = 30_000.0, yawDeg = 15.0)
        assertTrue(MountSlipMonitor.checkSides(late, listOf(calibration)).getValue("L").flagged)
    }

    @Test fun otherBootsRailSamplesAndGapsAreExcludedRatherThanCounted() {
        assertFalse(check(segment(2000.0, 60.0, side = "R")).available)
        val rail = segment(2000.0, 60.0).map { it.copy(sample = it.sample.copy(quality = SensorSample.QUALITY_RAIL_MASK)) }
        assertFalse(check(rail).available)
        val gapped = segment(2000.0, 60.0).filter { (it.timeMs % 1000.0) < 100.0 }
        assertFalse(check(gapped).available)
    }

    @Test fun descriptionsStateTheLimitsAndTimeAfterCalibration() {
        val flagged = check(segment(2000.0, 60.0, slipAtMs = 30_000.0, yawDeg = 20.0))
        val text = MountSlipMonitor.describe(flagged)
        assertTrue(text, "after calibration" in text)
        assertTrue(text, "re-calibrate" in text)
        assertTrue("not enough turning motion" in MountSlipMonitor.describe(check(segment(2000.0, 5.0))))
        assertTrue("Small tilts" in MountSlipMonitor.describe(check(segment(2000.0, 60.0))))
    }

    @Test fun aTenMinuteRecordingAt100HzIsCheckedQuickly() {
        val long = segment(2000.0, 600.0, fs = 100.0, slipAtMs = 300_000.0, yawDeg = 15.0)
        val started = System.nanoTime()
        val result = check(long)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.flagged)
        assertTrue("took $elapsedMs ms", elapsedMs < 3000)
    }
}
