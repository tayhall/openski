package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RunComparisonTest {
    private fun row(side: String, sequence: Int, startMs: Long, durationMs: Int, peak: Float, receivedMs: Long = startMs + durationMs) =
        RunEventRow(side, sequence, startMs, durationMs, peak, 100f, 15f, 0, receivedMs)

    private fun verdict(timeMs: Long, kind: Verdict, side: String = "L") =
        RunVerdictRow(timeMs, side, kind.name, 90, 90, 90, 90, 100, 2.0, 20.0, false)

    private val info = RunInfoRow(2.0, 20.0, "Steady rhythm", 4, "AUTO", "{}", "manual", null, null, "[]")

    /** Alternating half-turns one beat apart; the first is positive (to the skier's left) when [firstPositive]. */
    private fun turns(side: String = "L", count: Int, peak: Float = 20f, beatMs: Long = 2000, from: Long = 10_000,
                      firstSequence: Int = 1, firstPositive: Boolean = true) =
        List(count) { i -> row(side, firstSequence + i, from + i * beatMs, (beatMs * 0.9).toInt(), if ((i % 2 == 0) == firstPositive) peak else -peak) }

    private fun run(events: List<RunEventRow>, verdicts: List<RunVerdictRow> = emptyList(), runInfo: RunInfoRow? = info, demo: Boolean = false) =
        RunComparisons.build(RunData(events, verdicts, runInfo), demo)

    private fun RollTrace.valueAt(second: Float): Float {
        val index = seconds.indices.minBy { abs(seconds[it] - second) }
        return degrees[index]
    }

    // the two waves -----------------------------------------------------------------------------------------

    @Test fun theTargetWaveStartsAtZeroAlternatesAndPeaksAtTheTargetDepth() {
        val target = run(turns(count = 4)).windows[0].target!!
        assertEquals(0f, target.valueAt(0f), 0.01f)
        assertEquals(20f, target.valueAt(1f), 0.5f)          // the first half-turn peaks half a beat in
        assertEquals(0f, target.valueAt(2f), 0.5f)
        assertEquals(-20f, target.valueAt(3f), 0.5f)         // the next half-turn is on the other side
        assertEquals(20f, target.degrees.max(), 0.01f)
        assertEquals(-20f, target.degrees.min(), 0.01f)
    }

    @Test fun theTargetWaveStartsOnTheSideOfTheFirstTurn() {
        val target = run(turns(count = 4, firstPositive = false)).windows[0].target!!
        assertEquals(-20f, target.valueAt(1f), 0.5f)
    }

    @Test fun theRecordedWaveIsAHalfSineThroughEachTurnsMeasuredPeak() {
        val recorded = run(turns(count = 4, peak = 17f)).windows[0].recorded
        assertEquals(17f, recorded.degrees.max(), 0.01f)
        assertEquals(-17f, recorded.degrees.min(), 0.01f)
        assertEquals(0f, recorded.degrees.first(), 0.01f)
        assertEquals(17f, recorded.valueAt(0.9f), 0.5f)       // middle of the first 1.8 s half-turn
        assertEquals(-17f, recorded.valueAt(2.9f), 0.5f)
    }

    @Test fun consecutiveTurnsJoinAtZeroWithoutJumps() {
        val recorded = run(turns(count = 4)).windows[0].recorded
        val biggestStep = (1 until recorded.degrees.size).maxOf { abs(recorded.degrees[it] - recorded.degrees[it - 1]) }
        assertTrue(biggestStep < 20f * 0.25f)                  // a smooth wave: no step bigger than one sample of the sine
        assertTrue(recorded.seconds.toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun aRealGapBetweenTurnsIsDrawnFlatAtZero() {
        val events = turns(count = 2) + turns(count = 2, from = 10_000 + 2 * 2000 + 4000, firstSequence = 3)   // a 4 s pause
        val recorded = run(events, runInfo = null).windows[0].recorded
        val flat = (1 until recorded.seconds.size).any { recorded.seconds[it] - recorded.seconds[it - 1] > 3.5f &&
            recorded.degrees[it] == 0f && recorded.degrees[it - 1] == 0f }
        assertTrue(flat)
    }

    @Test fun theRightBootIsMirroredIntoTheSkierFrame() {
        val events = turns(side = "R", count = 4)            // the boot's own sign: first turn positive
        val comparison = run(events, runInfo = info.copy(coachBoot = "RIGHT"))
        assertEquals("R", comparison.side)
        assertEquals(-20f, comparison.windows[0].recorded.valueAt(0.9f), 0.5f)
        assertEquals(-20f, comparison.windows[0].target!!.valueAt(1f), 0.5f)   // the target follows the mirrored first turn
    }

    @Test fun theBootClockWrappingMidWindowStillGivesTwoSecondBeats() {
        val events = listOf(
            row("L", 1, BOOT_CLOCK_WRAP_MS - 1000, 1800, 20f), row("L", 2, 1000, 1800, -20f),
            row("L", 3, 3000, 1800, 20f), row("L", 4, 5000, 1800, -20f))
        val recorded = run(events).windows[0].recorded
        assertEquals(7.8f, recorded.duration, 0.05f)          // turns start at 0, 2, 4 and 6 s; the last lasts 1.8 s
        assertEquals(-20f, recorded.valueAt(2.9f), 0.5f)
    }

    @Test fun longRunsKeepEveryPeakWhileUsingAFixedNumberOfPointsPerTurn() {
        // Turns that meet exactly (each half-turn ends where the next starts, as the recogniser reports them).
        val meeting = List(4) { i -> row("L", i + 1, 10_000L + i * 2000L, 2000, if (i % 2 == 0) 23.5f else -23.5f) }
        val recorded = run(meeting).windows[0].recorded
        assertEquals(4 * (RunComparisons.POINTS_PER_TURN + 1), recorded.seconds.size)
        assertEquals(23.5f, recorded.degrees.max(), 0.001f)
        assertEquals(-23.5f, recorded.degrees.min(), 0.001f)
    }

    // windows -----------------------------------------------------------------------------------------------

    @Test fun eachVerdictTakesTheLastWindowSizeTurnsBeforeIt() {
        val events = turns(count = 8)
        val verdicts = listOf(verdict(events[3].receivedMs + 10, Verdict.POSITIVE), verdict(events[7].receivedMs + 10, Verdict.NEGATIVE))
        val comparison = run(events, verdicts)
        assertEquals(listOf(Verdict.POSITIVE, Verdict.NEGATIVE), comparison.windows.map { it.verdict })
        assertEquals(listOf(4, 4), comparison.windows.map { it.turns })
        assertTrue(comparison.windows.none { it.partial })
        assertEquals(1, comparison.defaultWindow)             // the first off-target window
    }

    @Test fun turnsAfterTheLastVerdictFormAPartialWindowWithNoVerdict() {
        val events = turns(count = 6)
        val comparison = run(events, listOf(verdict(events[3].receivedMs + 10, Verdict.POSITIVE)))
        assertEquals(listOf(Verdict.POSITIVE, null), comparison.windows.map { it.verdict })
        assertEquals(listOf(4, 2), comparison.windows.map { it.turns })
        assertEquals(listOf(false, true), comparison.windows.map { it.partial })
        assertEquals(0, comparison.defaultWindow)             // nothing was off target
    }

    @Test fun aCoachResetLeavesTheDroppedTurnsOutOfAnyWindow() {
        // Turns 5 and 6 were dropped by a reset (no verdict for them); the next verdict covers turns 7 to 10.
        // The dropped turns are only 1 s long, so a window that wrongly included them would not last 7.8 s.
        val events = turns(count = 10).mapIndexed { i, e -> if (i == 4 || i == 5) e.copy(durationMs = 1000, receivedMs = e.startMs + 1000) else e }
        val verdicts = listOf(verdict(events[3].receivedMs + 10, Verdict.POSITIVE), verdict(events[9].receivedMs + 10, Verdict.NONE))
        val comparison = run(events, verdicts)
        assertEquals(listOf(4, 4), comparison.windows.map { it.turns })
        assertEquals(7.8f, comparison.windows[1].recorded.duration, 0.05f)     // turns 7 to 10: starts at 0, 2, 4, 6 s; the last lasts 1.8 s
        assertEquals(listOf(Verdict.POSITIVE, Verdict.NONE), comparison.windows.map { it.verdict })
    }

    @Test fun aVerdictNeverReusesTurnsFromTheWindowBeforeIt() {
        // The second verdict arrives after only two new turns (the coach reset in between): it covers those two, not four.
        val events = turns(count = 6)
        val verdicts = listOf(verdict(events[3].receivedMs + 10, Verdict.POSITIVE), verdict(events[5].receivedMs + 10, Verdict.NEGATIVE))
        val comparison = run(events, verdicts)
        assertEquals(listOf(4, 2), comparison.windows.map { it.turns })
        assertEquals(listOf(false, true), comparison.windows.map { it.partial })
        assertEquals(listOf(Verdict.POSITIVE, Verdict.NEGATIVE), comparison.windows.map { it.verdict })
    }

    @Test fun withNoVerdictsTurnsAreChunkedIntoPartialWindows() {
        val chunks = run(turns(count = 9)).windows
        assertEquals(listOf(4, 4, 1), chunks.map { it.turns })
        assertEquals(listOf(false, false, true), chunks.map { it.partial })
        assertTrue(chunks.all { it.verdict == null })
        val few = run(turns(count = 2)).windows
        assertEquals(1, few.size)
        assertTrue(few[0].partial)
    }

    // which boot, numbers and the sentence --------------------------------------------------------------------

    @Test fun theBootIsPinnedOrChosenByHowManyTurnsItHad() {
        val mixed = turns("L", 3) + turns("R", 5, firstSequence = 10)
        assertEquals("R", run(mixed).side)                                         // automatic: more turns on the right
        assertEquals("L", run(mixed, runInfo = info.copy(coachBoot = "LEFT")).side)    // pinned
        assertEquals("L", run(turns("L", 3) + turns("R", 3, firstSequence = 10)).side)  // a tie goes to the left
    }

    @Test fun theNumbersAreSignedDifferencesFromTheTarget() {
        val events = turns(count = 4, peak = 16f, beatMs = 2400)
        val numbers = run(events, listOf(verdict(events[3].receivedMs, Verdict.POSITIVE), verdict(events[3].receivedMs + 1, Verdict.NEGATIVE))).numbers
        assertEquals(4, numbers.turns)
        assertEquals(-4.0, numbers.depthDifferenceDegrees!!, 0.001)
        assertEquals(0.4, numbers.beatDifferenceSeconds!!, 0.001)
        assertEquals(16.0, numbers.leftDepthDegrees!!, 0.001)
        assertEquals(16.0, numbers.rightDepthDegrees!!, 0.001)
        assertEquals(0.5, numbers.matchedShare!!, 0.001)
        assertEquals(2, numbers.windowsWithVerdict)
    }

    @Test fun beatsLongerThanTwiceTheTargetAreAPauseNotABeat() {
        val events = turns(count = 2) + turns(count = 2, from = 10_000 + 2 * 2000 + 9000, firstSequence = 3)
        assertEquals(0.0, run(events).numbers.beatDifferenceSeconds!!, 0.001)       // the 9 s pause is ignored
    }

    @Test fun theSentenceSaysWhatDiffersAndWhatDoesNot() {
        val target = CoachTarget(2.0, 20.0)
        fun numbers(depth: Double?, beat: Double?, left: Double?, right: Double?, turns: Int = 12) =
            ComparisonNumbers(turns, depth, beat, left, right, null, 0)
        assertEquals("Your turns were about 6° shallower than the 20° target and 0.3 s slower; left and right were even.",
            RunComparisons.sentence(numbers(-6.0, 0.3, 14.0, 14.5), target))
        assertEquals("Your turns were close to the 20° target depth and 2.0 s beat; left and right were even.",
            RunComparisons.sentence(numbers(1.0, 0.05, 20.0, 20.0), target))
        assertEquals("Your turns were about 5° deeper than the 20° target and 0.4 s faster; your left turns were shallower.",
            RunComparisons.sentence(numbers(5.0, -0.4, 18.0, 25.0), target))
        assertEquals("Your turns were close to the 20° target depth and 0.3 s slower.",
            RunComparisons.sentence(numbers(0.5, 0.3, null, null), target))
        assertEquals("You made 12 turns; left and right were even.", RunComparisons.sentence(numbers(null, null, 20.0, 20.0), null))
        assertEquals("No turns were recorded.", RunComparisons.sentence(numbers(null, null, null, null, turns = 0), target))
    }

    // edge cases --------------------------------------------------------------------------------------------

    @Test fun aRunWithoutTargetDataStillShowsTurnsButNoTargetLine() {
        val comparison = run(turns(count = 4), runInfo = null)
        assertNull(comparison.target)
        assertNull(comparison.windows[0].target)
        assertNull(comparison.numbers.depthDifferenceDegrees)
        assertTrue(comparison.sentence.startsWith("You made 4 turns"))
    }

    @Test fun aRunWithNoTurnsIsEmptyButSafe() {
        val comparison = run(emptyList())
        assertTrue(comparison.windows.isEmpty())
        assertEquals(0, comparison.defaultWindow)
        assertEquals("No turns were recorded.", comparison.sentence)
        assertEquals(0, comparison.numbers.turns)
    }

    @Test fun theDemoFlagIsCarriedThrough() = assertTrue(run(turns(count = 4), demo = true).demo)
}
