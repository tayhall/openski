package com.openski.android

import kotlin.math.abs

/**
 * Experimental yaw-based descriptors of one turn window for one boot. All return null when the window has
 * fewer than [MIN_SAMPLES] samples, a gap over [MAX_GAP_MS] (including at either end of the window), and,
 * for yaw ranges, when the window spans an orientation segment change.
 */
object YawTurnShape {
    const val MAX_GAP_MS = 100.0
    private const val MIN_SAMPLES = 10
    private const val EPSILON = 1e-6

    private fun window(rolls: List<BootRoll>, startMs: Double, endMs: Double): List<BootRoll>? {
        val w = rolls.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }
        if (w.size < MIN_SAMPLES || w.zipWithNext().any { (a, b) -> b.timeMs - a.timeMs > MAX_GAP_MS } ||
            w.first().timeMs - startMs > MAX_GAP_MS || endMs - w.last().timeMs > MAX_GAP_MS) return null
        return w
    }

    /** Peak absolute yaw rate over mean absolute yaw rate: about 1 for even rotation, larger for one sharp pivot. */
    fun turnShape(rolls: List<BootRoll>, startMs: Double, endMs: Double): Double? {
        val rates = window(rolls, startMs, endMs)?.map { abs(it.yawRateRadps) } ?: return null
        val mean = rates.average()
        return if (mean < EPSILON) null else rates.max() / mean
    }

    /** Range (max minus min) of yaw over the first half of the window, in degrees. */
    fun turnClosure(rolls: List<BootRoll>, startMs: Double, endMs: Double): Double? {
        val w = window(rolls, startMs, endMs) ?: return null
        if (w.any { it.segment != w.first().segment }) return null  // yaw is relative and restarts at a segment change
        val half = w.filter { it.timeMs <= startMs + (endMs - startMs) / 2 }.map { it.yawDegrees }
        return if (half.isEmpty()) null else half.max() - half.min()
    }

    /** Roll-peak time minus yaw-rate-peak time (ms); negative means roll peaks first, so the edge leads. */
    fun yawLeadMs(rolls: List<BootRoll>, windowStartMs: Double, windowEndMs: Double): Double? {
        val w = window(rolls, windowStartMs, windowEndMs) ?: return null
        return w.maxBy { abs(it.degrees) }.timeMs - w.maxBy { abs(it.yawRateRadps) }.timeMs
    }
}
