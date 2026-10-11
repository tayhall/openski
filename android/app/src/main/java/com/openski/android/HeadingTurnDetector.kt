package com.openski.android

import kotlin.math.abs
import kotlin.math.sqrt

/** One heading lobe, in the replay's milliseconds. Experimental, as the firmware's HeadingTurn. */
data class HeadingTurn(
    val startMs: Double,
    val endMs: Double,
    val positive: Boolean,
    val peakRateDps: Double,
    val changeDegrees: Double,
    val skippedSamples: Boolean,
    val nearRail: Boolean,
)

/**
 * Line-for-line port of lib/Motion/src/HeadingTracker.h (Double maths, time in ms) so the phone can
 * replay heading turns from the same stream. Parity with the firmware is pinned by the
 * heading_turns.txt fixture (HeadingTurnDetectorTest). Experimental.
 */
class HeadingTurnDetector {
    companion object {
        const val FLAG_SKIPPED = 1
        const val FLAG_NEAR_RAIL = 2
        const val DEGREES = 57.2957795
        private const val SMOOTH_TAU_S = 0.25
        private const val BASELINE_TAU_S = 4.0
        private const val SD_TAU_S = 20.0
        private const val AMPLITUDE_SD = 0.6
        private const val FLOOR_DPS = 15.0
        private const val MIN_DURATION_MS = 400.0
        private const val GAP_MS = 100.0
    }

    private var hasTime = false
    private var seeded = false
    private var skipped = false
    private var nearRail = false
    private var lastMs = 0.0
    private var firstMs = 0.0
    private var hasFirst = false
    private var segmentStartMs = 0.0
    private var sign = 0
    private var smooth = 0.0
    private var base = 0.0
    private var variance = 0.0
    private var area = 0.0
    private var peak = 0.0

    var rejected = 0
        private set

    /** Feeds one sample; returns the turn closed by this sample's sign flip, else null. */
    fun accept(timeMs: Double, rateRadps: Double, flags: Int = 0): HeadingTurn? {
        val rate = rateRadps * DEGREES
        if (hasTime && timeMs - lastMs > GAP_MS) breakSegment()
        val reseeded = !seeded
        if (reseeded) {
            smooth = rate; base = rate; seeded = true
            segmentStartMs = timeMs; area = 0.0; peak = 0.0; sign = 0
            skipped = false; nearRail = false
        }
        // The reseeding sample takes no filter step, so the SD survives a gap.
        val dt = if (reseeded) 0.0 else (timeMs - lastMs) / 1000.0
        if (!hasFirst) { firstMs = timeMs; hasFirst = true }
        hasTime = true; lastMs = timeMs
        if (dt > 0) {
            smooth += dt / (SMOOTH_TAU_S + dt) * (rate - smooth)
            base += dt / (BASELINE_TAU_S + dt) * (smooth - base)
        }
        val x = smooth - base
        if (dt > 0) variance += dt / (SD_TAU_S + dt) * (x * x - variance)
        if (flags and FLAG_NEAR_RAIL != 0) nearRail = true
        if (flags and FLAG_SKIPPED != 0) skipped = true
        val s = if (x >= 0) 1 else -1
        if (sign == 0) sign = s
        var closed: HeadingTurn? = null
        if (s != sign) {
            closed = close(timeMs)
            sign = s; segmentStartMs = timeMs; area = 0.0; peak = 0.0
            skipped = false; nearRail = false
        }
        area += x * dt
        if (abs(x) > abs(peak)) peak = x
        return closed
    }

    /** An impact-gated sample: its rate is not trusted, filters are untouched. */
    fun skip() { skipped = true }

    /** Sample loss: the open segment cannot be trusted and filters restart from the next sample. */
    fun breakSegment() { seeded = false; sign = 0 }

    private fun close(timeMs: Double): HeadingTurn? {
        val sd = sqrt(if (variance > 0) variance else 0.0)
        var threshold = AMPLITUDE_SD * sd
        if (threshold < FLOOR_DPS) threshold = FLOOR_DPS
        if (abs(peak) < threshold) return null  // wobble, not a turn
        val delayMs = SMOOTH_TAU_S * 1000.0
        if (timeMs - segmentStartMs < MIN_DURATION_MS) { rejected++; return null }
        return HeadingTurn(
            startMs = maxOf(segmentStartMs - delayMs, firstMs),
            endMs = timeMs - delayMs,
            positive = sign > 0,
            peakRateDps = peak,
            changeDegrees = area,
            skippedSamples = skipped,
            nearRail = nearRail,
        )
    }
}
