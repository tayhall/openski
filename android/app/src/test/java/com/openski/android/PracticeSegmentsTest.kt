package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeSegmentsTest {
    private fun movement(start: Double) = DryMovement(start, start + 800.0, true, 20.0)

    @Test fun pauseDoesNotBecomeABeatBetweenIntervals() {
        val first = PracticeSegment(0.0, 3000.0, listOf(movement(100.0), movement(2100.0)))
        val second = PracticeSegment(20_000.0, 23_000.0, listOf(movement(20_100.0), movement(22_100.0)))
        val result = PracticeTiming.summary(listOf(first, second), 10)
        assertEquals(listOf(2.0, 2.0), result.beatSeconds)
        assertEquals(6000.0, result.activeMs, 0.0)
        assertEquals(4, result.movements.size)
    }

    @Test fun oneMovementPerIntervalDoesNotInventTimingEvidence() {
        val result = PracticeTiming.summary(listOf(
            PracticeSegment(0.0, 1000.0, listOf(movement(100.0))),
            PracticeSegment(10_000.0, 11_000.0, listOf(movement(10_100.0)))), 10)
        assertEquals(2, result.movements.size)
        assertTrue(result.beatSeconds.isEmpty())
    }

    @Test fun targetLimitDoesNotPairAnExtraMovementOrCrossAPause() {
        val result = PracticeTiming.summary(listOf(
            PracticeSegment(0.0, 3000.0, listOf(movement(100.0), movement(2100.0))),
            PracticeSegment(10_000.0, 13_000.0, listOf(movement(10_100.0), movement(12_100.0)))), 3)
        assertEquals(3, result.movements.size)
        assertEquals(listOf(2.0), result.beatSeconds)
    }

    @Test fun emptyActiveIntervalsAreTimeWithoutInventedMovements() {
        val result = PracticeTiming.summary(listOf(PracticeSegment(1000.0, 2000.0, emptyList())), 10)
        assertEquals(1000.0, result.activeMs, 0.0)
        assertTrue(result.movements.isEmpty() && result.beatSeconds.isEmpty())
    }

    @Test fun unfinishedMovementCannotBeAcceptedAcrossPauseBoundary() {
        assertTrue(runCatching { PracticeSegment(0.0, 500.0, listOf(movement(100.0))) }.isFailure)
        assertTrue(runCatching { PracticeSegment(200.0, 1000.0, listOf(movement(100.0))) }.isFailure)
    }

    @Test fun corruptOrOverlappingIntervalsAreRejected() {
        assertTrue(runCatching { PracticeSegment(Double.NaN, 1000.0, emptyList()) }.isFailure)
        assertTrue(runCatching { PracticeTiming.summary(listOf(PracticeSegment(0.0, 1000.0, emptyList()),
            PracticeSegment(900.0, 2000.0, emptyList())), 10) }.isFailure)
    }

    @Test fun duplicatePauseAndResumeCallbacksDoNotDoubleCountTime() {
        val clock = PracticeClock()
        assertTrue(clock.resume(1000)); assertFalse(clock.resume(1500))
        assertEquals(1000L, clock.elapsedMs(2000))
        assertTrue(clock.pause(3000)); assertFalse(clock.pause(5000))
        assertEquals(2000L, clock.elapsedMs(50_000))
        assertTrue(clock.resume(60_000)); assertFalse(clock.resume(60_100))
        assertEquals(3000L, clock.elapsedMs(61_000))
    }
}
