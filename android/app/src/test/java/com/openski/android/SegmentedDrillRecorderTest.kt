package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class SegmentedDrillRecorderTest {
    private val drill = Programme.drills.first()
    private fun feed(recorder: SegmentedDrillRecorder, start: Int, end: Int, degrees: Double) {
        for (time in start..end step 20) recorder.accept(BootRoll("L", time.toDouble(), degrees, 0.0))
    }

    @Test fun pauseDiscardsUnfinishedExcursionAndRequiresFreshNeutral() {
        val recorder = SegmentedDrillRecorder(drill, "L")
        feed(recorder, 0, 200, 0.0)
        assertTrue(recorder.begin(200.0))
        feed(recorder, 220, 400, 0.0)
        feed(recorder, 420, 1200, 20.0)
        assertTrue(recorder.pause(1200.0))
        assertEquals(0, recorder.count())
        assertFalse(recorder.begin(1200.0))
        recorder.accept(BootRoll("L", 1190.0, 0.0, 0.0))
        assertFalse(recorder.begin(1300.0))
        feed(recorder, 20_000, 20_200, 20.0)
        assertFalse(recorder.begin(20_200.0))
        feed(recorder, 20_220, 20_800, 0.0)
        assertTrue(recorder.begin(20_800.0))
        feed(recorder, 20_820, 21_000, 0.0)
        feed(recorder, 21_020, 21_800, -20.0)
        feed(recorder, 21_820, 22_500, 0.0)
        recorder.pause(22_500.0)
        assertEquals(1, recorder.count())
        val segments = recorder.segments(22_500.0)
        assertEquals(2, segments.size)
        assertTrue(segments.first().movements.isEmpty())
        assertTrue(PracticeTiming.summary(segments, drill.movements).beatSeconds.isEmpty())
    }

    @Test fun completedMovementSurvivesPauseAndOtherSideCannotResume() {
        val recorder = SegmentedDrillRecorder(drill, "L")
        feed(recorder, 0, 200, 0.0); recorder.begin(200.0)
        feed(recorder, 220, 400, 0.0)
        feed(recorder, 420, 1200, 20.0)
        feed(recorder, 1220, 1900, 0.0)
        recorder.pause(1900.0)
        assertEquals(1, recorder.count())
        recorder.accept(BootRoll("R", 20_000.0, 0.0, 0.0))
        assertFalse(recorder.begin(20_000.0))
        assertFalse(recorder.pause(20_000.0))
        assertEquals(1, recorder.segments(20_000.0).size)
    }
    @Test fun closedSegmentEvidenceIssuesSurviveFreshResumeDetector() {
        val recorder=SegmentedDrillRecorder(drill,"L")
        feed(recorder,0,200,0.0); recorder.begin(200.0)
        recorder.accept(BootRoll("L",220.0,0.0,0.0,gated=true))
        recorder.pause(240.0)
        recorder.accept(BootRoll("L",2000.0,0.0,0.0)); recorder.begin(2000.0)
        feed(recorder,2020,2200,0.0)
        val run=recorder.run(2200.0,true)
        assertTrue(run.evidenceIssues.any { it.contains("gate") })
        assertEquals(2,run.segments!!.size)
        assertEquals(PracticeTiming.POLICY,run.timingPolicy)
    }

    @Test fun pauseBeforeRecorderBeginsRejectsQueuedNeutralAndNeedsNewSample() {
        val recorder=SegmentedDrillRecorder(drill,"L")
        recorder.accept(BootRoll("L",100.0,0.0,0.0))
        assertFalse(recorder.pause(200.0))
        recorder.accept(BootRoll("L",150.0,0.0,0.0))
        assertFalse(recorder.neutral(220.0)); assertFalse(recorder.begin(220.0))
        recorder.accept(BootRoll("L",240.0,0.0,0.0)); assertTrue(recorder.begin(240.0))
    }

}
