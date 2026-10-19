package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class DrillControllerTest {
    private class Harness : DrillEffects {
        var clock = 0L
        var recording: String? = null
        var opens = 0
        var stops = 0
        var saves = 0
        var prepared: ((Result<Int>) -> Unit)? = null
        var calibration: ((Result<Unit>) -> Unit)? = null
        var deferPrepare = false
        var failSave = false
        var failPrepare: String? = null
        var abandoned = 0
        val markers = mutableListOf<String>()
        val controller = DrillController(this, { clock }, { 100_000L + clock })
        override fun prepare(attempt: Attempt, completed: (Result<Int>) -> Unit) {
            if (deferPrepare) prepared = completed
            else completed(failPrepare?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(6))
        }
        override fun openSession(attempt: Attempt, completed: (Result<Unit>) -> Unit) {
            opens++; recording = attempt.sessionId; completed(Result.success(Unit))
        }
        override fun calibrate(attempt: Attempt, side: String, mounting: Mounting, completed: (Result<Unit>) -> Unit) { calibration = completed }
        override fun abandon(attempt: Attempt) { abandoned++ }
        override fun mark(attempt: Attempt, label: String) { markers += label }
        override fun stopSession(attempt: Attempt) { if (recording == attempt.sessionId) { recording = null; stops++ } }
        override fun save(attempt: Attempt, run: DrillRun?, completed: (Result<Attempt>) -> Unit) {
            saves++
            if (failSave) completed(Result.failure(IllegalStateException("disk"))) else completed(Result.success(attempt))
        }
        fun tick(live: Set<String> = setOf("L"), mounting: Mounting? = Mounting(0, 1)) =
            controller.tick(live, mapOf("L" to mounting), recording)
        fun startLive() { assertNull(controller.start(Programme.drills.first(), false, "a", "session")); tick() }
        fun ready() {
            startLive()
            clock = 3300; tick()
            calibration!!(Result.success(Unit))
            controller.accept(BootRoll("L", 100_000.0 + clock, 0.0, 0.0))
            clock += 3000
            controller.accept(BootRoll("L", 100_000.0 + clock, 0.0, 0.0)); tick()
        }
    }

    @Test fun observingSnapshotsAndReturningDoesNotStopOrRestartCapture() {
        val h = Harness(); h.ready()
        val before = h.controller.state()
        repeat(10) { h.controller.state(); h.tick() }
        assertEquals(DrillPhase.RUNNING, h.controller.phase)
        assertEquals(before.attempt!!.id, h.controller.state().attempt!!.id)
        assertEquals("session", h.recording)
        assertEquals(1, h.opens)
        assertEquals(0, h.stops)
        assertNotNull(h.controller.start(Programme.drills.last(), true, "other", null))
    }

    @Test fun mountingWaitsForExplicitChoiceAndHandlesAStreamDisappearing() {
        val h = Harness()
        h.controller.start(Programme.drills.first(), false, "a", "session")
        h.tick(mounting = null)
        repeat(5) { h.tick(mounting = null) }
        assertEquals(DrillPhase.MOUNTING, h.controller.phase)
        assertEquals(0, h.opens)
        h.tick(live = emptySet())
        assertEquals(DrillPhase.CONNECTING, h.controller.phase)
        h.tick()
        assertEquals(DrillPhase.HOLDING, h.controller.phase)
        assertEquals(1, h.opens)
    }

    @Test fun cancelDuringPreparationIgnoresItsLateCallback() {
        val h = Harness(); h.deferPrepare = true
        h.controller.start(Programme.drills.first(), false, "a", "session")
        h.controller.finishEarly()
        h.prepared!!(Result.success(6))
        h.tick()
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals(PracticeStatus.INCOMPLETE, h.controller.state().attempt!!.status)
        assertEquals(0, h.opens)
        assertEquals(1, h.saves)
    }

    @Test fun lateCalibrationCannotReviveACancelledOrDifferentAttempt() {
        val h = Harness(); h.startLive(); h.clock = 3300; h.tick()
        val late = h.calibration!!
        h.controller.finishEarly()
        h.controller.start(Programme.drills.first(), true, "new", null)
        late(Result.success(Unit))
        assertEquals("new", h.controller.state().attempt!!.id)
        assertEquals(DrillPhase.RUNNING, h.controller.phase)
    }

    @Test fun losingRecordingOwnershipInterruptsWithoutStoppingAnotherSession() {
        val h = Harness(); h.ready()
        h.recording = "different-recording"
        h.tick()
        assertEquals(PracticeStatus.INTERRUPTED, h.controller.state().attempt!!.status)
        assertEquals("different-recording", h.recording)
        assertEquals(0, h.stops)
    }

    @Test fun completionIsOnceAndSaveRetryKeepsTheIdentity() {
        val h = Harness(); h.ready(); h.failSave = true
        h.controller.finishEarly()
        assertEquals(DrillPhase.SAVE_FAILED, h.controller.phase)
        assertEquals(1, h.saves)
        h.failSave = false; h.controller.retrySave(); h.controller.retrySave()
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals("a", h.controller.state().attempt!!.id)
        assertEquals(2, h.saves)
        assertEquals(1, h.stops)
    }

    @Test fun demoProgressDependsOnServiceTimeInsteadOfAViewAnimator() {
        val h = Harness()
        h.controller.start(Programme.drills.first(), true, "demo", null)
        h.clock = 3250; h.tick(live = emptySet())
        assertEquals(0.5f, h.controller.state().animation, 0.001f)
        h.clock = 6500; h.tick(live = emptySet())
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals(PracticeStatus.COMPLETED, h.controller.state().attempt!!.status)
        repeat(5) { h.tick(live = emptySet()) }
        assertEquals(1, h.saves)
        assertEquals(0, h.opens)
    }

    @Test fun liveCompletionUsesTheExistingMovementDetector() {
        val h = Harness(); h.ready()
        val run = DemoDrill.simulate(Programme.drills.first(), 6)
        val start = h.clock
        for (i in run.trace.seconds.indices) {
            h.clock = start + (run.trace.seconds[i] * 1000).toLong()
            h.controller.accept(BootRoll("L", 100_000.0 + h.clock, run.trace.degrees[i].toDouble(), 0.0))
        }
        assertEquals(PracticeStatus.COMPLETED, h.controller.state().attempt!!.status)
        assertEquals(10, h.controller.state().result!!.completed)
        assertEquals(1, h.saves)
        assertEquals(listOf("START_TEST", "PAUSE"), h.markers)
    }

    @Test fun serviceRestartRestoresAnInterruptedSummaryWithoutCaptureOrReward() {
        val h = Harness()
        h.controller.restoreInterrupted(Attempt("slow-rolls", 1, 100, 3, 200, false, status = PracticeStatus.ACTIVE))
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals(PracticeStatus.INTERRUPTED, h.controller.state().attempt!!.status)
        assertEquals(0, h.controller.state().attempt!!.vert)
        assertEquals(0, h.opens)
        assertEquals(0, h.saves)
    }

    @Test fun returningAfterBackgroundCompletionRestoresTheCommittedSummaryWithoutAnotherReward() {
        val h = Harness()
        val saved = Attempt("slow-rolls", 100, 80, 2, 160, true, id = "saved")
        h.controller.restoreSaved(saved)
        assertEquals(saved, h.controller.state().attempt)
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals(0, h.opens)
        assertEquals(0, h.saves)
    }
    @Test fun livePauseRetainsRawOwnerAndRequiresNewNeutralBeforeClockResumes() {
        val h=Harness(); h.ready(); h.clock+=1000
        assertTrue(h.controller.pause("Audio route lost")); assertFalse(h.controller.pause())
        val elapsed=h.controller.state().seconds
        assertEquals(0,h.stops); assertEquals("session",h.recording)
        h.clock=1_000_000; h.tick()
        assertEquals(DrillPhase.PAUSED,h.controller.phase); assertEquals(elapsed,h.controller.state().seconds,0.0)
        h.controller.accept(BootRoll("L",100_000.0+h.clock,0.0,0.0))
        assertTrue(h.controller.resume()); assertFalse(h.controller.resume()); h.tick()
        assertEquals(DrillPhase.RESUMING,h.controller.phase)
        h.controller.accept(BootRoll("L",100_000.0+h.clock-2000,0.0,0.0))
        assertEquals(DrillPhase.RESUMING,h.controller.phase)
        h.clock+=20; h.controller.accept(BootRoll("L",100_000.0+h.clock,20.0,0.0))
        assertEquals(DrillPhase.RESUMING,h.controller.phase)
        h.clock+=20; h.controller.accept(BootRoll("L",100_000.0+h.clock,0.0,0.0))
        assertEquals(DrillPhase.COUNTDOWN,h.controller.phase); assertEquals(elapsed,h.controller.state().seconds,0.0)
        h.clock+=3000; h.controller.accept(BootRoll("L",100_000.0+h.clock,0.0,0.0)); h.tick()
        assertEquals(DrillPhase.RUNNING,h.controller.phase); assertEquals(elapsed,h.controller.state().seconds,0.0)
        h.clock+=1000; h.tick(); assertEquals(elapsed+1,h.controller.state().seconds,0.0)
        assertEquals(2,h.markers.count { it=="START_TEST" })
    }
    @Test fun pausedAndNeutralWaitingStillGuardRecordingOwnership() {
        for(resume in listOf(false,true)) {
            val h=Harness(); h.ready(); h.controller.pause(); if(resume) h.controller.resume()
            h.recording="someone-else"; h.tick()
            assertEquals(PracticeStatus.INTERRUPTED,h.controller.state().attempt!!.status)
            assertEquals("someone-else",h.recording); assertEquals(0,h.stops)
        }
    }
    @Test fun finishingWhilePausedIsIncompleteAndStopsOnlyOnce() {
        val h=Harness(); h.ready(); h.controller.pause(); h.controller.finishEarly(); h.controller.finishEarly()
        assertEquals(PracticeStatus.INCOMPLETE,h.controller.state().attempt!!.status)
        assertEquals(1,h.stops); assertEquals(1,h.saves)
        assertEquals(PracticeTiming.POLICY,h.controller.state().attempt!!.review!!.evidence.timingPolicy)
    }
    @Test fun demoPauseFreezesSimulationAndElapsedTimeUntilExplicitResume() {
        val h=Harness(); h.controller.start(Programme.drills.first(),true,"demo",null)
        h.clock=2000; assertTrue(h.controller.pause()); val before=h.controller.state()
        h.clock=200_000; h.tick(emptySet())
        assertEquals(before.animation,h.controller.state().animation,0.0f)
        assertEquals(2.0,h.controller.state().seconds,0.0); assertEquals(0,h.saves)
        assertTrue(h.controller.resume()); h.clock+=4500; h.tick(emptySet())
        assertEquals(DrillPhase.DONE,h.controller.phase); assertEquals(1,h.saves)
        assertEquals("continuous_v1",h.controller.state().attempt!!.review!!.evidence.timingPolicy)
    }

    @Test fun countdownRequiresFreshNeutralAndDoesNotScoreOrAdvanceActiveTime() {
        val h=Harness(); h.startLive(); h.clock=3300; h.tick(); h.calibration!!(Result.success(Unit))
        h.controller.accept(BootRoll("L",100_000.0+h.clock,0.0,0.0))
        assertEquals(DrillPhase.COUNTDOWN,h.controller.phase); assertEquals(3,h.controller.state().countdownSeconds)
        h.clock+=3000; h.tick()
        assertEquals(DrillPhase.SETTLING,h.controller.phase); assertEquals(0.0,h.controller.state().seconds,0.0)
        h.controller.accept(BootRoll("L",100_000.0+h.clock,0.0,0.0)); assertTrue(h.controller.pause())
        assertEquals(0,h.stops); h.recording="other"; h.tick()
        assertEquals(PracticeStatus.INTERRUPTED,h.controller.state().attempt!!.status)
    }

    @Test fun streamLossPausesWithoutStoppingRawAndReconnectNeverAutoResumes() {
        val h=Harness(); h.ready(); h.clock+=1000; h.tick(emptySet())
        assertEquals(DrillPhase.PAUSED,h.controller.phase); assertEquals(0,h.stops)
        val paused=h.controller.state().seconds
        h.clock+=100_000; h.tick(); assertEquals(DrillPhase.PAUSED,h.controller.phase)
        assertEquals(paused,h.controller.state().seconds,0.0)
    }

    @Test fun aSaveThatKeepsFailingCanBeAbandonedSoTheServiceIsNotHeldActive() {
        val h = Harness(); h.ready(); h.failSave = true
        h.controller.finishEarly()
        h.controller.retrySave()
        assertEquals(DrillPhase.SAVE_FAILED, h.controller.phase)
        assertTrue(h.controller.active)
        h.controller.finishEarly()
        assertFalse(h.controller.active)
        assertEquals(DrillPhase.DONE, h.controller.phase)
        assertEquals(PracticeStatus.INTERRUPTED, h.controller.state().attempt!!.status)
        assertEquals(0, h.controller.state().attempt!!.stars)
        assertEquals(1, h.abandoned)
        assertEquals(2, h.saves)
        h.controller.interrupt("again")
        assertEquals(1, h.abandoned)
    }

    @Test fun interruptingAFailedSaveAlsoReleasesTheController() {
        val h = Harness(); h.ready(); h.failSave = true
        h.controller.finishEarly()
        h.controller.interrupt("service destroyed")
        assertFalse(h.controller.active)
        assertEquals(1, h.abandoned)
    }

    @Test fun refusedPreparationLeavesNoAttemptOrSave() {
        val h = Harness(); h.failPrepare = "Complete the preceding measured sets, or explore this drill in Demo."
        assertNull(h.controller.start(Programme.drills.last(), false, "a", "session"))
        assertEquals(DrillPhase.IDLE, h.controller.phase)
        assertFalse(h.controller.active)
        assertNull(h.controller.state().attempt)
        assertEquals(h.failPrepare, h.controller.state().message)
        assertEquals(0, h.saves)
        assertTrue(h.markers.isEmpty())
        h.tick()
        assertEquals(DrillPhase.IDLE, h.controller.phase)
        h.failPrepare = null
        assertNull(h.controller.start(Programme.drills.first(), false, "b", "session"))
        assertEquals(DrillPhase.CONNECTING, h.controller.phase)
    }

    @Test fun aRunningSetIsJudgedForTheGhostOnTheEvidenceItWillCarry() {
        val h = Harness(); h.ready()
        assertEquals(DrillPhase.RUNNING, h.controller.phase)
        val (subject, evidence) = h.controller.prospective()!!
        val previous = subject.copy(id = "earlier", review = PracticeReview(evidence, 6, 1.0, 10.0, 10.0, 10.0, 5, emptySet()))
        assertTrue(PracticeComparisonPolicy.evaluate(subject, evidence, previous, previous.review!!.evidence).comparable)
        val otherSide = previous.copy(review = previous.review!!.copy(evidence = evidence.copy(scoredSide = "R")))
        assertFalse(PracticeComparisonPolicy.evaluate(subject, evidence, otherSide, otherSide.review!!.evidence).comparable)
        h.controller.finishEarly()
        assertNull(h.controller.prospective())
    }

    @Test fun aPausedLiveSetIsJudgedForTheGhostOnSegmentedTiming() {
        val h = Harness(); h.ready()
        assertEquals("continuous_v1", h.controller.prospective()!!.second.timingPolicy)
        h.controller.pause()
        assertEquals(PracticeTiming.POLICY, h.controller.prospective()!!.second.timingPolicy)
    }
}
