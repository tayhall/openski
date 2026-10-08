package com.openski.android

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** One coaching window: the turns, the verdict the coach gave them (if any), and the two waves to draw. */
data class ComparisonWindow(
    val verdict: Verdict?,
    /** Fewer turns than a full window. */
    val partial: Boolean,
    val turns: Int,
    val recorded: RollTrace,
    /** The wave the skier was aiming for, re-anchored at the start of this window; null when the run has no target. */
    val target: RollTrace?,
)

data class ComparisonNumbers(
    val turns: Int,
    /** Mean peak depth minus the target depth, in degrees. */
    val depthDifferenceDegrees: Double?,
    /** Mean beat (time between half-turn starts) minus the target beat, in seconds. */
    val beatDifferenceSeconds: Double?,
    /** Mean peak depth of turns to the skier's left and to the right. */
    val leftDepthDegrees: Double?,
    val rightDepthDegrees: Double?,
    /** Windows with a matched verdict over windows with any verdict. */
    val matchedShare: Double?,
    val windowsWithVerdict: Int,
)

data class RunComparison(
    val side: String,
    val target: CoachTarget?,
    val demo: Boolean,
    val windows: List<ComparisonWindow>,
    val numbers: ComparisonNumbers,
    val sentence: String,
    /** The window to show first: the first off-target one, since it is the most useful, else the first. */
    val defaultWindow: Int,
)

/**
 * Turns a run's stored half-turns, coaching verdicts and target into two waves per coaching window, the summary
 * numbers and a plain sentence. Pure logic. The recorded wave is reconstructed from the half-turns (a half-sine
 * through each turn's measured peak, start and length), not the measured roll curve. Dry-ski boot roll against a
 * training target; nothing here is an on-snow technique score.
 */
object RunComparisons {
    const val POINTS_PER_TURN = 16
    const val CLOSE_DEPTH_DEGREES = 2.0
    const val CLOSE_BEAT_SECONDS = 0.15
    const val UNEVEN_SHARE = 0.2
    /** A verdict may be stamped a few milliseconds before the turn that completed its window; this forgives that. */
    const val VERDICT_TOLERANCE_MS = 50L

    private class Turn(val row: RunEventRow, val peak: Float)

    fun build(data: RunData, demo: Boolean): RunComparison {
        val info = data.info
        val target = info?.let { CoachTarget(it.targetBeat, it.targetDepth) }
        val windowSize = (info?.windowSize ?: Coach.DEFAULT_WINDOW).coerceIn(Coach.MIN_WINDOW, Coach.MAX_WINDOW)
        val side = chooseSide(info?.coachBoot, data.events, data.verdicts)
        val mirror = if (side == "R") -1f else 1f
        // Half-turns are stored as the boot sent them; roll is mirrored into the skier frame on read.
        val turns = data.events.filter { it.side == side }.map { Turn(it, it.peakRoll * mirror) }
        val verdicts = data.verdicts.filter { it.side == side }.sortedBy { it.timeMs }

        val windows = mutableListOf<ComparisonWindow>()
        var used = 0
        for (verdict in verdicts) {
            val end = turns.indexOfLast { it.row.receivedMs <= verdict.timeMs + VERDICT_TOLERANCE_MS } + 1
            val start = maxOf(used, end - windowSize)
            if (end - start <= 0) continue
            windows.add(window(turns.subList(start, end), runCatching { Verdict.valueOf(verdict.verdict) }.getOrNull(), end - start < windowSize, target))
            used = end
        }
        var next = used
        while (next < turns.size) {
            val end = minOf(turns.size, next + windowSize)
            windows.add(window(turns.subList(next, end), null, end - next < windowSize, target))
            next = end
        }

        val numbers = numbers(turns, verdicts, target)
        val defaultWindow = windows.indexOfFirst { it.verdict == Verdict.NEGATIVE }.takeIf { it >= 0 } ?: 0
        return RunComparison(side, target, demo, windows, numbers, sentence(numbers, target), defaultWindow)
    }

    /** The boot the coach followed: pinned in the settings, else the boot its verdicts came from, else the boot with more turns. */
    private fun chooseSide(coachBoot: String?, events: List<RunEventRow>, verdicts: List<RunVerdictRow>): String = when {
        coachBoot == "LEFT" -> "L"
        coachBoot == "RIGHT" -> "R"
        verdicts.isNotEmpty() -> if (verdicts.count { it.side == "R" } > verdicts.count { it.side == "L" }) "R" else "L"
        else -> if (events.count { it.side == "R" } > events.count { it.side == "L" }) "R" else "L"
    }

    private fun window(turns: List<Turn>, verdict: Verdict?, partial: Boolean, target: CoachTarget?): ComparisonWindow {
        val origin = turns.first().row.startMs
        val seconds = ArrayList<Float>()
        val degrees = ArrayList<Float>()
        var previousEnd = 0.0
        for (turn in turns) {
            // A start stamped before the window's first turn would look like a delta of almost the whole clock wrap: treat it as zero.
            val delta = bootClockDelta(origin, turn.row.startMs).let { if (it > BOOT_CLOCK_WRAP_MS / 2) 0L else it }
            val start = maxOf(delta / 1000.0, previousEnd)
            val length = (turn.row.durationMs / 1000.0).coerceAtLeast(0.05)
            if (seconds.isNotEmpty() && start > previousEnd + 1e-6) {
                // A real gap between two half-turns: flat at zero.
                seconds.add(previousEnd.toFloat()); degrees.add(0f)
                seconds.add(start.toFloat()); degrees.add(0f)
            }
            for (k in 0..POINTS_PER_TURN) {
                seconds.add((start + length * k / POINTS_PER_TURN).toFloat())
                degrees.add((turn.peak * sin(PI * k / POINTS_PER_TURN)).toFloat())
            }
            previousEnd = start + length
        }
        val recorded = RollTrace(seconds.toFloatArray(), degrees.toFloatArray())
        return ComparisonWindow(verdict, partial, turns.size, recorded, target?.let { targetWave(it, previousEnd, if (turns.first().peak >= 0) 1f else -1f) })
    }

    /** `roll(t) = ±depth · sin(π·t / beat)`, alternating sign each beat, starting on the side of the window's first turn. */
    private fun targetWave(target: CoachTarget, total: Double, firstSign: Float): RollTrace {
        val steps = ceil(total / target.beatSeconds * POINTS_PER_TURN).toInt().coerceAtLeast(1)
        val seconds = FloatArray(steps + 1)
        val degrees = FloatArray(steps + 1)
        for (step in 0..steps) {
            val t = minOf(total, step * target.beatSeconds / POINTS_PER_TURN)
            val beatIndex = floor(t / target.beatSeconds).toInt()
            val sign = if (beatIndex % 2 == 0) firstSign else -firstSign
            seconds[step] = t.toFloat()
            degrees[step] = (sign * target.depthDegrees * sin(PI * (t - beatIndex * target.beatSeconds) / target.beatSeconds)).toFloat()
        }
        return RollTrace(seconds, degrees)
    }

    private fun numbers(turns: List<Turn>, verdicts: List<RunVerdictRow>, target: CoachTarget?): ComparisonNumbers {
        if (turns.isEmpty()) return ComparisonNumbers(0, null, null, null, null, null, verdicts.size)
        val depth = turns.map { abs(it.peak.toDouble()) }.average()
        val limit = target?.let { 2 * it.beatSeconds } ?: 10.0
        val beats = turns.zipWithNext { a, b -> bootClockDelta(a.row.startMs, b.row.startMs) / 1000.0 }.filter { it in 0.001..limit }
        val left = turns.filter { it.peak > 0 }.map { it.peak.toDouble() }
        val right = turns.filter { it.peak < 0 }.map { -it.peak.toDouble() }
        return ComparisonNumbers(
            turns = turns.size,
            depthDifferenceDegrees = target?.let { depth - it.depthDegrees },
            beatDifferenceSeconds = if (target != null && beats.isNotEmpty()) beats.average() - target.beatSeconds else null,
            leftDepthDegrees = left.takeIf { it.isNotEmpty() }?.average(),
            rightDepthDegrees = right.takeIf { it.isNotEmpty() }?.average(),
            matchedShare = verdicts.takeIf { it.isNotEmpty() }?.let { v -> v.count { it.verdict == Verdict.POSITIVE.name }.toDouble() / v.size },
            windowsWithVerdict = verdicts.size,
        )
    }

    private fun one(value: Double) = String.format(java.util.Locale.US, "%.1f", value)

    fun sentence(n: ComparisonNumbers, target: CoachTarget?): String {
        if (n.turns == 0) return "No turns were recorded."
        val parts = mutableListOf<String>()
        val depth = n.depthDifferenceDegrees
        val beat = n.beatDifferenceSeconds
        if (target != null && depth != null) {
            val depthClose = abs(depth) < CLOSE_DEPTH_DEGREES
            val beatClose = beat != null && abs(beat) < CLOSE_BEAT_SECONDS
            val aim = "${target.depthDegrees.roundToInt()}°"
            parts += when {
                depthClose && beat != null && beatClose -> "close to the $aim target depth and ${one(target.beatSeconds)} s beat"
                depthClose -> "close to the $aim target depth"
                else -> "about ${abs(depth).roundToInt()}° ${if (depth < 0) "shallower" else "deeper"} than the $aim target"
            }
            if (beat != null && !beatClose) parts += "${one(abs(beat))} s ${if (beat > 0) "slower" else "faster"}"
        }
        val lead = if (parts.isEmpty()) "You made ${n.turns} turns" else "Your turns were ${parts.joinToString(" and ")}"
        val left = n.leftDepthDegrees
        val right = n.rightDepthDegrees
        val balance = if (left != null && right != null) {
            if (minOf(left, right) / maxOf(left, right) >= 1 - UNEVEN_SHARE) "left and right were even"
            else "your ${if (left < right) "left" else "right"} turns were shallower"
        } else null
        return "$lead${if (balance != null) "; $balance" else ""}."
    }
}
