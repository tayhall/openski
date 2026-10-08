# Coaching Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android app plays a metronome and, after every few half-turns, a rising or falling chirp comparing the skier's last turns with a training target, through earbuds with the phone in a pocket.

**Architecture:** Pure Kotlin judgement (`Coach`, `CoachSession`), sound generation (`ToneSynth`, `MetronomeClock`) and settings logic are unit-tested on the JVM. Thin Android shells (`CoachAudio`, `SensorSessionService` additions, `CoachingActivity`) play the sound, keep it alive with the screen off, and show a Snow-look screen. Half-turn events come from the existing `ski_v0` frames, already mirrored into the skier frame.

**Tech Stack:** Native Kotlin Android (no Compose or AndroidX beyond the platform), JUnit 4 unit tests, `AudioTrack`, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-08-coaching-engine-design.md` (approved 8 October 2026, updated after prototyping). Read it first.

**Base:** branch `feat/coaching-engine`, which sits on `feat/ski-v0-recogniser` (PR #8). `SkiEvent`, `SkierFrame` and `SensorSessionService.Listener.onSkiEvent` come from that branch.

## Global Constraints

- Native Kotlin, minSdk 26, compile and target SDK 36, no Compose or AndroidX beyond the platform; hand-built Views.
- New training screens use the light `Snow` look (`Snow.text/card/button`), not the dark `SkiUi` look.
- Experimental framing everywhere: the coach compares dry-ski boot roll with a training target. It never claims on-snow technique, edge angle or carving.
- Target ranges: beat 0.8 to 3.5 s, depth 10° to 45°. Window 3 to 5 half-turns, default 4, non-overlapping.
- Verdict: `POSITIVE` needs a score of at least 70 and every component at least 60; `NEGATIVE` is a score below 40 or any component below 20; otherwise `NONE` (no sound). An event flagged outside the expected range caps the window score at 60.
- Sounds are generated in code: tick about 1.2 kHz and 30 ms (accent about 1.6 kHz), rising chirp about 880 then 1320 Hz, falling chirp about 660 then 440 Hz, about 110 ms per note, peak at most 0.5 of full scale before the user's gain.
- No audio focus is requested. Coaching needs earbuds or a headset unless "allow phone speaker" is on, and pauses with the message "Coaching paused: earbuds disconnected" when the route is lost.
- The foreground service is declared `connectedDevice|mediaPlayback`, with the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission.
- Source files in this repo use LF line endings. On Windows, never rewrite a file with Python text mode (it writes CRLF); use `newline=''` or the Edit tool, and check `git diff --stat` shows only the lines you meant to change.
- Branch work happens on `feat/coaching-engine`; end every commit message with the two lines `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z`.
- Update the relevant docs when behaviour changes (Task 5).

Gradle is run from `android/` (`./gradlew.bat ...` in Git Bash, `.\gradlew.bat ...` in PowerShell). Wait for each run to finish and read its output; "BUILD SUCCESSFUL" and the per-test counts are the evidence.

## Review Focus

Failure modes the spec implies but the happy path does not exercise, most likely first. Each has a pinning test or checklist item in the task named.

1. **A boot restarts mid-window** (its clock and sequence start again). It must reset the window, not look like a two-second beat. Task 1, `aBootRestartMidWindowResetsIt`.
2. **The same event delivered twice** after a Bluetooth reconnect is ignored. Task 1, `theSameEventTwiceIsIgnored`.
3. **Both boots send events at once:** only the coach boot counts, so a turn is never counted twice. Task 3, `automaticFollowsTheFirstBootToSendAnEvent` and `aPinnedBootIgnoresTheOther`.
4. **Earbuds disconnect mid-run:** nothing may come out of the phone speaker. Device only; Task 5 checklist item 4.
5. **Coaching started with no boot connected, or before the service is ready:** a clear message and no false error. Task 4 (message strings in `startCoaching` and `CoachingActivity.start`); Task 5 checklist item 7.

---

### Task 1: The judgement engine

**Files:**
- Modify: `android/app/src/main/java/com/openski/android/Programme.kt` (expose `closeness`, add `steadiness`; behaviour of the drill scoring is unchanged)
- Create: `android/app/src/main/java/com/openski/android/Coach.kt`
- Create: `android/app/src/test/java/com/openski/android/CoachTest.kt`

**Interfaces:**
- Produces (used by Tasks 3 and 4): `CoachTarget(beatSeconds: Double, depthDegrees: Double)` with `CoachTarget.of(drill: Drill)`, `CoachTarget.custom(beat, depth)` and the range constants; `enum Verdict { POSITIVE, NEGATIVE, NONE }`; `CoachVerdict(verdict, score, tempo, steadiness, depth, balance: Int?, meanBeatSeconds, meanDepthDegrees, outsideEnvelope)`; `CoachState(running, paused, demo, target, last, message)`; `class Coach(target: CoachTarget, windowSize: Int = 4)` with `onEvent(event: SkiEvent): CoachVerdict?` and `reset()`; `Coach.DEFAULT_WINDOW/MIN_WINDOW/MAX_WINDOW`; `bootClockDelta(fromMs: Long, toMs: Long): Long`; `DrillScoring.closeness(value, ideal, tolerance)` and `DrillScoring.steadiness(beats: List<Double>)` (both `internal`).
- Consumes: `SkiEvent` (fields `sequence`, `startMs`, `peakRollDegrees`, `positive`, `outsideEnvelope`) from `SkiEventProtocol.kt`; `Programme.drill(id)`.

The code below was written and run in a scratch copy before this plan was finalised: 17 `CoachTest` cases pass, the existing `ProgrammeTest` still passes, and removing the weakest-component floors makes four cases fail. Use it as written.

- [ ] **Step 1: Make the drill scoring helpers reusable**

Save this patch to `.pio/host/programme.patch` (create the folder if needed), then apply it from the repo root:

```diff
diff --git a/android/app/src/main/java/com/openski/android/Programme.kt b/android/app/src/main/java/com/openski/android/Programme.kt
index 2fb72ce..9c9d293 100644
--- a/android/app/src/main/java/com/openski/android/Programme.kt
+++ b/android/app/src/main/java/com/openski/android/Programme.kt
@@ -86,9 +86,19 @@ object DrillScoring {
     const val GREAT = 90
 
     /** Maps [value] from a perfect-at-[ideal], zero-at-[ideal]±[tolerance] triangle onto 0..100. */
-    private fun closeness(value: Double, ideal: Double, tolerance: Double) =
+    internal fun closeness(value: Double, ideal: Double, tolerance: Double) =
         (100 * (1 - abs(value - ideal) / tolerance)).coerceIn(0.0, 100.0)
 
+    /** 100 for perfectly even beats, falling to zero when the spread reaches 35% of the mean beat. */
+    internal fun steadiness(beats: List<Double>): Double {
+        val mean = beats.average()
+        val spread = if (beats.size < 2) 1.0 else {
+            val variance = beats.sumOf { (it - mean) * (it - mean) } / (beats.size - 1)
+            kotlin.math.sqrt(variance) / mean
+        }
+        return (100 * (1 - spread / 0.35)).coerceIn(0.0, 100.0)
+    }
+
     fun score(drill: Drill, detection: DryDetection): DrillResult {
         val moves = detection.movements.take(drill.movements)
         if (moves.isEmpty()) return DrillResult(0, 0, 0, 0, 0, 0, 0,
@@ -100,11 +110,7 @@ object DrillScoring {
         val mean = beats.average()
         // 50% either side of the target beat scores zero.
         val tempo = closeness(mean, drill.paceSeconds, drill.paceSeconds * 0.5)
-        val spread = if (beats.size < 2) 1.0 else {
-            val variance = beats.sumOf { (it - mean) * (it - mean) } / (beats.size - 1)
-            kotlin.math.sqrt(variance) / mean
-        }
-        val steadiness = (100 * (1 - spread / 0.35)).coerceIn(0.0, 100.0)
+        val steadiness = steadiness(beats)
         val balance = DrySkiAnalysis.balance(DryDetection(moves, emptyList()))?.let {
             (it.durationRatioPercent + it.peakRatioPercent) / 2
         } ?: 50.0
```

Run: `git apply --check .pio/host/programme.patch && git apply .pio/host/programme.patch`
Expected: no output. Then `git diff --stat` shows only `Programme.kt` with about 12 insertions and 6 deletions.

- [ ] **Step 2: Confirm the refactor changed no drill behaviour**

Run (from `android/`): `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.ProgrammeTest"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Write the failing test**

Create `android/app/src/test/java/com/openski/android/CoachTest.kt`:

```kotlin
package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class CoachTest {
    private val target = CoachTarget(beatSeconds = 2.0, depthDegrees = 20.0)

    private fun event(sequence: Int, startMs: Long, positive: Boolean, peak: Double = 20.0, outside: Boolean = false) =
        SkiEvent(sequence, startMs, 1800, (if (positive) peak else -peak).toFloat(), 100f, 15f,
            positive, outside, false, false)

    /** Alternating half-turns whose starts are [startsMs] apart, peaks given per event; returns the last verdict. */
    private fun feed(coach: Coach, starts: List<Long>, peaks: List<Double>, firstSequence: Int = 1): List<CoachVerdict?> =
        starts.indices.map { i -> coach.onEvent(event(firstSequence + i, starts[i], i % 2 == 0, peaks[i])) }

    private fun even(count: Int, beatMs: Long = 2000, from: Long = 10_000) = List(count) { from + it * beatMs }

    @Test fun onTargetWindowGetsARisingChirp() {
        val results = feed(Coach(target), even(4), List(4) { 20.0 })
        assertNull(results[0]); assertNull(results[1]); assertNull(results[2])
        val verdict = results[3]!!
        assertEquals(Verdict.POSITIVE, verdict.verdict)
        assertTrue(verdict.score >= 90)
        assertEquals(2.0, verdict.meanBeatSeconds, 0.001)
        assertEquals(20.0, verdict.meanDepthDegrees, 0.001)
        assertEquals(100, verdict.balance)
    }

    @Test fun slightlyOffIsStillPositive() {
        val verdict = feed(Coach(target), even(4, 2200), List(4) { 18.0 })[3]!!
        assertEquals(Verdict.POSITIVE, verdict.verdict)
    }

    @Test fun shallowRollsGetAFallingChirpEvenWhenEverythingElseIsPerfect() {
        // Depth scores zero, but the other three parts average high: the weakest part must not hide.
        val verdict = feed(Coach(target), even(4), List(4) { 8.0 })[3]!!
        assertEquals(0, verdict.depth)
        assertEquals(Verdict.NEGATIVE, verdict.verdict)
    }

    @Test fun tooFastAndTooSlowAreNegative() {
        assertEquals(Verdict.NEGATIVE, feed(Coach(target), even(4, 1000), List(4) { 20.0 })[3]!!.verdict)
        assertEquals(Verdict.NEGATIVE, feed(Coach(target), even(4, 3300), List(4) { 20.0 })[3]!!.verdict)
    }

    @Test fun unevenBeatsAreNegative() {
        val starts = listOf(0L, 1200L, 4000L, 5200L)  // beats 1.2, 2.8, 1.2: right on average, nothing like steady
        val verdict = feed(Coach(target), starts, List(4) { 20.0 })[3]!!
        assertEquals(0, verdict.steadiness)
        assertEquals(Verdict.NEGATIVE, verdict.verdict)
    }

    @Test fun lopsidedRollsDropTheBalanceScore() {
        val verdict = feed(Coach(target), even(4), listOf(26.0, 10.0, 26.0, 10.0))[3]!!
        assertEquals(38, verdict.balance)
        assertNotEquals(Verdict.POSITIVE, verdict.verdict)
    }

    @Test fun anOutOfRangeHalfTurnCannotEarnAPositiveChirp() {
        val coach = Coach(target)
        val verdicts = (0 until 4).map { coach.onEvent(event(it + 1, 10_000L + it * 2000, it % 2 == 0, 20.0, outside = it == 2)) }
        val verdict = verdicts[3]!!
        assertTrue(verdict.outsideEnvelope)
        assertTrue(verdict.score <= 60)
        assertEquals(Verdict.NONE, verdict.verdict)
    }

    @Test fun aPauseResetsTheWindowWithoutAVerdict() {
        val coach = Coach(target)
        val starts = listOf(0L, 2000L, 4000L, 10_000L, 12_000L, 14_000L, 16_000L)  // 6 s gap before the fourth
        val results = feed(coach, starts, List(7) { 20.0 })
        assertEquals(listOf(false, false, false, false, false, false, true), results.map { it != null })
    }

    @Test fun aMissedHalfTurnResetsTheWindow() {
        val coach = Coach(target)
        val sides = listOf(true, false, false, true, false, true)  // the third event repeats the second's side
        val results = sides.indices.map { i -> coach.onEvent(event(i + 1, 10_000L + i * 2000, sides[i])) }
        assertEquals(listOf(false, false, false, false, false, true), results.map { it != null })
    }

    @Test fun aBootRestartMidWindowResetsIt() {
        // The boot reboots: its clock and sequence start again from near zero. That must not look like a turn.
        val coach = Coach(target)
        coach.onEvent(event(5, 110_000, true))
        coach.onEvent(event(6, 112_000, false))
        coach.onEvent(event(1, 500, true))     // clock far behind the previous event: a restart, not a 2 s beat
        coach.onEvent(event(2, 2500, false))
        coach.onEvent(event(3, 4500, true))
        assertNotNull(coach.onEvent(event(4, 6500, false)))   // a full fresh window of four, none left over
    }

    @Test fun theSameEventTwiceIsIgnored() {
        val coach = Coach(target)
        coach.onEvent(event(1, 10_000, true))
        assertNull(coach.onEvent(event(1, 10_000, true)))
        coach.onEvent(event(2, 12_000, false))
        coach.onEvent(event(3, 14_000, true))
        assertNotNull(coach.onEvent(event(4, 16_000, false)))
    }

    @Test fun theBootClockWrappingMidWindowStillGivesTwoSecondBeats() {
        val starts = listOf(4_292_967L, 0L, 2000L, 4000L)  // wraps at 4,294,967 ms
        val verdict = feed(Coach(target), starts, List(4) { 20.0 })[3]!!
        assertEquals(2.0, verdict.meanBeatSeconds, 0.001)
        assertEquals(Verdict.POSITIVE, verdict.verdict)
    }

    @Test fun windowSizesThreeAndFiveAreHonouredAndOthersAreClamped() {
        assertNotNull(feed(Coach(target, 3), even(3), List(3) { 20.0 })[2])
        val five = feed(Coach(target, 5), even(5), List(5) { 20.0 })
        assertNull(five[3]); assertNotNull(five[4])
        assertNotNull(feed(Coach(target, 1), even(3), List(3) { 20.0 })[2])   // 1 clamps to 3
        assertNull(feed(Coach(target, 9), even(5), List(5) { 20.0 })[3])     // 9 clamps to 5
    }

    @Test fun windowsDoNotOverlap() {
        val results = feed(Coach(target), even(8), List(8) { 20.0 })
        assertEquals(listOf(3, 7), results.indices.filter { results[it] != null })
    }

    @Test fun resetForgetsEverything() {
        val coach = Coach(target)
        feed(coach, even(3), List(3) { 20.0 })
        coach.reset()
        assertNull(coach.onEvent(event(4, 16_000, false)))
    }

    @Test fun bootClockDeltaIsForwardAndWrapAware() {
        assertEquals(2000L, bootClockDelta(1000, 3000))
        assertEquals(2000L, bootClockDelta(BOOT_CLOCK_WRAP_MS - 1000, 1000))
    }

    @Test fun targetsComeFromDrillsAndCustomValuesAreClamped() {
        val drill = Programme.drill("short-turn-set")!!
        assertEquals(CoachTarget(0.9, 25.0), CoachTarget.of(drill))
        assertEquals(CoachTarget(0.8, 45.0), CoachTarget.custom(0.1, 90.0))
        assertEquals(CoachTarget(3.5, 10.0), CoachTarget.custom(9.0, 1.0))
    }
}
```

- [ ] **Step 4: Run it to confirm it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.CoachTest"`
Expected: FAIL to compile with `Unresolved reference: Coach` (and `CoachTarget`).

- [ ] **Step 5: Write the implementation**

Create `android/app/src/main/java/com/openski/android/Coach.kt`:

```kotlin
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
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.CoachTest" --tests "com.openski.android.ProgrammeTest"`
Expected: `BUILD SUCCESSFUL`. Check `app/build/test-results/testDebugUnitTest/TEST-com.openski.android.CoachTest.xml` reports `tests="17"` and `failures="0"`.

- [ ] **Step 7: Prove the weakest-component rule is tested**

Temporarily change `NEGATIVE_FLOOR = 20.0` to `-1.0` and `POSITIVE_FLOOR = 60.0` to `0.0` in `Coach.kt`, rerun the `CoachTest` command, and expect FAIL in `shallowRollsGetAFallingChirpEvenWhenEverythingElseIsPerfect` (and others). Then restore the two constants and rerun to see it pass again. Do not commit the temporary change.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/openski/android/Programme.kt android/app/src/main/java/com/openski/android/Coach.kt android/app/src/test/java/com/openski/android/CoachTest.kt
git commit -m "$(cat <<'EOF'
Add the coaching engine that judges windows of half-turns

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 2: The sounds

**Files:**
- Create: `android/app/src/main/java/com/openski/android/ToneSynth.kt`
- Create: `android/app/src/test/java/com/openski/android/ToneSynthTest.kt`

**Interfaces:**
- Produces (used by Task 4): `ToneSynth.SAMPLE_RATE = 44100`, `ToneSynth.PEAK = 0.5`, `ToneSynth.tone(frequencyHz, millis, fadeMillis = 8, sampleRate): ShortArray`, `ToneSynth.tick(accent: Boolean)`, `ToneSynth.chirpPositive()`, `ToneSynth.chirpNegative()`, `ToneSynth.concat(vararg ShortArray)`; `class MetronomeClock(beatSeconds: Double, sampleRate: Int = 44100)` with `nextBeat(): ShortArray`.
- Consumes: `CoachTarget.MIN_BEAT` (Task 1) in one test.

Ten `ToneSynthTest` cases pass in the prototype.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/openski/android/ToneSynthTest.kt`:

```kotlin
package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {
    private fun crossings(samples: ShortArray, from: Int = 0, to: Int = samples.size): Int {
        var count = 0
        for (i in from + 1 until to) if ((samples[i - 1] < 0) != (samples[i] < 0)) count++
        return count
    }

    @Test fun toneHasTheRightLengthAndFrequency() {
        val tone = ToneSynth.tone(1000.0, 100)
        assertEquals(4410, tone.size)
        assertEquals(200.0, crossings(tone).toDouble(), 4.0)  // two zero crossings per cycle, 100 cycles
    }

    @Test fun toneFadesInAndOutSoItNeverClicks() {
        val tone = ToneSynth.tone(1000.0, 100)
        assertTrue(abs(tone.first().toInt()) < 400)
        assertTrue(abs(tone.last().toInt()) < 400)
    }

    @Test fun peakStaysUnderTheCap() {
        val peak = ToneSynth.chirpPositive().maxOf { abs(it.toInt()) }
        assertTrue(peak <= (ToneSynth.PEAK * Short.MAX_VALUE).toInt() + 1)
        assertTrue(peak > 0.4 * Short.MAX_VALUE)
    }

    @Test fun positiveChirpRisesAndNegativeChirpFalls() {
        val up = ToneSynth.chirpPositive()
        val down = ToneSynth.chirpNegative()
        assertEquals(up.size, 2 * 4851)
        val half = up.size / 2
        assertTrue(crossings(up, half, up.size) > crossings(up, 0, half))
        assertTrue(crossings(down, half, down.size) < crossings(down, 0, half))
    }

    @Test fun theTwoChirpsDifferInRegisterAsWellAsDirection() {
        val up = ToneSynth.chirpPositive()
        val down = ToneSynth.chirpNegative()
        assertTrue(crossings(up) > crossings(down))
    }

    @Test fun accentTickIsHigherThanThePlainTick() {
        assertTrue(crossings(ToneSynth.tick(true)) > crossings(ToneSynth.tick(false)))
    }

    @Test fun metronomeBeatStartsWithTheTickAndThenIsSilent() {
        val beat = MetronomeClock(1.0).nextBeat()
        assertEquals(44100, beat.size)
        assertTrue((0 until 1323).any { beat[it].toInt() != 0 })
        assertTrue((1323 until beat.size).all { beat[it].toInt() == 0 })
    }

    @Test fun metronomeAccentsEverySecondBeat() {
        val clock = MetronomeClock(1.0)
        val first = clock.nextBeat()
        val second = clock.nextBeat()
        val third = clock.nextBeat()
        assertTrue(crossings(first, 0, 1323) > crossings(second, 0, 1323))
        assertEquals(crossings(first, 0, 1323), crossings(third, 0, 1323))
    }

    @Test fun metronomeDoesNotDriftOverHundredBeats() {
        val clock = MetronomeClock(1.7)
        val total = (1..100).sumOf { clock.nextBeat().size.toLong() }
        assertEquals(170.0 * ToneSynth.SAMPLE_RATE, total.toDouble(), 1.0)
    }

    @Test fun theShortestBeatStillFitsItsTick() {
        val beat = MetronomeClock(CoachTarget.MIN_BEAT).nextBeat()
        assertEquals(0.8 * ToneSynth.SAMPLE_RATE, beat.size.toDouble(), 1.0)
        assertTrue(beat.any { it.toInt() != 0 })
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run (from `android/`): `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.ToneSynthTest"`
Expected: FAIL to compile with `Unresolved reference: ToneSynth`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/openski/android/ToneSynth.kt`:

```kotlin
package com.openski.android

import kotlin.math.PI
import kotlin.math.sin

/** Generates the coaching sounds as 16-bit mono PCM, so no audio files ship with the app. */
object ToneSynth {
    const val SAMPLE_RATE = 44100
    /** Loudest sample as a fraction of full scale, before the user's gain. */
    const val PEAK = 0.5

    /** A sine burst with a linear fade in and out so it never clicks. */
    fun tone(frequencyHz: Double, millis: Int, fadeMillis: Int = 8, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val count = sampleRate * millis / 1000
        val fade = (sampleRate * fadeMillis / 1000).coerceAtLeast(1)
        return ShortArray(count) { i ->
            val envelope = minOf(1.0, i.toDouble() / fade, (count - 1 - i).toDouble() / fade)
            (sin(2 * PI * frequencyHz * i / sampleRate) * envelope * PEAK * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) { part.copyInto(out, offset); offset += part.size }
        return out
    }

    /** The metronome click. The accent, on every second beat, is higher so left and right feel different. */
    fun tick(accent: Boolean): ShortArray = tone(if (accent) 1600.0 else 1200.0, 30, fadeMillis = 3)

    /** Two rising notes: the last few turns matched the target. */
    fun chirpPositive(): ShortArray = concat(tone(880.0, 110, 10), tone(1320.0, 110, 10))

    /** Two falling notes in a lower register: the last few turns did not. */
    fun chirpNegative(): ShortArray = concat(tone(660.0, 110, 10), tone(440.0, 110, 10))
}

/**
 * Produces the metronome one beat at a time. Each beat is a whole number of samples, and the fraction left over is
 * carried into the next beat, so the tempo does not drift however long the metronome runs.
 */
class MetronomeClock(private val beatSeconds: Double, private val sampleRate: Int = ToneSynth.SAMPLE_RATE) {
    private var carried = 0.0
    private var beatIndex = 0L

    fun nextBeat(): ShortArray {
        val exact = beatSeconds * sampleRate + carried
        val length = exact.toInt()
        carried = exact - length
        val accent = beatIndex % 2L == 0L
        beatIndex++
        val beat = ShortArray(length)
        val tick = ToneSynth.tick(accent)
        tick.copyInto(beat, 0, 0, minOf(tick.size, length))
        return beat
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.ToneSynthTest"`
Expected: `BUILD SUCCESSFUL`; `TEST-com.openski.android.ToneSynthTest.xml` reports `tests="10"` and `failures="0"`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/openski/android/ToneSynth.kt android/app/src/test/java/com/openski/android/ToneSynthTest.kt
git commit -m "$(cat <<'EOF'
Add the coaching tone generator and drift-free metronome clock

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 3: Settings, session and demo feed

**Files:**
- Create: `android/app/src/main/java/com/openski/android/CoachSettings.kt`
- Create: `android/app/src/main/java/com/openski/android/CoachSession.kt`
- Create: `android/app/src/main/java/com/openski/android/DemoCoachFeed.kt`
- Create: `android/app/src/test/java/com/openski/android/CoachSessionTest.kt`

**Interfaces:**
- Produces (used by Task 4): `enum CoachBoot { AUTO, LEFT, RIGHT }`; `data class CoachSettings(metronome, countIn, chirps, windowSize, coachBoot, presetDrillId: String?, customBeat, customDepth, gainPercent, allowPhoneSpeaker)` with `normalised()`, `target(): CoachTarget`, `targetLabel(): String`; `class CoachSettingsStore(context)` with `var settings`; `class CoachSession(settings, onVerdict: (side: String, CoachVerdict) -> Unit)` with `onEvent(side, event)` and `bootInUse()`; `data class DemoStep(event: SkiEvent, afterMs: Long)`; `class DemoCoachFeed(target: CoachTarget, blockSize: Int = 8)` with `next(): DemoStep`.
- Consumes: Task 1 (`Coach`, `CoachTarget`, `CoachVerdict`, `Verdict`); `Programme.drill`; `SkiEvent`.

Seven `CoachSessionTest` cases pass in the prototype. The `CoachSettingsStore` (SharedPreferences) is not unit-tested; it only maps fields to keys and applies `normalised()`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/openski/android/CoachSessionTest.kt`:

```kotlin
package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class CoachSessionTest {
    private fun event(sequence: Int, startMs: Long, positive: Boolean) =
        SkiEvent(sequence, startMs, 1800, (if (positive) 20f else -20f), 100f, 15f, positive, false, false, false)

    private fun run(session: CoachSession, side: String, count: Int, from: Int = 0) =
        repeat(count) { session.onEvent(side, event(from + it + 1, 10_000L + (from + it) * 2000L, (from + it) % 2 == 0)) }

    private val settings = CoachSettings(presetDrillId = null, customBeat = 2.0, customDepth = 20.0)

    @Test fun automaticFollowsTheFirstBootToSendAnEvent() {
        val verdicts = mutableListOf<Pair<String, CoachVerdict>>()
        val session = CoachSession(settings) { side, verdict -> verdicts.add(side to verdict) }
        assertNull(session.bootInUse())
        session.onEvent("R", event(1, 10_000, true))
        assertEquals("R", session.bootInUse())
        session.onEvent("L", event(1, 10_050, true))   // the other boot is ignored
        run(session, "R", 3, from = 1)
        assertEquals(1, verdicts.size)
        assertEquals("R", verdicts[0].first)
        assertEquals(Verdict.POSITIVE, verdicts[0].second.verdict)
    }

    @Test fun aPinnedBootIgnoresTheOther() {
        val verdicts = mutableListOf<String>()
        val session = CoachSession(settings.copy(coachBoot = CoachBoot.LEFT)) { side, _ -> verdicts.add(side) }
        assertEquals("L", session.bootInUse())
        run(session, "R", 8)
        assertTrue(verdicts.isEmpty())
        run(session, "L", 4)
        assertEquals(listOf("L"), verdicts)
    }

    @Test fun theWindowSizeSettingIsUsed() {
        var count = 0
        val session = CoachSession(settings.copy(windowSize = 3)) { _, _ -> count++ }
        run(session, "L", 6)
        assertEquals(2, count)
    }

    @Test fun demoFeedProducesBothChirpsInOrder() {
        val target = CoachTarget(2.0, 20.0)
        val feed = DemoCoachFeed(target)
        val coach = Coach(target, 4)
        val verdicts = (1..16).mapNotNull { coach.onEvent(feed.next().event)?.verdict }
        assertEquals(listOf(Verdict.POSITIVE, Verdict.POSITIVE, Verdict.NEGATIVE, Verdict.NEGATIVE), verdicts)
    }

    @Test fun demoFeedDeliversAtTheTargetBeat() {
        val feed = DemoCoachFeed(CoachTarget(2.0, 20.0))
        val gaps = (1..8).map { feed.next().afterMs }
        assertTrue(gaps.all { it in 1900L..2150L })
    }

    @Test fun settingsDefaultsAndClamping() {
        val defaults = CoachSettings()
        assertEquals(4, defaults.windowSize)
        assertEquals(CoachTarget(2.0, 20.0), defaults.target())   // the steady-rhythm preset
        val clamped = CoachSettings(windowSize = 9, customBeat = 0.1, customDepth = 99.0, gainPercent = 250).normalised()
        assertEquals(5, clamped.windowSize)
        assertEquals(0.8, clamped.customBeat, 0.0)
        assertEquals(45.0, clamped.customDepth, 0.0)
        assertEquals(100, clamped.gainPercent)
        assertEquals(3, CoachSettings(windowSize = 0).normalised().windowSize)
    }

    @Test fun anUnknownPresetFallsBackToTheCustomNumbers() {
        val settings = CoachSettings(presetDrillId = "no-such-drill", customBeat = 1.5, customDepth = 30.0)
        assertEquals(CoachTarget(1.5, 30.0), settings.target())
        assertTrue(settings.targetLabel().startsWith("Custom"))
        assertTrue(CoachSettings().targetLabel().startsWith("Steady rhythm"))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run (from `android/`): `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.CoachSessionTest"`
Expected: FAIL to compile with `Unresolved reference: CoachSession` (and `CoachSettings`, `DemoCoachFeed`).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/openski/android/CoachSettings.kt`:

```kotlin
package com.openski.android

import android.content.Context

enum class CoachBoot { AUTO, LEFT, RIGHT }

/** Everything the skier can change about coaching. [normalised] clamps values that came from storage or a text box. */
data class CoachSettings(
    val metronome: Boolean = true,
    val countIn: Boolean = true,
    val chirps: Boolean = true,
    val windowSize: Int = Coach.DEFAULT_WINDOW,
    val coachBoot: CoachBoot = CoachBoot.AUTO,
    /** A drill id from [Programme], or null to use the custom numbers. */
    val presetDrillId: String? = "steady-rhythm",
    val customBeat: Double = 2.0,
    val customDepth: Double = 20.0,
    val gainPercent: Int = 60,
    val allowPhoneSpeaker: Boolean = false,
) {
    fun normalised() = copy(
        windowSize = windowSize.coerceIn(Coach.MIN_WINDOW, Coach.MAX_WINDOW),
        customBeat = customBeat.coerceIn(CoachTarget.MIN_BEAT, CoachTarget.MAX_BEAT),
        customDepth = customDepth.coerceIn(CoachTarget.MIN_DEPTH, CoachTarget.MAX_DEPTH),
        gainPercent = gainPercent.coerceIn(0, 100),
    )

    /** The preset drill's pace and depth, or the custom numbers if there is no such drill. */
    fun target(): CoachTarget {
        val drill = presetDrillId?.let { Programme.drill(it) }
        return if (drill != null) CoachTarget.of(drill) else CoachTarget.custom(customBeat, customDepth)
    }

    /** One line saying what the skier is aiming for. */
    fun targetLabel(): String {
        val drill = presetDrillId?.let { Programme.drill(it) }
        val target = target()
        val numbers = "%.1f s beat, %d° roll".format(java.util.Locale.US, target.beatSeconds, target.depthDegrees.toInt())
        return if (drill != null) "${drill.title} · $numbers" else "Custom · $numbers"
    }
}

class CoachSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("openski-coach", Context.MODE_PRIVATE)

    var settings: CoachSettings
        get() {
            val defaults = CoachSettings()
            return CoachSettings(
                metronome = prefs.getBoolean("metronome", defaults.metronome),
                countIn = prefs.getBoolean("count_in", defaults.countIn),
                chirps = prefs.getBoolean("chirps", defaults.chirps),
                windowSize = prefs.getInt("window", defaults.windowSize),
                coachBoot = CoachBoot.entries.firstOrNull { it.name == prefs.getString("boot", null) } ?: defaults.coachBoot,
                presetDrillId = if (prefs.contains("preset")) prefs.getString("preset", null) else defaults.presetDrillId,
                customBeat = prefs.getFloat("custom_beat", defaults.customBeat.toFloat()).toDouble(),
                customDepth = prefs.getFloat("custom_depth", defaults.customDepth.toFloat()).toDouble(),
                gainPercent = prefs.getInt("gain", defaults.gainPercent),
                allowPhoneSpeaker = prefs.getBoolean("phone_speaker", defaults.allowPhoneSpeaker),
            ).normalised()
        }
        set(value) {
            val v = value.normalised()
            prefs.edit()
                .putBoolean("metronome", v.metronome).putBoolean("count_in", v.countIn).putBoolean("chirps", v.chirps)
                .putInt("window", v.windowSize).putString("boot", v.coachBoot.name).putString("preset", v.presetDrillId)
                .putFloat("custom_beat", v.customBeat.toFloat()).putFloat("custom_depth", v.customDepth.toFloat())
                .putInt("gain", v.gainPercent).putBoolean("phone_speaker", v.allowPhoneSpeaker)
                .apply()
        }
}
```

Create `android/app/src/main/java/com/openski/android/CoachSession.kt`:

```kotlin
package com.openski.android

/**
 * One coaching run: picks the coach boot, feeds its half-turns to a [Coach], and reports each verdict.
 * Pure logic, so it is testable without a phone. Events arrive already mirrored into the skier frame.
 */
class CoachSession(settings: CoachSettings, private val onVerdict: (side: String, verdict: CoachVerdict) -> Unit) {
    private val coach = Coach(settings.target(), settings.windowSize)
    private var boot: String? = when (settings.coachBoot) {
        CoachBoot.LEFT -> "L"
        CoachBoot.RIGHT -> "R"
        CoachBoot.AUTO -> null
    }

    /** The boot being followed, or null until the first event when the setting is automatic. */
    fun bootInUse(): String? = boot

    fun onEvent(side: String, event: SkiEvent) {
        if (boot == null) boot = side  // automatic: follow whichever boot sends a half-turn first
        if (side != boot) return
        coach.onEvent(event)?.let { onVerdict(side, it) }
    }
}
```

Create `android/app/src/main/java/com/openski/android/DemoCoachFeed.kt`:

```kotlin
package com.openski.android

/** One synthetic half-turn and how long after the previous one it should be delivered, in milliseconds. */
data class DemoStep(val event: SkiEvent, val afterMs: Long)

/**
 * Synthetic half-turns for demo boots, so the metronome and chirps can be heard without hardware.
 * Blocks of [blockSize] turns alternate between matching the target and drifting off it (shallow and slow),
 * so both chirps are heard.
 */
class DemoCoachFeed(private val target: CoachTarget, private val blockSize: Int = 8) {
    private var n = 0
    private var clockMs = 100_000L

    fun next(): DemoStep {
        val onTarget = (n / blockSize) % 2 == 0
        val wobble = WOBBLE[n % WOBBLE.size]
        val beatMs = (target.beatSeconds * 1000 * (if (onTarget) 1.0 + wobble / 40 else 1.5)).toLong()
        val depth = target.depthDegrees * (if (onTarget) 1.0 + wobble / 30 else 0.65)
        clockMs += beatMs
        val positive = n % 2 == 0
        val event = SkiEvent(
            sequence = n + 1,
            startMs = clockMs,
            durationMs = (beatMs * 0.9).toInt(),
            peakRollDegrees = (if (positive) depth else -depth).toFloat(),
            peakRateDps = (depth * 3.14159 / (beatMs / 1000.0)).toFloat(),
            pitchDegrees = 15f,
            positive = positive,
            outsideEnvelope = false,
            pitchOutside = false,
            skippedSamples = false,
        )
        n++
        return DemoStep(event, beatMs)
    }

    private companion object {
        val WOBBLE = doubleArrayOf(0.0, 1.5, -1.0, 0.5)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.CoachSessionTest" --tests "com.openski.android.CoachTest"`
Expected: `BUILD SUCCESSFUL`; `TEST-com.openski.android.CoachSessionTest.xml` reports `tests="7"` and `failures="0"`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/openski/android/CoachSettings.kt android/app/src/main/java/com/openski/android/CoachSession.kt android/app/src/main/java/com/openski/android/DemoCoachFeed.kt android/app/src/test/java/com/openski/android/CoachSessionTest.kt
git commit -m "$(cat <<'EOF'
Add coaching settings, the session that follows one boot, and a demo feed

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 4: The Android shell: audio, service, screen

**Files:**
- Create: `android/app/src/main/java/com/openski/android/CoachAudio.kt`
- Create: `android/app/src/main/java/com/openski/android/CoachingActivity.kt`
- Modify: `android/app/src/main/java/com/openski/android/SensorSessionService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/openski/android/HomeActivity.kt`

**Interfaces:**
- Consumes: Tasks 1 to 3; `SkierFrame.of`, `SensorSessionService.startMonitoring/ensureForeground/settleForeground/acquireWakeLock`, `Snow` helpers, `SkiUi.applyInsets`.
- Produces: `SensorSessionService.coachState(): CoachState`, `setCoachListener((CoachState) -> Unit)?`, `startCoaching(settings, demo): String?` (null means started, otherwise the reason), `stopCoaching()`, `playCoachTestSounds(settings)`; `CoachAudio(context, onRoute, onProblem)` with `start`, `chirp`, `playTestSounds`, `stop`, `headphonesConnected`; `CoachingActivity`; a "Coaching" card on the Boots tab.

There are no JVM unit tests for this task. The `AudioTrack`, the audio route, foreground-service behaviour and the screen need a phone. The proof here is that everything compiles, the existing and new JVM tests still pass, lint is clean, and the device checklist in Task 5 is run. Say so in the final report; do not claim the sound works until a person has heard it.

The code compiled in a scratch copy with the full build, unit tests, lint and `assembleDebugAndroidTest` all green.

- [ ] **Step 1: Create the audio shell**

Create `android/app/src/main/java/com/openski/android/CoachAudio.kt`:

```kotlin
package com.openski.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Plays the coaching sounds. A thin Android shell: the sounds come from [ToneSynth] and the judging from [Coach].
 * No audio focus is requested, so music or a podcast keeps playing and the cues mix over it.
 * Callbacks run on the main thread.
 */
class CoachAudio(
    private val context: Context,
    private val onRoute: (headphones: Boolean) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gain = 0.6f
    @Volatile private var running = false
    @Volatile private var paused = false
    private var allowSpeaker = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var registered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(ToneSynth.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
    }

    /** True when earbuds, a headset or a helmet speaker is connected for output. */
    fun headphonesConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphone(it.type) }

    private fun isHeadphone(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> (Build.VERSION.SDK_INT >= 28 && type == AudioDeviceInfo.TYPE_HEARING_AID) ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun routeOk() = allowSpeaker || headphonesConnected()

    /** Starts the metronome (if enabled) and watches the audio route. Returns a reason if it cannot start. */
    fun start(settings: CoachSettings, target: CoachTarget): String? {
        gain = settings.gainPercent / 100f
        allowSpeaker = settings.allowPhoneSpeaker
        if (!routeOk()) return "Connect earbuds or a helmet speaker first, or allow the phone speaker in the coaching settings."
        running = true
        paused = false
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        registered = true
        if (!settings.metronome) return null
        val minBuffer = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { stop(); return "This phone could not start audio." }
        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, ToneSynth.SAMPLE_RATE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            created.play()
            track = created
            val clock = MetronomeClock(target.beatSeconds)
            thread = Thread({ pump(created, clock) }, "coach-metronome").also { it.start() }
            null
        } catch (error: Exception) {
            stop()
            "Audio could not start: ${error.message ?: "unknown error"}"
        }
    }

    /** Writes the metronome one beat at a time; the blocking write paces it in real time. */
    private fun pump(output: AudioTrack, clock: MetronomeClock) {
        while (running) {
            if (paused) { try { Thread.sleep(50) } catch (_: InterruptedException) { return }; continue }
            val beat = clock.nextBeat()
            val scaled = ShortArray(beat.size) { (beat[it] * gain).toInt().toShort() }
            var offset = 0
            while (running && !paused && offset < scaled.size) {
                val written = output.write(scaled, offset, scaled.size - offset)
                if (written < 0) {
                    running = false
                    main.post { onProblem("Audio stopped unexpectedly.") }
                    return
                }
                offset += written
            }
        }
    }

    /** Plays the section chirp, if the verdict has one. */
    fun chirp(verdict: Verdict) {
        if (!running || paused) return
        val clip = when (verdict) {
            Verdict.POSITIVE -> ToneSynth.chirpPositive()
            Verdict.NEGATIVE -> ToneSynth.chirpNegative()
            Verdict.NONE -> return
        }
        playClip(clip)
    }

    /** Plays a tick, the accent, then both chirps, so the skier can set the volume before starting. */
    fun playTestSounds(gainPercent: Int) {
        gain = gainPercent.coerceIn(0, 100) / 100f
        listOf(0L to ToneSynth.tick(false), 500L to ToneSynth.tick(true), 1200L to ToneSynth.chirpPositive(),
            2200L to ToneSynth.chirpNegative()).forEach { (delay, clip) -> main.postDelayed({ playClip(clip) }, delay) }
    }

    private fun playClip(clip: ShortArray) {
        val scaled = ShortArray(clip.size) { (clip[it] * gain).toInt().toShort() }
        try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(scaled.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            output.write(scaled, 0, scaled.size)
            output.play()
            main.postDelayed({ runCatching { output.stop(); output.release() } }, clip.size * 1000L / ToneSynth.SAMPLE_RATE + 300)
        } catch (error: Exception) {
            onProblem("Could not play a coaching sound.")
        }
    }

    private fun routeChanged() {
        if (!running) return
        val ok = routeOk()
        if (ok == !paused) return
        paused = !ok
        onRoute(ok)
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1000) } }
        thread = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        if (registered) runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        registered = false
    }
}
```

- [ ] **Step 2: Create the Coaching screen**

Create `android/app/src/main/java/com/openski/android/CoachingActivity.kt`:

```kotlin
package com.openski.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Audio coaching: a metronome and a chirp after every few half-turns. A simple Snow-look screen that the
 * run screen will absorb later. It compares dry-ski boot roll with a training target, nothing more.
 */
class CoachingActivity : Activity() {
    private lateinit var store: CoachSettingsStore
    private lateinit var frame: FrameLayout
    private var service: SensorSessionService? = null
    private var bound = false
    private var settings = CoachSettings()
    private var state = CoachState()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? SensorSessionService.LocalBinder)?.service ?: return
            service = connected
            state = connected.coachState()
            connected.setCoachListener { update -> runOnUiThread { state = update; render() } }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Snow.configureWindow(this)
        store = CoachSettingsStore(this)
        settings = store.settings
        frame = FrameLayout(this).apply { setBackgroundColor(Snow.SNOW) }
        SkiUi.applyInsets(frame)
        setContentView(frame)
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, SensorSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.setCoachListener(null)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    private fun t(text: String, type: Snow.Type = Snow.Type.BODY, tint: Int = Snow.INK) = Snow.text(this, text, type, tint)
    private fun LinearLayout.add(view: View, top: Int = 0, bottom: Int = 0) =
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Snow.dp(this@CoachingActivity, top); bottomMargin = Snow.dp(this@CoachingActivity, bottom) })

    private fun change(update: (CoachSettings) -> CoachSettings) {
        settings = update(settings).normalised()
        store.settings = settings
        render()
    }

    private fun start(demo: Boolean) {
        val running = service
        if (running == null) {
            state = CoachState(message = "The sensor service is not ready yet. Try again in a moment.")
            render()
            return
        }
        val problem = running.startCoaching(settings, demo)  // null means it started; the listener delivers the new state
        if (problem != null) { state = CoachState(message = problem); render() }
    }

    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = Snow.dp(this@CoachingActivity, 20)
            setPadding(inset, inset, inset, Snow.dp(this@CoachingActivity, 28))
        }
        column.add(t("Coaching", Snow.Type.HERO), bottom = 8)
        column.add(t("Audio cues for pace and depth, so you can ski with the phone in a pocket and listen.", Snow.Type.BODY, Snow.INK_SOFT), bottom = 16)

        val status = Snow.card(this, 18)
        status.add(t(when { state.running && state.paused -> "Paused"; state.running -> "Coaching"; else -> "Ready" }, Snow.Type.TITLE))
        status.add(t(state.message.ifEmpty { settings.targetLabel() }, Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        state.last?.let { status.add(t(verdictLine(it), Snow.Type.BODY), top = 10) }
        column.add(status, bottom = 12)

        if (state.running) {
            column.add(Snow.button(this, "Stop coaching") { service?.stopCoaching() }, bottom = 10)
        } else {
            column.add(Snow.button(this, "Start coaching") { start(false) }, bottom = 10)
            column.add(Snow.button(this, "Try with demo boots", Snow.ButtonKind.SECONDARY) { start(true) }, bottom = 10)
        }
        column.add(Snow.button(this, "Play test sounds", Snow.ButtonKind.QUIET) { service?.playCoachTestSounds(settings) }, bottom = 16)

        val explainer = Snow.card(this, 18)
        explainer.add(t("What you will hear", Snow.Type.TITLE))
        explainer.add(t("Ticks set the beat you are aiming for. After every ${settings.windowSize} half-turns: a rising chirp means they matched the target, a falling chirp means they did not, and no sound means close or not enough to judge.", Snow.Type.BODY, Snow.INK_SOFT), top = 4)
        explainer.add(t("This compares dry-ski boot roll with a training target. It is not an on-snow technique score.", Snow.Type.CAPTION, Snow.INK_SOFT), top = 8)
        column.add(explainer, bottom = 20)

        if (!state.running) {
            column.add(t("Target and sounds", Snow.Type.DISPLAY), bottom = 8)
            column.add(setting("Target", settings.targetLabel()) { chooseTarget() })
            column.add(setting("Metronome", onOff(settings.metronome)) { change { it.copy(metronome = !it.metronome) } })
            column.add(setting("Count-in of 4 ticks", onOff(settings.countIn)) { change { it.copy(countIn = !it.countIn) } })
            column.add(setting("Chirps", onOff(settings.chirps)) { change { it.copy(chirps = !it.chirps) } })
            column.add(setting("Turns per chirp", "${settings.windowSize}") { change { it.copy(windowSize = if (it.windowSize >= Coach.MAX_WINDOW) Coach.MIN_WINDOW else it.windowSize + 1) } })
            column.add(setting("Follow boot", settings.coachBoot.name.lowercase().replaceFirstChar { it.uppercase() }) {
                change { it.copy(coachBoot = CoachBoot.entries[(it.coachBoot.ordinal + 1) % CoachBoot.entries.size]) } })
            column.add(setting("Volume", "${settings.gainPercent}%") {
                change { it.copy(gainPercent = VOLUMES.firstOrNull { v -> v > it.gainPercent } ?: VOLUMES.first()) } })
            column.add(setting("Allow phone speaker", onOff(settings.allowPhoneSpeaker)) { change { it.copy(allowPhoneSpeaker = !it.allowPhoneSpeaker) } })
        }
        frame.removeAllViews()
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(column) })
    }

    private fun setting(label: String, value: String, action: () -> Unit) =
        Snow.button(this, "$label: $value", Snow.ButtonKind.SECONDARY, action).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Snow.dp(this@CoachingActivity, 10) }
        }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private fun verdictLine(v: CoachVerdict): String {
        val target = state.target
        val word = when (v.verdict) { Verdict.POSITIVE -> "matched the target"; Verdict.NEGATIVE -> "off target"; Verdict.NONE -> "close" }
        val beat = "%.1f".format(Locale.US, v.meanBeatSeconds)
        val aim = target?.let { " (aim ${"%.1f".format(Locale.US, it.beatSeconds)} s, ${it.depthDegrees.roundToInt()}°)" } ?: ""
        return "Last turns: $word · beat $beat s, roll ${v.meanDepthDegrees.roundToInt()}°$aim · score ${v.score}"
    }

    private fun chooseTarget() {
        val drills = Programme.drills
        val names = drills.map { "${it.title} · ${"%.1f".format(Locale.US, it.paceSeconds)} s, ${it.depthDegrees.roundToInt()}°" } + "Custom beat and depth…"
        AlertDialog.Builder(this).setTitle("Coaching target").setItems(names.toTypedArray()) { _, index ->
            if (index < drills.size) change { it.copy(presetDrillId = drills[index].id) } else customTarget()
        }.show()
    }

    private fun customTarget() {
        val beat = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Seconds between turns (0.8 to 3.5)"
            setText("%.1f".format(Locale.US, settings.customBeat))
        }
        val depth = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Roll in degrees (10 to 45)"
            setText("%.0f".format(Locale.US, settings.customDepth))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Snow.dp(this@CoachingActivity, 20)
            setPadding(pad, pad, pad, 0)
            addView(beat); addView(depth)
        }
        AlertDialog.Builder(this).setTitle("Custom target").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val b = beat.text.toString().toDoubleOrNull() ?: settings.customBeat
                val d = depth.text.toString().toDoubleOrNull() ?: settings.customDepth
                change { it.copy(presetDrillId = null, customBeat = b, customDepth = d) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private companion object {
        val VOLUMES = listOf(30, 45, 60, 75, 90)
    }
}
```

- [ ] **Step 3: Wire the service, manifest and Boots tab**

Save each patch below to `.pio/host/<name>.patch` and apply from the repo root with `git apply --check` then `git apply`.

`SensorSessionService.patch` (adds the coaching state, the hook on mirrored `ski_v0` events, `startCoaching`, `stopCoaching`, `playCoachTestSounds`, the notification text, the wake-lock rule and teardown):

```diff
diff --git a/android/app/src/main/java/com/openski/android/SensorSessionService.kt b/android/app/src/main/java/com/openski/android/SensorSessionService.kt
index 0446a1d..1203cf5 100644
--- a/android/app/src/main/java/com/openski/android/SensorSessionService.kt
+++ b/android/app/src/main/java/com/openski/android/SensorSessionService.kt
@@ -58,6 +58,21 @@ class SensorSessionService : Service() {
     private var listener: Listener? = null
     private var foreground = false
     private var monitoring = false
+    private var coachState = CoachState()
+    private var coachSession: CoachSession? = null
+    private var coachAudio: CoachAudio? = null
+    private var coachSettings = CoachSettings()
+    private var coachStartedAt = 0L
+    private var coachDemo: DemoCoachFeed? = null
+    private var coachListener: ((CoachState) -> Unit)? = null
+    private val coachDemoStep = object : Runnable {
+        override fun run() {
+            val feed = coachDemo ?: return
+            val step = feed.next()
+            coachSession?.onEvent("L", step.event)
+            mainHandler.postDelayed(this, step.afterMs)
+        }
+    }
     private var lastNotificationMessage: String? = null
     private var uiVisible = false
     private var destroyed = false
@@ -256,7 +271,11 @@ class SensorSessionService : Service() {
                 updateInfo(side) },
             onRssi = { value -> rssiLevels[side]=value to System.currentTimeMillis(); updateInfo(side) },
             onMovement = { event -> listener?.onMovementEvent(side, event) },
-            onSkiEvent = { event -> listener?.onSkiEvent(side, SkierFrame.of(side, event)) },
+            onSkiEvent = { event ->
+                val skier = SkierFrame.of(side, event)
+                listener?.onSkiEvent(side, skier)
+                mainHandler.post { coachSession?.onEvent(side, skier) }
+            },
             onSkiState = { state ->
                 if (state.production) productionSides.add(side) else productionSides.remove(side)
                 listener?.onSkiState(side, SkierFrame.of(side, state))
@@ -568,6 +587,78 @@ class SensorSessionService : Service() {
         return true
     }
 
+    // Coaching ------------------------------------------------------------------------------------------
+
+    fun coachState(): CoachState = coachState
+    fun setCoachListener(value: ((CoachState) -> Unit)?) { coachListener = value }
+
+    private fun publishCoach(state: CoachState) {
+        coachState = state
+        coachListener?.invoke(state)
+        if (foreground) ensureForeground()  // refresh the notification text
+    }
+
+    /**
+     * Starts the metronome and section chirps. With [demo] on, synthetic half-turns drive the coach so it can be
+     * heard without boots. Returns a reason if it could not start, or null if it started.
+     */
+    fun startCoaching(settings: CoachSettings, demo: Boolean): String? {
+        if (coachState.running) return null
+        val chosen = settings.normalised()
+        val target = chosen.target()
+        val audio = CoachAudio(this,
+            onRoute = { headphones -> mainHandler.post { publishCoach(coachState.copy(paused = !headphones,
+                message = if (headphones) "Coaching resumed." else "Coaching paused: earbuds disconnected.")) } },
+            onProblem = { text -> mainHandler.post { stopCoaching(); publishCoach(CoachState(message = text)) } })
+        audio.start(chosen, target)?.let { return it }
+        try {
+            startMonitoring()  // keeps this service in the foreground with the screen off
+        } catch (error: Exception) {
+            audio.stop()
+            return "Could not keep coaching running in the background."
+        }
+        acquireWakeLock()
+        coachSettings = chosen
+        coachAudio = audio
+        coachStartedAt = SystemClock.elapsedRealtime()
+        coachSession = CoachSession(chosen) { _, verdict -> onCoachVerdict(verdict) }
+        if (demo) {
+            coachDemo = DemoCoachFeed(target)
+            mainHandler.postDelayed(coachDemoStep, (target.beatSeconds * 1000).toLong())
+        }
+        publishCoach(CoachState(running = true, demo = demo, target = target,
+            message = when {
+                demo -> "Coaching on demo boots."
+                clients.values.none { it.isReady } -> "No boot connected yet. You will hear the metronome, and chirps start once a boot sends turns."
+                else -> "Coaching. Start skiing when you hear the first ticks."
+            }))
+        return null
+    }
+
+    fun stopCoaching() {
+        mainHandler.removeCallbacks(coachDemoStep)
+        coachDemo = null
+        coachSession = null
+        coachAudio?.stop()
+        coachAudio = null
+        if (!coachState.running) return
+        publishCoach(CoachState(message = "Coaching stopped."))
+        settleForeground()
+    }
+
+    fun playCoachTestSounds(settings: CoachSettings) {
+        val audio = coachAudio ?: CoachAudio(this, onRoute = {}, onProblem = { text -> mainHandler.post { coachListener?.invoke(CoachState(message = text)) } })
+        audio.playTestSounds(settings.normalised().gainPercent)
+    }
+
+    private fun onCoachVerdict(verdict: CoachVerdict) {
+        val target = coachState.target ?: return
+        val countInMs = (COUNT_IN_BEATS * target.beatSeconds * 1000).toLong()
+        val counting = coachSettings.metronome && coachSettings.countIn && SystemClock.elapsedRealtime() - coachStartedAt < countInMs
+        if (coachSettings.chirps && !counting && !coachState.paused) coachAudio?.chirp(verdict.verdict)
+        publishCoach(coachState.copy(last = verdict))
+    }
+
     /** Boots with a live, ready link; only these can take a command. */
     fun connectedSides(): List<String> = clients.filterValues { it.isReady }.keys.sorted()
 
@@ -779,6 +870,7 @@ class SensorSessionService : Service() {
         val warnings = addresses.keys.filter { side -> clients[side]?.isReady != true ||
             SystemClock.elapsedRealtime() - (lastSampleTime[side] ?: 0) > 3_000 }
         return when {
+            coachState.running && coachState.paused -> "Coaching paused: earbuds disconnected"
             activeSessionId != null -> if (warnings.isEmpty()) "Recording both available boot streams" else "Recording · ${warnings.joinToString("/")} boot samples unavailable"
             transfers.isNotEmpty() -> "Recovering sensor flash · keep sensors powered and nearby"
             store.hasPendingRecovery() -> "Live recording saved · sensor flash recovery pending"
@@ -795,7 +887,7 @@ class SensorSessionService : Service() {
         foreground = true
     }
     private fun settleForeground() {
-        if (activeSessionId == null && transfers.isEmpty()) releaseWakeLock()
+        if (activeSessionId == null && transfers.isEmpty() && !coachState.running) releaseWakeLock()
         if (!monitoring && activeSessionId == null && !store.hasPendingRecovery() && transfers.isEmpty() && !storageFailed) {
             if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
             foreground = false
@@ -832,6 +924,7 @@ class SensorSessionService : Service() {
 
     override fun onDestroy() {
         destroyed = true
+        stopCoaching()
         mainHandler.removeCallbacks(tick)
         listOf("L", "R").forEach { invalidate(it) }
         reconnectTasks.values.forEach(mainHandler::removeCallbacks)
@@ -847,6 +940,7 @@ class SensorSessionService : Service() {
 
     companion object {
         const val ACTION_MONITOR = "com.openski.android.MONITOR"
+        private const val COUNT_IN_BEATS = 4
         const val ACTION_STOP_MONITORING = "com.openski.android.STOP_MONITORING"
         const val ACTION_START_RECORDING = "com.openski.android.START_RECORDING"
         const val ACTION_STOP_RECORDING = "com.openski.android.STOP_RECORDING"
```

`AndroidManifest.patch` (media-playback foreground type and permission, and the new activity):

```diff
diff --git a/android/app/src/main/AndroidManifest.xml b/android/app/src/main/AndroidManifest.xml
index 894a7be..c1765c0 100644
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -7,6 +7,7 @@
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     <uses-permission android:name="android.permission.WAKE_LOCK" />
 
@@ -26,12 +27,13 @@
             </intent-filter>
         </activity>
         <activity android:name=".DrillActivity" android:exported="false" android:theme="@style/Theme.OpenSki.Snow" />
+        <activity android:name=".CoachingActivity" android:exported="false" android:theme="@style/Theme.OpenSki.Snow" />
         <activity android:name=".MainActivity" android:exported="false" />
         <activity android:name=".BenchActivity" android:exported="false" />
         <service
             android:name=".SensorSessionService"
             android:exported="false"
-            android:foregroundServiceType="connectedDevice" />
+            android:foregroundServiceType="connectedDevice|mediaPlayback" />
         <activity android:name=".SessionDetailActivity" android:exported="false" />
     </application>
 </manifest>
```

`HomeActivity.patch` (a "Coaching" card on the Boots tab):

```diff
diff --git a/android/app/src/main/java/com/openski/android/HomeActivity.kt b/android/app/src/main/java/com/openski/android/HomeActivity.kt
index 17a2284..abe0055 100644
--- a/android/app/src/main/java/com/openski/android/HomeActivity.kt
+++ b/android/app/src/main/java/com/openski/android/HomeActivity.kt
@@ -365,6 +365,15 @@ class HomeActivity : Activity() {
         add(status, bottom = 12)
         add(Snow.button(this@HomeActivity, if (paired.isEmpty()) "Pair my boots" else "Manage sensors", if (paired.isEmpty()) Snow.ButtonKind.PRIMARY else Snow.ButtonKind.SECONDARY) { openBoots() }, bottom = 28)
 
+        add(t("Coaching", Snow.Type.DISPLAY), bottom = 6)
+        val coaching = Snow.card(this@HomeActivity, 18)
+        coaching.add(t("Metronome and turn chirps", Snow.Type.TITLE))
+        coaching.add(t("Audio cues for pace and depth, for skiing with the phone in a pocket. Needs earbuds or a helmet speaker.", Snow.Type.BODY, Snow.INK_SOFT), top = 6)
+        coaching.add(Snow.button(this@HomeActivity, "Open coaching", Snow.ButtonKind.SECONDARY) {
+            startActivity(Intent(this@HomeActivity, CoachingActivity::class.java))
+        }, top = 14)
+        add(coaching, bottom = 28)
+
         add(t("Geek mode", Snow.Type.DISPLAY), bottom = 6)
         val geek = Snow.card(this@HomeActivity, 18, Snow.INK)
         geek.addView(t("Raw telemetry and the logbook", Snow.Type.TITLE, Snow.SNOW))
```

Run: `git apply --check .pio/host/SensorSessionService.patch && git apply .pio/host/SensorSessionService.patch`, then the same for the other two.
Expected: no output. `git diff --stat` then lists `SensorSessionService.kt`, `AndroidManifest.xml` and `HomeActivity.kt` with only the lines in the patches.

- [ ] **Step 4: Build, test and lint**

Run (from `android/`): `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`. There must be no new warnings from the new files (`grep` the output for `Coach` and `ToneSynth`), and the earlier unit tests still pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main
git commit -m "$(cat <<'EOF'
Add audio coaching: audio shell, service hook and the Coaching screen

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 5: Docs, the device checklist and final verification

**Files:**
- Modify: `docs/android-acceptance.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add the device checklist to `docs/android-acceptance.md`**

Append this section (use the Edit tool, or Python with `newline=''`; the file uses LF):

```markdown

## Audio coaching checks (phone and earbuds needed)

The JVM tests cover the judgement, sounds, settings and demo feed. These cover what only a phone can show. Use Bluetooth earbuds or a helmet speaker unless a step says otherwise.

1. Open Boots > Coaching, tap Play test sounds. Expect a tick, a higher accent tick, a rising chirp, then a falling chirp, at a comfortable volume. Try the Volume setting at 30% and 90%.
2. Tap Try with demo boots. Expect ticks at the target beat and, after about eight turns, a rising chirp, then later a falling chirp as the demo drifts off target.
3. Lock the phone and put it in a pocket. Expect the ticks to carry on for at least two minutes and the notification to stay.
4. Switch the earbuds off mid-run. Expect the ticks to stop, no sound from the phone speaker, and the notification "Coaching paused: earbuds disconnected". Switch them back on and expect coaching to resume.
5. With no earbuds connected and Allow phone speaker off, tap Start coaching. Expect a refusal with a message. Turn Allow phone speaker on and expect the ticks from the speaker.
6. Play music in another app, then start coaching. Expect the ticks and chirps to mix over the music, and the music not to pause.
7. Start coaching with no boot connected. Expect the message that no boot is connected and the metronome still playing. Connect a boot and expect chirps once it sends turns.
8. With a real boot in the garden (training mode) and then in on-snow mode, roll the boot from side to side at the target beat. Expect chirps after each window. Record how often a clearly good window gets no sound, to tune the thresholds.
9. Leave the Coaching screen and reopen it while coaching runs. Expect coaching to continue and the screen to show the current state.
10. Disconnect the boot during a run. Expect the metronome to keep ticking and no crash.
```

- [ ] **Step 2: Add a short entry to `CLAUDE.md`**

In the Android section, after the bullet describing `BenchActivity`, add this bullet (use the Edit tool, or Python with `newline=''`; the file uses LF):

```markdown
  - **Coaching** (`Coach.kt`, `ToneSynth.kt`, `CoachSession.kt`, `CoachSettings.kt`, `DemoCoachFeed.kt`, `CoachAudio.kt`, `CoachingActivity.kt`): audio cues for a skier with the phone in a pocket. `Coach` judges non-overlapping windows of 3 to 5 `ski_v0` half-turns against a `CoachTarget` (a drill's beat and depth, or custom) and returns a rising chirp, a falling chirp or nothing; `MetronomeClock` ticks at the target beat. The judging, sounds, settings and demo feed are JVM-tested; `CoachAudio` and the service hook are only checked on a phone (see `docs/android-acceptance.md`). The service is declared `connectedDevice|mediaPlayback`. Entered from the Boots tab; it compares dry-ski boot roll with a training target, never on-snow technique.
```

- [ ] **Step 3: Run every automated check**

Run (from `android/`): `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`. Confirm the result files show `CoachTest` 17, `ToneSynthTest` 10, `CoachSessionTest` 7, `ProgrammeTest` 12 and `SkiEventProtocolTest` 8, all with `failures="0"`.

- [ ] **Step 4: Hand over the device checklist**

The device checklist cannot be run by an automated agent. In the final report, list the ten checks as not yet run, and say plainly that the sound, the screen-off behaviour and the earbud handling are unverified until a person runs them.

- [ ] **Step 5: Commit**

```bash
git add docs/android-acceptance.md CLAUDE.md
git commit -m "$(cat <<'EOF'
Document audio coaching and its device checklist

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

## Self-review notes

- Spec coverage: judgement rules and resets (Task 1); sounds and drift-free metronome (Task 2); settings, boot selection, demo feed (Task 3); audio shell, routing and pause, media-playback service type, notification, Coaching screen and Boots-tab entry (Task 4); docs and the device checklist (Task 5). Out-of-scope items (phase alignment, spoken cues, a continuous tone, music ducking, the Run screen, the wave view, saving verdicts) have no task.
- Two details settled while prototyping and recorded in the spec: the weakest-component floors on the verdict, and the count-in meaning "chirps are muted for the first four beats".
- The `CoachSettingsStore` and everything that touches the audio hardware are verified only by compilation and the device checklist.
