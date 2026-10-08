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

    @Test fun eventsStampedAtTheSameTimeNeverCrashTheCoach() {
        // A buggy or hostile sender: distinct sequences and alternating sides, but one boot-clock time.
        // That makes every beat zero, which used to produce NaN scores and an exception when rounding.
        val coach = Coach(target)
        val results = (0 until 8).map { coach.onEvent(event(it + 1, 50_000, it % 2 == 0)) }
        assertTrue(results.all { it == null })
        // And the coach recovers: a proper window afterwards still gets a verdict.
        val proper = (0 until 4).map { coach.onEvent(event(100 + it, 60_000L + it * 2000, it % 2 == 0)) }
        assertEquals(Verdict.POSITIVE, proper[3]!!.verdict)
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
