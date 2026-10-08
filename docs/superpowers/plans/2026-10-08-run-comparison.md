# Run Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a run, the app shows the wave the skier was aiming for next to the wave they actually skied, window by window, with a plain sentence and a few numbers, on the run's Done screen and in a "Run" tab in the logbook.

**Architecture:** A pure Kotlin `RunComparisons.build` turns a run's stored half-turns, coaching verdicts and target into windows, two waves per window, numbers and a sentence. A `RunComparisonCard` builds the view around the existing `CarvedLineView`. The service loads a finished run's data off the main thread for the Done screen; the logbook's detail screen already has the run data and gets a new first tab.

**Tech Stack:** Native Kotlin Android (no Compose or AndroidX beyond the platform), JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-08-run-comparison-design.md` (approved 8 October 2026, wording updated after prototyping). Read it first.

**Base:** branch `feat/run-comparison`, cut from `main` after PRs #8, #9 and #10 (the recogniser, the coaching engine and the run flow) were merged. `RunData`, `RunEventRow`, `RunVerdictRow`, `RunInfoRow`, `Coach`, `CoachTarget`, `Verdict`, `RollTrace`, `CarvedLineView` and `SkierFrame` already exist.

## Global Constraints

- Native Kotlin, minSdk 26, compile and target SDK 36, no Compose or AndroidX beyond the platform; hand-built Views. The card is styled in the light `Snow` look so it works inside either look.
- Experimental framing everywhere: the chart compares dry-ski boot roll with a training target. It never claims on-snow technique, edge angle or carving, and it says the recorded wave is reconstructed from half-turns.
- No database change. The card only reads tables the run flow already writes.
- The recorded wave is a half-sine per stored half-turn (start, duration, measured peak, sign); the right boot is mirrored on read; the target wave is `±depth·sin(π·t/beat)`, re-anchored at each window's first turn.
- Each stored verdict defines its window as the last `windowSize` half-turns received at or before it; turns after the last verdict are chunked into partial windows.
- Sentence thresholds: depth is "close" under 2°, beat under 0.15 s, left and right are "uneven" when their mean depths differ by more than 20%.
- Source files use LF line endings. On Windows never rewrite a file with Python text mode (it writes CRLF); use `newline=''` or the Edit tool, and check `git diff --stat` shows only the lines you meant to change.
- Branch work happens on `feat/run-comparison`; end every commit message with the two lines `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z`.
- Update the relevant docs when behaviour changes (Task 3).

Gradle is run from `android/` (`./gradlew.bat ...` in Git Bash, `.\gradlew.bat ...` in PowerShell). Wait for each run to finish and read its output; "BUILD SUCCESSFUL" and the per-test counts in `app/build/test-results/testDebugUnitTest/TEST-com.openski.android.<Class>.xml` are the evidence.

Patches in this plan were produced from a working prototype and checked with `git apply --check` against this branch. To apply one, save the block to `.pio/host/<name>.patch` (create the folder if needed), then run `git apply --check` followed by `git apply` from the repo root. Expected: no output.

## Review Focus

Failure modes the spec implies but the happy path does not exercise, most likely first. Each has a pinning test or checklist item in the task named.

1. **A pause or a coach reset must not pull dropped turns into a window.** Task 1, `aCoachResetLeavesTheDroppedTurnsOutOfAnyWindow` (it uses turns of a different length, so a wrongly included turn changes the window's duration).
2. **The boot clock wrapping mid-run** must not produce a negative or huge time axis. Task 1, `theBootClockWrappingMidWindowStillGivesTwoSecondBeats`.
3. **A run with no turns, one turn, or no target data** must show something sensible and never crash. Task 1, the three edge-case tests; Task 2 (the card returns early for no windows).
4. **The right boot's sign.** Task 1, `theRightBootIsMirroredIntoTheSkierFrame`; device checklist item 3.
5. **A run opened in the logbook before its flash is saved**, or a normal (non-run) session, must still open and show the old tabs. Task 2 (the Run tab exists only when the session has run data); device checklist item 4.

---

### Task 1: The comparison logic

**Files:**
- Create: `android/app/src/main/java/com/openski/android/RunComparison.kt`
- Create: `android/app/src/test/java/com/openski/android/RunComparisonTest.kt`

**Interfaces:**
- Produces (used by Task 2): `data class ComparisonWindow(verdict: Verdict?, partial: Boolean, turns: Int, recorded: RollTrace, target: RollTrace?)`; `data class ComparisonNumbers(turns, depthDifferenceDegrees, beatDifferenceSeconds, leftDepthDegrees, rightDepthDegrees, matchedShare, windowsWithVerdict)`; `data class RunComparison(side, target: CoachTarget?, demo, windows, numbers, sentence, defaultWindow)`; `object RunComparisons { fun build(data: RunData, demo: Boolean): RunComparison; fun sentence(numbers, target): String; const val POINTS_PER_TURN = 16 }`.
- Consumes: `RunData`, `RunEventRow`, `RunVerdictRow`, `RunInfoRow` (`RunRecords.kt`), `CoachTarget`, `Verdict`, `Coach.DEFAULT_WINDOW/MIN_WINDOW/MAX_WINDOW`, `bootClockDelta`, `RollTrace`.

The code below was written and run in a scratch copy before this plan was finalised: 21 `RunComparisonTest` cases pass.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/openski/android/RunComparisonTest.kt`:

```kotlin
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

    @Test fun theNumberLinesStateTheFactsBehindTheSentence() {
        val events = turns(count = 4, peak = 16f, beatMs = 2400)
        val comparison = run(events, listOf(verdict(events[3].receivedMs, Verdict.POSITIVE), verdict(events[3].receivedMs + 1, Verdict.NEGATIVE)))
        assertEquals(listOf("Depth: 16° average, aim 20°", "Beat: 2.4 s average, aim 2.0 s",
            "Left turns 16° · right turns 16°", "Matched 1 of 2 windows"), RunComparisonCard.numberLines(comparison))
        assertTrue(RunComparisonCard.numberLines(run(emptyList())).isEmpty())
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
```

This test file also references `RunComparisonCard.numberLines`, which Task 2 creates. Until then the test file cannot compile, so for Task 1 temporarily delete the `theNumberLinesStateTheFactsBehindTheSentence` test (the block starting `@Test fun theNumberLinesStateTheFactsBehindTheSentence`), and put it back in Task 2 Step 1.

- [ ] **Step 2: Run it to confirm it fails**

Run (from `android/`): `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.RunComparisonTest"`
Expected: FAIL to compile with `Unresolved reference: RunComparisons`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/openski/android/RunComparison.kt`:

```kotlin
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

    private class Turn(val row: RunEventRow, val peak: Float)

    fun build(data: RunData, demo: Boolean): RunComparison {
        val info = data.info
        val target = info?.let { CoachTarget(it.targetBeat, it.targetDepth) }
        val windowSize = (info?.windowSize ?: Coach.DEFAULT_WINDOW).coerceIn(Coach.MIN_WINDOW, Coach.MAX_WINDOW)
        val side = chooseSide(info?.coachBoot, data.events)
        val mirror = if (side == "R") -1f else 1f
        // Half-turns are stored as the boot sent them; roll is mirrored into the skier frame on read.
        val turns = data.events.filter { it.side == side }.map { Turn(it, it.peakRoll * mirror) }
        val verdicts = data.verdicts.filter { it.side == side }.sortedBy { it.timeMs }

        val windows = mutableListOf<ComparisonWindow>()
        var used = 0
        for (verdict in verdicts) {
            val end = turns.indexOfLast { it.row.receivedMs <= verdict.timeMs } + 1
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

    private fun chooseSide(coachBoot: String?, events: List<RunEventRow>): String = when (coachBoot) {
        "LEFT" -> "L"
        "RIGHT" -> "R"
        else -> if (events.count { it.side == "R" } > events.count { it.side == "L" }) "R" else "L"
    }

    private fun window(turns: List<Turn>, verdict: Verdict?, partial: Boolean, target: CoachTarget?): ComparisonWindow {
        val origin = turns.first().row.startMs
        val seconds = ArrayList<Float>()
        val degrees = ArrayList<Float>()
        var previousEnd = 0.0
        for (turn in turns) {
            val start = maxOf(bootClockDelta(origin, turn.row.startMs) / 1000.0, previousEnd)
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.RunComparisonTest"`
Expected: `BUILD SUCCESSFUL`; `TEST-com.openski.android.RunComparisonTest.xml` reports `tests="20"` (the 21 minus the number-lines test) and `failures="0"`.

- [ ] **Step 5: Prove the window rule is tested**

Temporarily change `val start = maxOf(used, end - windowSize)` to `val start = maxOf(0, end - windowSize)` in `RunComparison.kt`, rerun, and expect FAIL in `aVerdictNeverReusesTurnsFromTheWindowBeforeIt` (the other windows tests do not catch it, because there the two bounds happen to agree). Restore it and rerun to see all pass. Do not commit the temporary change.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/openski/android/RunComparison.kt android/app/src/test/java/com/openski/android/RunComparisonTest.kt
git commit -m "$(cat <<'EOF'
Add the run comparison logic: waves, windows, numbers and a sentence

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

(If the number-lines test was removed in Step 1, the committed test file lacks it; Task 2 restores it.)

---

### Task 2: The card, the Done screen and the logbook tab

**Files:**
- Create: `android/app/src/main/java/com/openski/android/RunComparisonCard.kt`
- Modify: `android/app/src/test/java/com/openski/android/RunComparisonTest.kt` (restore the number-lines test)
- Modify: `android/app/src/main/java/com/openski/android/SensorSessionService.kt` (patch)
- Modify: `android/app/src/main/java/com/openski/android/RunActivity.kt` (patch)
- Modify: `android/app/src/main/java/com/openski/android/SessionDetailActivity.kt` (patch)

**Interfaces:**
- Consumes: Task 1; `Snow` helpers, `CarvedLineView(context, trace, ghost, targetDegrees, lineColor)` with `progress`, `LocalSessionStore.getSession/runData`, `SessionData.run`.
- Produces: `RunComparisonCard.build(context, comparison): View` and `internal fun numberLines(comparison): List<String>`; `SensorSessionService.lastRunId()` and `loadRunComparison(id, callback)`; a "Run" tab, first and selected, on a run's detail screen.

The card, service and screens have no JVM unit tests except `numberLines`: they are views and wiring. The proof is that everything compiles, the tests pass, lint is clean, and the emulator check in Step 5 shows the card on a demo run. Real-run shapes and the right boot's side need hardware (Task 3 checklist).

- [ ] **Step 1: Restore the number-lines test (failing)**

Put back the `theNumberLinesStateTheFactsBehindTheSentence` test in `RunComparisonTest.kt` (it is in the file listing in Task 1 Step 1). Run (from `android/`): `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.RunComparisonTest"`
Expected: FAIL to compile with `Unresolved reference: RunComparisonCard`.

- [ ] **Step 2: Create the card**

Create `android/app/src/main/java/com/openski/android/RunComparisonCard.kt`:

```kotlin
package com.openski.android

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The card that shows a run's two waves: a sentence, the numbers, one tile per coaching window, and a chart for the
 * selected window (recorded line solid, target pale, the target depth as a band) with a replay. Styled in the light
 * Snow look so it works inside either look. Dry-ski boot roll against a training target, not on-snow technique.
 */
object RunComparisonCard {
    fun build(context: Context, comparison: RunComparison): View {
        val card = Snow.card(context, 18)
        fun text(value: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(context, value, type, tint)
        fun LinearLayout.add(view: View, top: Int = 0) =
            addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(context, top) })

        card.addView(text("How your turns compared", Snow.Type.TITLE))
        if (comparison.demo) card.add(text("Demo run · simulated turns", Snow.Type.CAPTION, Snow.INK_SOFT), 2)
        card.add(text(comparison.sentence), 6)
        numberLines(comparison).forEach { card.add(text(it, Snow.Type.CAPTION, Snow.INK_SOFT), 6) }
        if (comparison.windows.isEmpty()) return card

        var selected = comparison.defaultWindow.coerceIn(0, comparison.windows.lastIndex)
        val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chartHolder = FrameLayout(context)
        var animator: ValueAnimator? = null
        var line: CarvedLineView? = null
        val tileViews = mutableListOf<TextView>()

        fun styleTiles() = tileViews.forEachIndexed { index, tile ->
            val on = index == selected
            tile.setTextColor(if (on) Snow.SNOW else Snow.INK)
            tile.background = Snow.rounded(context, if (on) Snow.INK else Snow.PAPER, 16, Snow.INK, 2)
            tile.contentDescription = tileDescription(index, comparison.windows[index]) + if (on) ", selected" else ""
        }

        fun showChart() {
            animator?.cancel()
            val window = comparison.windows[selected]
            val depth = comparison.target?.depthDegrees ?: max(10.0, window.recorded.degrees.maxOfOrNull { kotlin.math.abs(it) }?.toDouble() ?: 10.0)
            line = CarvedLineView(context, window.recorded, window.target, depth, Snow.BLUE)
            chartHolder.removeAllViews()
            chartHolder.addView(line, FrameLayout.LayoutParams(-1, -2))
            styleTiles()
        }

        comparison.windows.forEachIndexed { index, window ->
            val tile = TextView(context).apply {
                text = "${marker(window.verdict)}\n${index + 1}"
                textSize = 16f
                gravity = Gravity.CENTER
                minWidth = Snow.dp(context, 56); minHeight = Snow.dp(context, 56)
                isClickable = true; isFocusable = true
                setOnClickListener { selected = index; showChart() }
            }
            tileViews.add(tile)
            tiles.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Snow.dp(context, 8) })
        }
        card.add(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tiles) }, 12)
        card.add(chartHolder, 12)
        showChart()

        card.add(Snow.button(context, "Play", Snow.ButtonKind.SECONDARY) {
            val chart = line ?: return@button
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (comparison.windows[selected].recorded.duration * 1000).toLong().coerceIn(1500L, 6000L)
                interpolator = LinearInterpolator()
                addUpdateListener { chart.progress = it.animatedValue as Float }
                start()
            }
        }, 12)
        card.add(text(if (comparison.target != null)
            "Reconstructed from your half-turns. The pale line is the target. Dry-ski boot roll against a training target, not on-snow technique."
        else "Reconstructed from your half-turns. Dry-ski boot roll, not on-snow technique.", Snow.Type.CAPTION, Snow.INK_SOFT), 10)
        return card
    }

    private fun marker(verdict: Verdict?) = when (verdict) {
        Verdict.POSITIVE -> "▲"
        Verdict.NEGATIVE -> "▼"
        Verdict.NONE -> "●"
        null -> "–"
    }

    private fun tileDescription(index: Int, window: ComparisonWindow): String {
        val outcome = when (window.verdict) {
            Verdict.POSITIVE -> "matched the target"
            Verdict.NEGATIVE -> "off target"
            Verdict.NONE -> "close"
            null -> if (window.partial) "too few turns for a verdict" else "no verdict"
        }
        return "Window ${index + 1}, $outcome"
    }

    /** The plain facts behind the sentence, one per line. */
    internal fun numberLines(comparison: RunComparison): List<String> {
        val n = comparison.numbers
        val target = comparison.target
        val lines = mutableListOf<String>()
        if (n.turns == 0) return lines
        fun one(value: Double) = String.format(Locale.US, "%.1f", value)
        if (target != null && n.depthDifferenceDegrees != null)
            lines += "Depth: ${(target.depthDegrees + n.depthDifferenceDegrees).roundToInt()}° average, aim ${target.depthDegrees.roundToInt()}°"
        if (target != null && n.beatDifferenceSeconds != null)
            lines += "Beat: ${one(target.beatSeconds + n.beatDifferenceSeconds)} s average, aim ${one(target.beatSeconds)} s"
        if (n.leftDepthDegrees != null && n.rightDepthDegrees != null)
            lines += "Left turns ${n.leftDepthDegrees.roundToInt()}° · right turns ${n.rightDepthDegrees.roundToInt()}°"
        if (n.matchedShare != null) lines += "Matched ${(n.matchedShare * n.windowsWithVerdict).roundToInt()} of ${n.windowsWithVerdict} windows"
        return lines
    }
}
```

- [ ] **Step 3: Wire the service, the Done screen and the logbook**

`SensorSessionService.kt.patch` (remembers the run and loads its comparison off the main thread):

```diff
diff --git a/android/app/src/main/java/com/openski/android/SensorSessionService.kt b/android/app/src/main/java/com/openski/android/SensorSessionService.kt
index 8c9cf1e..5ddbe28 100644
--- a/android/app/src/main/java/com/openski/android/SensorSessionService.kt
+++ b/android/app/src/main/java/com/openski/android/SensorSessionService.kt
@@ -85,6 +85,7 @@ class SensorSessionService : Service() {
     private var runIsDemo = false
     private var runStartedMonitoring = false
     private var runTicks = 0
+    private var lastRunId: String? = null
     @Volatile private var runCaptures: List<SensorCapture> = emptyList()
     private var runSidesAtStart = setOf<String>()
     private var runListener: ((RunState) -> Unit)? = null
@@ -128,6 +129,7 @@ class SensorSessionService : Service() {
                 return problem
             }
             coachAudio?.playChime(ToneSynth.startChime())
+            lastRunId = runSessionId
             refreshRunCaptures()
             return null
         }
@@ -738,6 +740,20 @@ class SensorSessionService : Service() {
         io.execute { try { runCaptures = store.captures(id) } catch (_: Exception) { /* keep the last snapshot */ } }
     }
 
+    /** The run most recently started, for the Done screen's comparison. */
+    fun lastRunId(): String? = lastRunId
+
+    /** Builds a run's comparison off the main thread; the callback gets null if the run is not found. */
+    fun loadRunComparison(id: String, callback: (RunComparison?) -> Unit) {
+        io.execute {
+            val result = try {
+                val session = store.getSession(id)
+                if (session == null || session.kind != "run") null else RunComparisons.build(store.runData(id), session.origin == "synthetic")
+            } catch (error: Exception) { null }
+            mainHandler.post { if (!destroyed) callback(result) }
+        }
+    }
+
     fun cancelRun() { runController.cancel(); runFinished(); runListener?.invoke(runState()) }
     fun retryZero() { runController.retryZero(System.currentTimeMillis()); runListener?.invoke(runState()) }
     fun stopRun() { runController.stop(System.currentTimeMillis()); runListener?.invoke(runState()) }
```

`RunActivity.kt.patch` (the card under the Done summary):

```diff
diff --git a/android/app/src/main/java/com/openski/android/RunActivity.kt b/android/app/src/main/java/com/openski/android/RunActivity.kt
index 57327c1..a0bfc1b 100644
--- a/android/app/src/main/java/com/openski/android/RunActivity.kt
+++ b/android/app/src/main/java/com/openski/android/RunActivity.kt
@@ -261,6 +261,13 @@ class RunActivity : Activity() {
             card.add(t("This compares dry-ski boot roll with a training target. It is not an on-snow technique score.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 10)
             column.add(card, top = 16, bottom = 16)
         }
+        val comparisonHolder = FrameLayout(this)
+        column.add(comparisonHolder, bottom = 16)
+        service?.lastRunId()?.let { id ->
+            service?.loadRunComparison(id) { comparison ->
+                if (comparison != null && comparisonHolder.isAttachedToWindow) comparisonHolder.addView(RunComparisonCard.build(this, comparison))
+            }
+        }
         column.add(Snow.button(this, "Back to ready") { service?.resetRun() }, bottom = 10)
         column.add(Snow.button(this, "Open in logbook", Snow.ButtonKind.SECONDARY) {
             startActivity(Intent(this, MainActivity::class.java))
```

`SessionDetailActivity.kt.patch` (a "Run" tab, first and selected, only when the session has run data):

```diff
diff --git a/android/app/src/main/java/com/openski/android/SessionDetailActivity.kt b/android/app/src/main/java/com/openski/android/SessionDetailActivity.kt
index 63bcd7b..1c71eed 100644
--- a/android/app/src/main/java/com/openski/android/SessionDetailActivity.kt
+++ b/android/app/src/main/java/com/openski/android/SessionDetailActivity.kt
@@ -114,19 +114,24 @@ class SessionDetailActivity : Activity() {
             summary.addView(SkiUi.label(this,"Left boot / right boot · Experimental indoor detector",12f,SkiUi.SECONDARY))
             analysisPage.addView(summary,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
         }
-        val pages=listOf(replayPage,analysisPage,detailsPage)
+        val runPage=if(loaded.run!=null) reviewPage() else null
+        runPage?.let { page ->
+            loaded.run?.let { page.addView(RunComparisonCard.build(this,RunComparisons.build(it,session.origin=="synthetic")),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) }) }
+        }
+        val pages=listOfNotNull(runPage,replayPage,analysisPage,detailsPage)
+        val replayIndex=if(runPage!=null) 1 else 0
         val tabRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(16),0,dp(16),dp(8)) }
         val scrolls=pages.map { content -> ScrollView(this).apply { isFillViewport=true; addView(content) } }
         val content=FrameLayout(this)
         scrolls.forEach { content.addView(it,FrameLayout.LayoutParams(-1,-1)) }
         val tabs=mutableListOf<Button>()
         fun selectTab(index: Int) {
-            selectedTab=index.coerceIn(0,2)
+            selectedTab=index.coerceIn(0,pages.size-1)
             scrolls.forEachIndexed { i, view -> view.visibility=if(i==selectedTab) android.view.View.VISIBLE else android.view.View.GONE }
             tabs.forEachIndexed { i, view -> view.isSelected=i==selectedTab; SkiUi.styleButton(view,if(i==selectedTab) SkiUi.ButtonStyle.PRIMARY else SkiUi.ButtonStyle.QUIET) }
-            if(selectedTab!=0) video?.pause()
+            if(selectedTab!=replayIndex) video?.pause()
         }
-        listOf("Replay","Analysis","Details").forEachIndexed { index, title ->
+        (if(runPage!=null) listOf("Run","Replay","Analysis","Details") else listOf("Replay","Analysis","Details")).forEachIndexed { index, title ->
             val tab=button(title) { selectTab(index) }
             tabs.add(tab); tabRow.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
         }
```

Expected: each applies with no output; `git diff --stat` lists only the three files and the test file.

- [ ] **Step 4: Build, test and lint**

Run (from `android/`): `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, no new warnings from the touched files, `RunComparisonTest` `tests="21"` and the earlier suites unchanged (`RunControllerTest` 31, `CoachTest` 18, `ToneSynthTest` 11).

- [ ] **Step 5: Check it on the emulator with a demo run**

A Pixel 9a emulator exists on the dev machine (`emulator -avd Pixel_9a -no-window -no-audio`, then `./gradlew.bat :app:installDebug`). Set the coach preference `phone_speaker` to true (so a demo run does not need earbuds), launch the app, tap Start a run, Try with demo boots, wait about 40 s, tap Stop run. Expected: on the Done screen a "How your turns compared" card with a sentence, four number lines, a row of window tiles opening on the first off-target window, a chart with a solid blue line and a pale target line, and a Play button. Open the run in the logbook: expect a "Run" tab first and selected showing the same card, then Replay, Analysis and Details. Expect no crash. If no emulator is available, record that this step was not run.

- [ ] **Step 6: Commit**

```bash
git add android/app/src
git commit -m "$(cat <<'EOF'
Show the target and recorded waves on the Done screen and in a logbook Run tab

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 3: Docs, the device checklist and final verification

**Files:**
- Modify: `docs/android-acceptance.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add the checks to `docs/android-acceptance.md`**

Append this section (use the Edit tool, or Python with `newline=''`; the file uses LF):

```markdown

## Run comparison checks

Observed on the Pixel 9a emulator with a demo run (8 October 2026): the card on the Done screen (sentence, number lines, window tiles opening on the first off-target window, solid recorded line against the pale target line, Play) and the logbook "Run" tab first and selected with the same card and no crash. The rest needs real runs.

1. After a real run, the sentence and numbers are believable against how it felt: depth and beat averages close to what the coaching reported, and the first off-target window is one you remember being off.
2. Tap each window tile on a long run (more than 20 windows): the tiles row scrolls, the chart changes, and Play animates the selected window.
3. Do a run that leans to the skier's left first on each boot (swap the boots between legs if practical): the recorded line should start on the same side on both boots, since the right boot is mirrored.
4. Open a run in the logbook straight after it ends, before the boots' data is saved, and again after saving. Expect the Run tab and no crash. Open an ordinary recording: expect the old three tabs only.
5. Check a run where a boot dropped its link for 30 s: the window around the gap shows flat stretches at zero, and the sentence still reads sensibly.
6. Tune the sentence thresholds (2°, 0.15 s, 20%) if "close" or "uneven" feels wrong on real runs.
```

- [ ] **Step 2: Update `CLAUDE.md`**

After the **Runs** bullet in the Android section, add (use the Edit tool, or Python with `newline=''`; the file uses LF):

```markdown
  - **Run comparison** (`RunComparison.kt`, `RunComparisonCard.kt`): the target wave against the recorded wave for a run. `RunComparisons.build` turns a run's stored half-turns, verdicts and target into windows (each verdict takes the last `windowSize` half-turns before it), two waves per window, signed differences from the target, left versus right depth and a plain sentence. The recorded wave is reconstructed from the half-turns (a half-sine through each turn's measured peak, start and length), not the measured roll curve, and the card says so; the target is re-anchored at each window. The card reuses `CarvedLineView` and shows on the Done screen and in a logbook "Run" tab. The logic is JVM-tested; the shapes on real runs need the checks in `docs/android-acceptance.md`.
```

- [ ] **Step 3: Run every automated check**

Run (from `android/`): `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`, with the unit test counts from Task 2 Step 4 and all `failures="0"`.

- [ ] **Step 4: Hand over the real-run checks**

The real-run checks cannot be run by an automated agent. In the final report list the six items as not yet run, and say plainly that the reconstructed shapes, the numbers against real skiing and the right boot's side are unverified until a person runs them.

- [ ] **Step 5: Commit**

```bash
git add docs/android-acceptance.md CLAUDE.md
git commit -m "$(cat <<'EOF'
Document run comparison and its real-run checks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

## Self-review notes

- Spec coverage: the two waves, windows, boot choice, numbers and sentence (Task 1); the card, the Done screen and the logbook tab (Task 2); docs and the real-run checks (Task 3). Out-of-scope items (the measured trace, best-run and shared targets, pitch, left-right overlay, export, video sync, next-run suggestions, zoom, the logbook restyle) have no task.
- One detail settled while prototyping and recorded in the spec: the target line is pale, not dashed, because that is how the existing `CarvedLineView` draws its ghost line.
- The card and wiring are verified by compilation, lint and an emulator demo run; real-run shapes need hardware.
