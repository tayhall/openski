package com.openski.android

import kotlin.math.abs
import kotlin.math.roundToInt

/** What the skier is trying to match: the time between half-turn starts and the peak roll of each half-turn. */
data class CoachTarget(val beatSeconds: Double, val depthDegrees: Double) {
    companion object {
        const val MIN_BEAT = 0.8
        const val MAX_BEAT = 3.5
        const val MIN_DEPTH = 10.0
        const val MAX_DEPTH = 45.0

        fun of(drill: Drill) = CoachTarget(drill.paceSeconds, drill.depthDegrees)
        fun custom(beatSeconds: Double, depthDegrees: Double) =
            CoachTarget(beatSeconds.coerceIn(MIN_BEAT, MAX_BEAT), depthDegrees.coerceIn(MIN_DEPTH, MAX_DEPTH))
    }
}

enum class Verdict { POSITIVE, NEGATIVE, NONE }

/** The outcome of one window of half-turns. Component scores run 0 to 100. */
data class CoachVerdict(
    val verdict: Verdict,
    val score: Int,
    val tempo: Int,
    val steadiness: Int,
    val depth: Int,
    val balance: Int?,
    val meanBeatSeconds: Double,
    val meanDepthDegrees: Double,
    val outsideEnvelope: Boolean,
)

/** What the coaching screen shows. */
data class CoachState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val demo: Boolean = false,
    val target: CoachTarget? = null,
    val last: CoachVerdict? = null,
    val message: String = "",
)

/** The boot clock counts milliseconds of a 32-bit microsecond timer, so it wraps at 4,294,967 ms. */
const val BOOT_CLOCK_WRAP_MS = 4_294_967L

/** Forward difference between two boot-clock times, correct across the wrap. */
fun bootClockDelta(fromMs: Long, toMs: Long): Long = ((toMs - fromMs) % BOOT_CLOCK_WRAP_MS + BOOT_CLOCK_WRAP_MS) % BOOT_CLOCK_WRAP_MS

/**
 * Judges consecutive half-turns in non-overlapping windows against a [CoachTarget]. Pure logic: no Android,
 * no clock. It compares dry-ski boot roll with a training target; it says nothing about on-snow technique.
 */
class Coach(private val target: CoachTarget, windowSize: Int = DEFAULT_WINDOW) {
    private val size = windowSize.coerceIn(MIN_WINDOW, MAX_WINDOW)
    private val window = ArrayList<SkiEvent>()
    private var last: SkiEvent? = null

    fun reset() {
        window.clear()
        last = null
    }

    /** Feed one half-turn. Returns a verdict when this event completes a window. */
    fun onEvent(event: SkiEvent): CoachVerdict? {
        val previous = last
        if (previous != null) {
            if (event.sequence == previous.sequence) return null  // the same event twice
            val gapMs = bootClockDelta(previous.startMs, event.startMs)
            // A long gap means the skier stopped; the same side twice means a half-turn was missed.
            if (gapMs > 2 * target.beatSeconds * 1000 || event.positive == previous.positive) window.clear()
        }
        last = event
        window.add(event)
        if (window.size < size) return null
        val verdict = judge(window.toList())
        window.clear()
        return verdict
    }

    private fun judge(events: List<SkiEvent>): CoachVerdict {
        val beats = events.zipWithNext { a, b -> bootClockDelta(a.startMs, b.startMs) / 1000.0 }
        val meanBeat = beats.average()
        val peaks = events.map { abs(it.peakRollDegrees.toDouble()) }
        val meanDepth = peaks.average()
        val tempo = DrillScoring.closeness(meanBeat, target.beatSeconds, target.beatSeconds * 0.5)
        val steadiness = DrillScoring.steadiness(beats)
        val depth = DrillScoring.closeness(meanDepth, target.depthDegrees, target.depthDegrees * 0.6)
        val positives = events.filter { it.positive }.map { abs(it.peakRollDegrees.toDouble()) }
        val negatives = events.filterNot { it.positive }.map { abs(it.peakRollDegrees.toDouble()) }
        val balance = if (positives.isEmpty() || negatives.isEmpty()) null
            else 100 * minOf(positives.average(), negatives.average()) / maxOf(positives.average(), negatives.average())
        val parts = listOfNotNull(tempo, steadiness, depth, balance)
        val outside = events.any { it.outsideEnvelope }
        var score = parts.average()
        if (outside) score = minOf(score, OUTSIDE_CAP)
        val weakest = parts.min()
        // Averaging alone would let one total failure (say, no depth at all) hide behind three good parts, so a
        // chirp needs every part to be reasonable, and a clear miss on any one part is a falling chirp.
        val verdict = when {
            score >= POSITIVE_SCORE && weakest >= POSITIVE_FLOOR -> Verdict.POSITIVE
            score < NEGATIVE_SCORE || weakest < NEGATIVE_FLOOR -> Verdict.NEGATIVE
            else -> Verdict.NONE
        }
        return CoachVerdict(verdict, score.roundToInt(), tempo.roundToInt(), steadiness.roundToInt(), depth.roundToInt(),
            balance?.roundToInt(), meanBeat, meanDepth, outside)
    }

    companion object {
        const val DEFAULT_WINDOW = 4
        const val MIN_WINDOW = 3
        const val MAX_WINDOW = 5
        const val POSITIVE_SCORE = 70.0
        const val POSITIVE_FLOOR = 60.0
        const val NEGATIVE_SCORE = 40.0
        const val NEGATIVE_FLOOR = 20.0
        const val OUTSIDE_CAP = 60.0
    }
}
