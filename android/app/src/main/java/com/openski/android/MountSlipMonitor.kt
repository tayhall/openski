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
    /** Moving windows used to set the reference; fewer than [MountSlipMonitor.BASELINE_WINDOWS] means unavailable. */
    val baselineWindows: Int,
    val windowsChecked: Int,
    val flags: List<MountSlipFlag>,
    val maxAxisDeviationDeg: Double?,
    val maxForceShiftG: Double?,
) {
    val available get() = baselineWindows >= MountSlipMonitor.BASELINE_WINDOWS
    val flagged get() = flags.isNotEmpty()
    val firstFlagMs get() = flags.firstOrNull()?.timeMs
}

/**
 * Replay-side check that a boot strap or sensor moved after calibration, ported from the research
 * repo's `MountMonitor` (`docs/skivids/reference-code/imu.py`). In the calibrated boot frame, turning
 * should rotate about the forward axis, so the principal horizontal gyro axis is compared with the
 * run's own baseline (a strap that slid round the shin rotates it in the horizontal plane), and the
 * windowed mean horizontal specific force is compared with its baseline spread (a tilted mount shifts it).
 *
 * Deviations from the research code, all to stay quiet when the skier is not moving: windows are
 * defined in time, not samples; the baseline is the first [BASELINE_WINDOWS] windows with real
 * horizontal gyro motion, not the first 12 s; windows without that motion are not checked, because a
 * still or held-lean window has no meaningful axis or mean force.
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
    const val FORCE_FLOOR_G = 0.10
    /** Horizontal gyro RMS below this is treated as not moving. */
    const val MIN_MOTION_RADPS = 0.1
    private const val MIN_SAMPLES = 80
    private const val MAX_GAP_MS = 250.0
    private const val G = 9.80665

    private class Window(val endMs: Double, val n: Int, val meanFwdG: Double, val meanLatG: Double,
        val sxx: Double, val syy: Double, val sxy: Double) {
        val rms get() = sqrt((sxx + syy) / n)
        fun axisDeg() = Math.toDegrees(0.5 * atan2(2 * sxy, sxx - syy))
    }

    fun check(points: List<TimelinePoint>, calibration: BootCalibration): MountSlipResult {
        val up = calibration.gravity
        val forward = BootOrientation.forwardOf(calibration)
        val lateral = up.cross(forward)
        // Near-rail samples carry a saturated value, so they would bias both statistics.
        val ordered = points.filter { it.side == calibration.side && it.timeMs >= calibration.timeMs &&
                it.sample.quality and SensorSample.QUALITY_RAIL_MASK == 0 }.sortedBy { it.timeMs }
        val windows = ArrayList<Window>()
        if (ordered.isNotEmpty()) {
            val last = ordered.last().timeMs
            var start = ordered.first().timeMs
            while (start + WINDOW_MS <= last) {
                val inside = ordered.filter { it.timeMs >= start && it.timeMs < start + WINDOW_MS }
                if (inside.size >= MIN_SAMPLES && inside.zipWithNext().none { (a, b) -> b.timeMs - a.timeMs > MAX_GAP_MS }) {
                    var f = 0.0; var l = 0.0; var xx = 0.0; var yy = 0.0; var xy = 0.0
                    inside.forEach { p ->
                        val s = p.sample
                        val accel = Vector3(s.accelX.toDouble(), s.accelY.toDouble(), s.accelZ.toDouble())
                        val gyro = Vector3(s.gyroX.toDouble(), s.gyroY.toDouble(), s.gyroZ.toDouble()) - calibration.bias
                        f += accel.dot(forward) / G; l += accel.dot(lateral) / G
                        val gf = gyro.dot(forward); val gl = gyro.dot(lateral)
                        xx += gf * gf; yy += gl * gl; xy += gf * gl
                    }
                    windows.add(Window(start + WINDOW_MS, inside.size, f / inside.size, l / inside.size, xx, yy, xy))
                }
                start += STEP_MS
            }
        }
        val moving = windows.filter { it.rms >= MIN_MOTION_RADPS }
        val baseline = moving.take(BASELINE_WINDOWS)
        if (baseline.size < BASELINE_WINDOWS) return MountSlipResult(calibration.side, baseline.size, 0, emptyList(), null, null)

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
        var maxAxis = 0.0
        var maxForce = 0.0
        moving.filter { it.endMs > baselineEnd }.forEach { w ->
            checked++
            val d = w.axisDeg() - refAxis
            val axisDev = d - 180 * floor((d + 90) / 180)
            val forceShift = sqrt((w.meanFwdG - refF) * (w.meanFwdG - refF) + (w.meanLatG - refL) * (w.meanLatG - refL))
            maxAxis = maxOf(maxAxis, abs(axisDev))
            maxForce = maxOf(maxForce, forceShift)
            if (abs(axisDev) > AXIS_LIMIT_DEG) flags.add(MountSlipFlag(w.endMs, MountSlipKind.GYRO_AXIS, axisDev, null))
            else if (forceShift > tolerance) flags.add(MountSlipFlag(w.endMs, MountSlipKind.MEAN_FORCE, null, forceShift))
        }
        return MountSlipResult(calibration.side, baseline.size, checked, flags, maxAxis, maxForce)
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
        else -> "Mount check: ${result.flags.size} of ${result.windowsChecked} windows moved from the baseline, first at " +
            "${"%.0f".format(java.util.Locale.US, (result.firstFlagMs ?: 0.0) / 1000)} s. The strap or sensor may have slipped; re-calibrate before trusting roll."
    }
}
