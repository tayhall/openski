package com.openski.android
import org.junit.Assert.*
import org.junit.Test
class SegmentedDrillScoringTest {
    private fun move(start:Double, positive:Boolean) = DryMovement(start,start+500,positive,20.0)
    @Test fun timingScoreAndReviewNeverPairAcrossPause() {
        val drill=Programme.drills.first().copy(movements=4,paceSeconds=2.0,depthDegrees=20.0)
        val groups=listOf(PracticeSegment(0.0,3000.0,listOf(move(0.0,true),move(2000.0,false))),PracticeSegment(100_000.0,103_000.0,listOf(move(100_000.0,true),move(102_000.0,false))))
        val moves=groups.flatMap { it.movements }
        val run=DrillRun(DryDetection(moves,emptyList()),RollTrace(floatArrayOf(),floatArrayOf()),true,segments=groups,timingPolicy=PracticeTiming.POLICY)
        val result=DrillScoring.score(drill,run)
        assertEquals(100,result.tempo); assertEquals(100,result.steadiness)
        assertTrue(DrillScoring.score(drill,run.detection).tempo<result.tempo)
        val attempt=Attempt(drill.id,0,result.score,result.stars,0,true,target=PracticeTarget.of(drill))
        val review=PracticeReview.capture(attempt,run,null,null)
        assertTrue(review.scoreEligible); assertEquals(2,review.knownBeatCount); assertEquals(2.0,review.meanBeatSeconds!!,0.0)
        assertEquals(PracticeTiming.POLICY,review.evidence.timingPolicy)
    }
    @Test fun singleMovementSegmentsHaveNoManufacturedBeat() {
        val drill=Programme.drills.first().copy(movements=2)
        val groups=listOf(PracticeSegment(0.0,1000.0,listOf(move(0.0,true))),PracticeSegment(10_000.0,11_000.0,listOf(move(10_000.0,false))))
        val run=DrillRun(DryDetection(groups.flatMap { it.movements },emptyList()),RollTrace(floatArrayOf(),floatArrayOf()),true,segments=groups,timingPolicy=PracticeTiming.POLICY)
        assertEquals(0,DrillScoring.score(drill,run).tempo)
        val review=PracticeReview.capture(Attempt(drill.id,0,0,0,0,true,target=PracticeTarget.of(drill)),run,null,null)
        assertFalse(review.scoreEligible); assertNull(review.meanBeatSeconds); assertEquals(0,review.knownBeatCount)
    }
}
