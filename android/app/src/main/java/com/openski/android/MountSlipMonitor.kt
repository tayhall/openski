package com.openski.android

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

enum class MountSlipKind { GYRO_AXIS, MEAN_FORCE }

/** One window that departs from the run's own baseline; [timeMs] is the end of the window. */
data class MountSlipFlag(val timeMs: Double, val kind: MountSlipKind, val axisDeviationDeg: Double?, val forceShiftG: Double?)

data class MountSlipResult(
    val side: String,
    /** Time of the calibration whose frame was used; flags are reported relative to it. */
    val calibrationMs: Double,
    /** Turning windows used to set the reference; fewer than [MountSlipMonitor.BASELINE_WINDOWS] means unavailable. */
    val baselineWindows: Int,
    val windowsChecked: Int,
    /** Moving windows whose gyro axis was too far from the baseline to be the same activity (walking, a lift ride). */
    val windowsSkipped: Int,
    val flags: List<MountSlipFlag>,
    val maxAxisDeviationDeg: Double?,
    val maxForceShiftG: Double?,
    val reason: String? = null,
) {
    val available get() = baselineWindows >= MountSlipMonitor.BASELINE_WINDOWS
    val flagged get() = flags.isNotEmpty()
    val firstFlagMs get() = flags.firstOrNull()?.timeMs
    val firstFlagAfterCalibrationMs get() = firstFlagMs?.let { it - calibrationMs }
}

/**
 * Replay-side check that a boot strap or sensor moved after calibration, ported from the research
 * repo's `MountMonitor` (`docs/skivids/reference-code/imu.py`). In the calibrated boot frame, turning
 * should rotate about the forward axis, so the principal horizontal gyro axis is compared with the
 * run's own baseline (a strap that slid round the shin rotates it in the horizontal plane), and the
 * windowed mean horizontal specific force is compared with its baseline spread (a tilted mount shifts it).
 *
 * Deviations from the research code, all to stay quiet when the skier is not turning:
 * - Windows are defined in time, not samples, and the window mean gyro is removed first, so a constant
 *   residual bias (cold start, drift) does not count as motion or rotate the axis.
 * - The baseline is the first [BASELINE_WINDOWS] windows with turning motion about the forward axis
 *   (principal axis within [BASELINE_AXIS_LIMIT_DEG] of forward), not the first 12 s, so walking or a
 *   lift ride after calibration cannot set the reference.
 * - Windows are checked only if they have motion and their axis is within [ACTIVITY_AXIS_LIMIT_DEG] of
 *   the baseline. Beyond that the motion is a different activity (walking pitches about the lateral axis,
 *   90 degrees away) and the window is skipped. The cost: a yaw slip over 45 degrees is not flagged either.
 * - A flag needs two consecutive exceeding windows, because a strap slip persists and a load or terrain
 *   transient does not. This delays a flag by one step (2 s).
 * - Only the latest calibration of a side is used, from its time to the end, so earlier frames never
 *   meet later samples.
 *
 * The research simulation flagged 8 degrees of yaw slip 6 of 6 times (about 9 s late), but only
 * 2 to 3 of 6 for 15 degrees of tilt slip: it is a prompt to re-calibrate, not proof that the mount
 * is fine. Simulated signals only; unvalidated on a boot.
 */
object MountSlipMonitor {
    const val WINDOW_MS = 4000.0
    const val STEP_MS = 2000.0
    const val BASELINE_WINDOWS = 5
    const val AXIS_LIMIT_DEG = 8.0
    const val BASELINE_AXIS_LIMIT_DEG = 30.0
    const val ACTIVITY_AXIS_LIMIT_DEG = 45.0
    const val FORCE_FLOOR_G = 0.15
    /** Horizontal gyro RMS (mean removed) below this is treated as not moving. */
    const val MIN_MOTION_RADPS = 0.1
    private const val MIN_SAMPLES = 80
    private const val MAX_GAP_MS = 250.0
    private const val G = 9.80665

    private class Window(val endMs: Double, val n: Int, val meanFwdG: Double, val meanLatG: Double,
        val sxx: Double, val syy: Double, val sxy: Double) {
        val rms get() = sqrt((sxx + syy) / n)
        fun axisDeg() = Math.toDegrees(0.5 * atan2(2 * sxy, sxx - syy))
    }

    /** The latest calibration of each side, checked against that side's samples. */
    fun checkSides(points: List<TimelinePoint>, calibrations: List<BootCalibration>): Map<String, MountSlipResult> =
        calibrations.groupBy { it.side }.mapValues { (_, list) -> check(points, list.maxBy { it.timeMs }) }

    fun check(points: List<TimelinePoint>, calibration: BootCalibration): MountSlipResult {
        val up = calibration.gravity
        val forward = BootOrientation.forwardOf(calibration)
        val lateral = up.cross(forward)
        // Near-rail samples carry a saturated value, so they would bias both statistics.
        val ordered = points.filter { it.side == calibration.side && it.timeMs >= calibration.timeMs &&
                it.sample.quality and SensorSample.QUALITY_RAIL_MASK == 0 }.sortedBy { it.timeMs }

        // Per sample: forward and lateral gyro (bias-corrected) and forward and lateral force in g.
        val n = ordered.size
        val t = DoubleArray(n) { ordered[it].timeMs }
        val gf = DoubleArray(n); val gl = DoubleArray(n); val af = DoubleArray(n); val al = DoubleArray(n)
        for (i in 0 until n) {
            val s = ordered[i].sample
            val accel = Vector3(s.accelX.toDouble(), s.accelY.toDouble(), s.accelZ.toDouble())
            val gyro = Vector3(s.gyroX.toDouble(), s.gyroY.toDouble(), s.gyroZ.toDouble()) - calibration.bias
            gf[i] = gyro.dot(forward); gl[i] = gyro.dot(lateral)
            af[i] = accel.dot(forward) / G; al[i] = accel.dot(lateral) / G
        }

        val windows = ArrayList<Window>()
        if (n > 0) {
            val last = t[n - 1]
            var start = t[0]
            var lo = 0
            var hi = 0
            while (start + WINDOW_MS <= last) {
                while (lo < n && t[lo] < start) lo++
                if (hi < lo) hi = lo
                while (hi < n && t[hi] < start + WINDOW_MS) hi++
                val count = hi - lo
                // The window must be covered to both edges, not just free of holes inside it.
                val covered = count >= MIN_SAMPLES && t[lo] - start <= MAX_GAP_MS && start + WINDOW_MS - t[hi - 1] <= MAX_GAP_MS
                if (covered && (lo + 1 until hi).none { t[it] - t[it - 1] > MAX_GAP_MS }) {
                    var mf = 0.0; var ml = 0.0; var mgf = 0.0; var mgl = 0.0
                    for (i in lo until hi) { mf += af[i]; ml += al[i]; mgf += gf[i]; mgl += gl[i] }
                    mf /= count; ml /= count; mgf /= count; mgl /= count
                    var xx = 0.0; var yy = 0.0; var xy = 0.0
                    for (i in lo until hi) {
                        val x = gf[i] - mgf; val y = gl[i] - mgl
                        xx += x * x; yy += y * y; xy += x * y
                    }
                    windows.add(Window(start + WINDOW_MS, count, mf, ml, xx, yy, xy))
                }
                start += STEP_MS
            }
        }

        val moving = windows.filter { it.rms >= MIN_MOTION_RADPS }
        val baseline = moving.filter { abs(it.axisDeg()) <= BASELINE_AXIS_LIMIT_DEG }.take(BASELINE_WINDOWS)
        if (baseline.size < BASELINE_WINDOWS) {
            return MountSlipResult(calibration.side, calibration.timeMs, baseline.size, 0, 0, emptyList(), null, null,
                reason = "not enough turning motion about the forward axis after calibration")
        }

        val pooled = Window(0.0, 1, 0.0, 0.0, baseline.sumOf { it.sxx }, baseline.sumOf { it.syy }, baseline.sumOf { it.sxy })
        val refAxis = pooled.axisDeg()
        val refF = median(baseline.map { it.meanFwdG })
        val refL = median(baseline.map { it.meanLatG })
        // Few baseline windows hold a whole number of turns, so the natural spread of their mean force sets the tolerance.
        val spread = baseline.maxOf { sqrt((it.meanFwdG - refF) * (it.meanFwdG - refF) + (it.meanLatG - refL) * (it.meanLatG - refL)) }
        val tolerance = maxOf(FORCE_FLOOR_G, 2 * spread)
        val baselineEnd = baseline.last().endMs

        val flags = ArrayList<MountSlipFlag>()
        var checked = 0
        var skipped = 0
        var maxAxis = 0.0
        var maxForce = 0.0
        var previousExceeded = false
        // Windows are in time order; a skipped window breaks the run of exceedances.
        windows.filter { it.endMs > baselineEnd }.forEach { w ->
            if (w.rms < MIN_MOTION_RADPS) { previousExceeded = false; return@forEach }
            val d = w.axisDeg() - refAxis
            val axisDev = d - 180 * floor((d + 90) / 180)
            if (abs(axisDev) > ACTIVITY_AXIS_LIMIT_DEG) { skipped++; previousExceeded = false; return@forEach }
            checked++
            val forceShift = sqrt((w.meanFwdG - refF) * (w.meanFwdG - refF) + (w.meanLatG - refL) * (w.meanLatG - refL))
            maxAxis = maxOf(maxAxis, abs(axisDev))
            maxForce = maxOf(maxForce, forceShift)
            val axisOut = abs(axisDev) > AXIS_LIMIT_DEG
            val forceOut = forceShift > tolerance
            if ((axisOut || forceOut) && previousExceeded) {
                flags.add(if (axisOut) MountSlipFlag(w.endMs, MountSlipKind.GYRO_AXIS, axisDev, null)
                    else MountSlipFlag(w.endMs, MountSlipKind.MEAN_FORCE, null, forceShift))
            }
            previousExceeded = axisOut || forceOut
        }
        return MountSlipResult(calibration.side, calibration.timeMs, baseline.size, checked, skipped, flags, maxAxis, maxForce)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    /** One line for the Analysis tab; always carries the limits. */
    fun describe(result: MountSlipResult): String = when {
        !result.available -> "Mount check: not enough turning motion after calibration to set a baseline."
        !result.flagged -> "Mount check: no shift seen in ${result.windowsChecked} later windows (largest axis change " +
            "${"%.1f".format(java.util.Locale.US, result.maxAxisDeviationDeg ?: 0.0)}°). Small tilts are caught unreliably."
        else -> "Mount check: ${result.flags.size} of ${result.windowsChecked} windows moved from the baseline, first " +
            "${"%.0f".format(java.util.Locale.US, (result.firstFlagAfterCalibrationMs ?: 0.0) / 1000)} s after calibration. " +
            "The strap or sensor may have slipped; re-calibrate before trusting roll."
    }
}
