package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class PracticeReviewTest {
    private val attempt = Attempt("test", 1, 99, 3, 0, false, target = PracticeTarget(4, 2.0, 20.0))
    private val moves = (0..3).map { DryMovement(it * 2000.0, it * 2000.0 + 1000, it % 2 == 0, 18.0) }
    private fun run(movements: List<DryMovement>) = DrillRun(DryDetection(movements, emptyList()), RollTrace(floatArrayOf(), floatArrayOf()), false)

    @Test fun actualMovementsProvideFactualMetricsAndRecordedContext() {
        val review = PracticeReview.capture(attempt, run(moves), "R", Mounting(1, -1))
        assertTrue(review.scoreEligible)
        assertEquals(2.0, review.meanBeatSeconds!!, 0.0)
        assertEquals(18.0, review.meanPeakDegrees!!, 0.0)
        assertEquals(3, review.knownBeatCount)
        assertEquals("R", review.evidence.scoredSide)
        assertEquals("continuous_v1", review.evidence.timingPolicy)
        assertTrue(review.observation(attempt.target).contains("2.0° below"))
    }

    @Test fun insufficientCompletedSetCannotGrantRewardsOrBecomeZeroPerformance() {
        val review = PracticeReview.capture(attempt, run(moves.map { it.copy(positive = true) }), "L", Mounting(0, 1))
        val saved = attempt.copy(review = review)
        assertFalse(saved.eligible)
        assertEquals(PracticeEligibilityState.INSUFFICIENT_DATA, PracticeEligibility.evaluate(saved, review.evidence).state)
        assertEquals(99, saved.score) // Original calculation retained separately from display/reward eligibility.
        assertTrue(attempt.eligible) // Historical null metadata keeps the old reward contract.
    }

    @Test fun unknownMountingMissingMovementsAndInvalidTimingRemainInsufficient() {
        assertFalse(PracticeReview.capture(attempt, run(moves), "L", null).scoreEligible)
        assertFalse(PracticeReview.capture(attempt, run(moves.take(2)), "L", Mounting(0, 1)).scoreEligible)
        assertFalse(PracticeReview.capture(attempt, run(moves.map { it.copy(startMs = 0.0) }), "L", Mounting(0, 1)).scoreEligible)
        assertFalse(PracticeReview.capture(attempt, null, null, null).scoreEligible)
        assertFalse(PracticeReview.capture(attempt, run(moves).copy(evidenceIssues = setOf("Sensor rail flag")), "L", Mounting(0, 1)).scoreEligible)
    }

    @Test fun simulationUsesExplicitSourceWithoutInventedPhysicalContext() {
        val review = PracticeReview.capture(attempt.copy(demo = true), run(moves), null, null)
        assertTrue(review.scoreEligible)
        assertEquals(ScoringEvidenceSource.SIMULATED, review.evidence.scoringSource)
        assertNull(review.evidence.scoredSide); assertNull(review.evidence.mounting)
    }
    @Test fun segmentedReviewNeverInventsBeatsBetweenIsolatedMovements() {
        val review = PracticeReview.capture(attempt, run(moves), "L", Mounting(0, 1),
            moves.map { listOf(it) }, "active_segments_v1")
        assertFalse(review.scoreEligible)
        assertEquals(0, review.knownBeatCount)
        assertNull(review.meanBeatSeconds)
        val paired = PracticeReview.capture(attempt, run(moves), "L", Mounting(0, 1),
            moves.chunked(2), "active_segments_v1")
        assertTrue(paired.scoreEligible)
        assertEquals(2, paired.knownBeatCount)
    }

    @Test fun liveCollectorRetainsScoringQualityIssuesWithoutRewritingRawTrace() {
        val recorder = LiveDrillRecorder(Programme.drills.first(), "L")
        recorder.accept(BootRoll("L", 900.0, 0.0, 0.0, quality = SensorSample.QUALITY_ACCEL_RAIL))
        recorder.begin(1000.0)
        recorder.accept(BootRoll("L", 1000.0, 0.0, 0.0))
        assertTrue(recorder.run().evidenceIssues.isEmpty())
        recorder.accept(BootRoll("L", 1020.0, 5.0, 0.0, quality = SensorSample.QUALITY_GYRO_RAIL, gated = true))
        recorder.accept(BootRoll("L", 1040.0, 6.0, 0.0, segment = 1))
        recorder.accept(BootRoll("L", 1200.0, 0.0, 0.0, segment = 1))
        assertEquals(4, recorder.run().evidenceIssues.size)
        assertEquals(4, recorder.run().trace.seconds.size)
    }

}
