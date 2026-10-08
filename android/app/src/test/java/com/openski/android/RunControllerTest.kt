package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class RunControllerTest {
    private class FakeBoots : RunBoots {
        val connectedSides = mutableSetOf("L", "R")
        val modes = mutableMapOf<String, Boolean?>("L" to false, "R" to false)
        val zeroedSides = mutableSetOf<String>()
        val retained = mutableSetOf<String>()
        val full = mutableSetOf<String>()
        val progress = mutableMapOf<String, Int?>()
        val calls = mutableListOf<String>()
        var earbuds = true
        var storage = true
        var beginReason: String? = null
        val refuseMode = mutableSetOf<String>()
        var saved = false
        var failed = false
        override fun connected(side: String) = side in connectedSides
        override fun productionMode(side: String) = modes[side]
        override fun zeroed(side: String) = side in zeroedSides
        override fun flashRetained(side: String) = side in retained
        override fun flashFull(side: String) = side in full
        override fun setProduction(side: String, production: Boolean): Boolean {
            calls.add("mode $side $production")
            if (side in refuseMode) return false
            modes[side] = production
            return true
        }
        override fun zero(side: String) { calls.add("zero $side") }
        override fun beginSession(demo: Boolean): String? { if (beginReason == null) calls.add("begin demo=$demo"); return beginReason }
        override fun endSession() { calls.add("end") }
        override fun saveProgress(side: String) = progress[side]
        override fun flashSaved() = saved
        override fun flashSaveFailed() = failed
        override fun earbudsReady() = earbuds
        override fun storageOk() = storage
    }

    private class FakeSink : RunSink {
        val started = mutableListOf<RunStartInfo>()
        val turns = mutableListOf<Triple<String, Int, Long>>()
        val verdicts = mutableListOf<Pair<String, Verdict>>()
        val ended = mutableListOf<RunEndInfo>()
        override fun started(info: RunStartInfo) { started.add(info) }
        override fun halfTurn(side: String, event: SkiEvent, receivedMs: Long) { turns.add(Triple(side, event.sequence, receivedMs)) }
        override fun verdict(side: String, verdict: CoachVerdict, atMs: Long) { verdicts.add(side to verdict.verdict) }
        override fun ended(info: RunEndInfo) { ended.add(info) }
    }

    private val boots = FakeBoots()
    private val sink = FakeSink()
    private val run = RunController(boots, sink) { CoachSettings() }

    private fun event(sequence: Int, side: Boolean = true) =
        SkiEvent(sequence, 10_000L + sequence * 2000L, 1800, if (side) 20f else -20f, 100f, 15f, side, false, false, false)

    private fun verdict(kind: Verdict) = CoachVerdict(kind, 90, 90, 90, 90, 100, 2.0, 20.0, false)

    private fun startAndZero(now: Long = 0): Long {
        assertNull(run.start(now))
        boots.zeroedSides.addAll(listOf("L", "R"))
        run.tick(now + 1000)
        assertEquals(RunPhase.RUNNING, run.state(now + 1000).phase)
        return now + 1000
    }

    // readiness ---------------------------------------------------------------------------------------------

    @Test fun readyWhenEverythingIsInPlace() {
        assertTrue(run.readiness().ok)
        assertNull(run.readiness().reason)
    }

    @Test fun eachMissingConditionGivesItsOwnReason() {
        boots.connectedSides.remove("L")
        assertEquals("Connect your left boot", run.readiness().reason)
        boots.connectedSides.add("L"); boots.connectedSides.remove("R")
        assertEquals("Connect your right boot", run.readiness().reason)
        boots.connectedSides.add("R"); boots.retained.add("R")
        assertTrue(run.readiness().reason!!.contains("right boot still holds an unsaved recording"))
        boots.retained.clear(); boots.earbuds = false
        assertTrue(run.readiness().reason!!.contains("earbuds"))
        boots.earbuds = true; boots.storage = false
        assertTrue(run.readiness().reason!!.contains("storage"))
    }

    @Test fun theStateReportsWhoIsConnectedAndWhetherTheEarbudsAreIn() {
        boots.connectedSides.remove("R"); boots.earbuds = false
        val state = run.state(0)
        assertEquals(mapOf("L" to true, "R" to false), state.connected)
        assertFalse(state.earbuds)
    }

    @Test fun demoSkipsTheBootChecksButNotTheEarbuds() {
        boots.connectedSides.clear(); boots.retained.add("L")
        assertTrue(run.readiness(forDemo = true).ok)
        boots.earbuds = false
        assertFalse(run.readiness(forDemo = true).ok)
    }

    @Test fun startRefusesWithTheReasonAndTouchesNothing() {
        boots.connectedSides.remove("L")
        assertEquals("Connect your left boot", run.start(0))
        assertTrue(boots.calls.isEmpty())
        assertEquals(RunPhase.READY, run.state(0).phase)
    }

    // zeroing -----------------------------------------------------------------------------------------------

    @Test fun startSwitchesToOnSnowAndZeroesBothBoots() {
        assertNull(run.start(0))
        assertEquals(listOf("mode L true", "zero L", "mode R true", "zero R"), boots.calls)
        assertEquals(RunPhase.ZEROING, run.state(0).phase)
        assertEquals("Stand upright. Hold still.", run.state(0).message)
    }

    @Test fun waitsForBothBootsToZeroBeforeRunning() {
        run.start(0)
        boots.zeroedSides.add("L")
        run.tick(2000)
        assertEquals(RunPhase.ZEROING, run.state(2000).phase)
        assertFalse(boots.calls.contains("begin demo=false"))
        boots.zeroedSides.add("R")
        run.tick(3000)
        assertEquals(RunPhase.RUNNING, run.state(3000).phase)
        assertTrue(boots.calls.contains("begin demo=false"))
        val info = sink.started.single()
        assertEquals(3000L, info.startedAtMs)
        assertEquals(CoachSettings().targetLabel(), info.targetLabel)
        assertEquals(mapOf("L" to false, "R" to false), info.modeBefore)
    }

    @Test fun aBootThatNeverZeroesTimesOutNamedAndRetryOnlyResendsToIt() {
        run.start(0)
        boots.zeroedSides.add("L")
        run.tick(14_999)
        assertFalse(run.state(14_999).zeroTimedOut)
        run.tick(15_000)
        val state = run.state(15_000)
        assertTrue(state.zeroTimedOut)
        assertTrue(state.message.contains("right boot has not zeroed"))
        boots.calls.clear()
        run.retryZero(16_000)
        assertEquals(listOf("zero R"), boots.calls)
        assertFalse(run.state(16_000).zeroTimedOut)
        boots.zeroedSides.add("R")
        run.tick(17_000)
        assertEquals(RunPhase.RUNNING, run.state(17_000).phase)
    }

    @Test fun cancelRestoresOnlyTheModesTheRunChanged() {
        boots.modes["L"] = true            // already on snow before the run
        run.start(0)
        boots.calls.clear()
        run.cancel()
        assertEquals(listOf("mode R false"), boots.calls)
        assertEquals(RunPhase.READY, run.state(0).phase)
    }

    @Test fun aSessionThatCannotStartReturnsToReadyWithTheReasonAndRestoresTheMode() {
        boots.beginReason = "Connect earbuds first"
        run.start(0)
        boots.zeroedSides.addAll(listOf("L", "R"))
        boots.calls.clear()
        run.tick(1000)
        val state = run.state(1000)
        assertEquals(RunPhase.READY, state.phase)
        assertEquals("Connect earbuds first", state.message)
        assertEquals(listOf("mode L false", "mode R false"), boots.calls.filter { it.startsWith("mode") })
        assertTrue(sink.started.isEmpty())
    }

    // running and ending ------------------------------------------------------------------------------------

    @Test fun turnsResetTheQuietTimerAndTwentySecondsOfQuietEndsTheRun() {
        val t0 = startAndZero()
        run.onHalfTurn("L", event(1), t0 + 10_000)
        run.tick(t0 + 29_999)
        assertEquals(RunPhase.RUNNING, run.state(t0 + 29_999).phase)
        run.tick(t0 + 30_000)
        assertEquals(RunPhase.SAVING, run.state(t0 + 30_000).phase)
        assertEquals(RunEnd.QUIET, sink.ended.single().endedBy)
        assertEquals(t0 + 10_000, sink.ended.single().lastTurnMs)
        assertTrue(boots.calls.contains("end"))
    }

    @Test fun beforeTheFirstTurnTheSkierGetsTwoMinutes() {
        val t0 = startAndZero()
        run.tick(t0 + 119_999)
        assertEquals(RunPhase.RUNNING, run.state(t0 + 119_999).phase)
        run.tick(t0 + 120_000)
        assertEquals(RunPhase.SAVING, run.state(t0 + 120_000).phase)
        assertNull(sink.ended.single().firstTurnMs)
    }

    @Test fun manualStopEndsWithManualAndStopWithNoRunDoesNothing() {
        run.stop(0)
        assertTrue(boots.calls.isEmpty())
        val t0 = startAndZero()
        run.stop(t0 + 5000)
        assertEquals(RunEnd.MANUAL, sink.ended.single().endedBy)
        assertEquals(RunPhase.SAVING, run.state(t0 + 5000).phase)
    }

    @Test fun eventsAndVerdictsReachTheSinkOnlyWhileRunning() {
        run.onHalfTurn("L", event(1), 0)                 // not started
        val t0 = startAndZero()
        run.onHalfTurn("L", event(2), t0 + 1000)
        run.onHalfTurn("R", event(3, side = false), t0 + 3000)
        run.onVerdict("L", verdict(Verdict.POSITIVE), t0 + 4000)
        run.stop(t0 + 5000)
        run.onHalfTurn("L", event(4), t0 + 6000)         // after the run
        run.onVerdict("L", verdict(Verdict.NEGATIVE), t0 + 6000)
        assertEquals(listOf(Triple("L", 2, t0 + 1000), Triple("R", 3, t0 + 3000)), sink.turns)
        assertEquals(listOf("L" to Verdict.POSITIVE), sink.verdicts)
    }

    // losses ------------------------------------------------------------------------------------------------

    @Test fun aDroppedBootShowsABannerAndDoesNotEndTheRun() {
        val t0 = startAndZero()
        boots.connectedSides.remove("L")
        run.tick(t0 + 2000)
        val state = run.state(t0 + 3000)
        assertEquals(RunPhase.RUNNING, state.phase)
        assertEquals("The left boot lost its link. Reconnecting", state.banner)
        boots.connectedSides.add("L")
        run.tick(t0 + 7000)
        assertNull(run.state(t0 + 8000).banner)
        run.stop(t0 + 9000)
        assertEquals(RunGap("L", t0 + 2000, t0 + 7000), sink.ended.single().gaps.single())
    }

    @Test fun aGapStillOpenAtTheEndIsClosedThere() {
        val t0 = startAndZero()
        boots.connectedSides.remove("R")
        run.tick(t0 + 2000)
        run.stop(t0 + 6000)
        assertEquals(RunGap("R", t0 + 2000, t0 + 6000), sink.ended.single().gaps.single())
    }

    @Test fun aBootAlreadyDownWhenTheRunBeginsIsRecordedAsAGap() {
        run.start(0)
        boots.zeroedSides.addAll(listOf("L", "R"))
        boots.connectedSides.remove("R")      // drops during zeroing, still down when the run begins
        run.tick(1000)
        run.tick(1500)
        boots.connectedSides.add("R")
        run.tick(4500)
        run.stop(5000)
        assertEquals(RunGap("R", 1500, 4500), sink.ended.single().gaps.single())
    }

    @Test fun bothBootsOutOfRangeDoNotEndTheRunAndTheQuietClockRestartsWhenOneReturns() {
        val t0 = startAndZero()
        run.onHalfTurn("L", event(1), t0 + 1000)     // the skier has started, so the 20 s limit applies, not the first-turn grace
        boots.connectedSides.clear()
        for (offset in 5000L..55_000L step 10_000L) run.tick(t0 + offset)   // a minute with no link and no turns
        assertEquals(RunPhase.RUNNING, run.state(t0 + 55_000).phase)
        boots.connectedSides.add("L")
        run.tick(t0 + 60_000)
        run.tick(t0 + 74_999)
        assertEquals(RunPhase.RUNNING, run.state(t0 + 74_999).phase)
        run.tick(t0 + 80_000)
        assertEquals(RunPhase.SAVING, run.state(t0 + 80_000).phase)
        assertEquals(RunEnd.QUIET, sink.ended.single().endedBy)
    }

    @Test fun oneBootDownStillEndsOnQuietBecauseTheOtherCouldHaveSeenTurns() {
        val t0 = startAndZero()
        boots.connectedSides.remove("L")
        run.onHalfTurn("R", event(1, side = false), t0 + 1000)
        run.tick(t0 + 21_000)
        assertEquals(RunPhase.SAVING, run.state(t0 + 21_000).phase)
    }

    // saving and done ---------------------------------------------------------------------------------------

    @Test fun savingShowsProgressAndTheModeIsRestoredOnlyAfterTheFlashIsSaved() {
        val t0 = startAndZero()
        run.onHalfTurn("L", event(1), t0 + 1000)
        run.onHalfTurn("R", event(2, side = false), t0 + 3000)
        run.onVerdict("L", verdict(Verdict.POSITIVE), t0 + 4000)
        run.onVerdict("L", verdict(Verdict.NEGATIVE), t0 + 5000)
        run.onVerdict("L", verdict(Verdict.NONE), t0 + 6000)
        run.stop(t0 + 7000)
        boots.progress["L"] = 40; boots.progress["R"] = 12
        boots.calls.clear()
        run.tick(t0 + 8000)
        val saving = run.state(t0 + 8000)
        assertEquals(RunPhase.SAVING, saving.phase)
        assertEquals(mapOf<String, Int?>("L" to 40, "R" to 12), saving.saveProgress)
        assertTrue(boots.calls.none { it.startsWith("mode") })       // not yet
        boots.saved = true
        run.tick(t0 + 9000)
        val done = run.state(t0 + 9000)
        assertEquals(RunPhase.DONE, done.phase)
        assertEquals(listOf("mode L false", "mode R false"), boots.calls.filter { it.startsWith("mode") })
        val summary = done.summary!!
        assertEquals(mapOf("L" to 1, "R" to 1), summary.turns)
        assertEquals(Triple(1, 1, 1), Triple(summary.matched, summary.offTarget, summary.unclear))
        assertEquals(7000L, summary.durationMs)
        assertEquals(RunEnd.MANUAL, summary.endedBy)
        assertFalse(summary.rawDataCapped)
    }

    @Test fun aBootAlreadyOnSnowIsLeftOnSnow() {
        boots.modes["L"] = true
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        boots.saved = true
        run.tick(t0 + 2000)
        assertEquals(true, boots.modes["L"])
        assertEquals(false, boots.modes["R"])
    }

    @Test fun aRestoreThatCannotBeDeliveredIsRetriedWhenTheBootReturns() {
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        boots.refuseMode.add("L")
        boots.saved = true
        boots.calls.clear()
        run.tick(t0 + 2000)
        assertEquals(RunPhase.DONE, run.state(t0 + 2000).phase)
        assertEquals(true, boots.modes["L"])          // the command was refused, so L is still on snow
        assertEquals(false, boots.modes["R"])
        boots.refuseMode.clear()
        boots.calls.clear()
        run.retryRestore()
        assertEquals(listOf("mode L false"), boots.calls)
        assertEquals(false, boots.modes["L"])
        boots.calls.clear()
        run.retryRestore()                            // nothing left to do
        assertTrue(boots.calls.isEmpty())
    }

    @Test fun aRestoreStillPendingWhenTheNextRunStartsIsStillHonouredAfterThatRun() {
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        boots.refuseMode.add("L"); boots.saved = true
        run.tick(t0 + 2000)
        boots.refuseMode.clear()
        run.reset()
        assertNull(run.start(t0 + 3000))
        boots.zeroedSides.addAll(listOf("L", "R"))
        run.tick(t0 + 4000)
        run.stop(t0 + 5000)
        boots.saved = true
        run.tick(t0 + 6000)
        assertEquals(false, boots.modes["L"])         // back to training, as it was before the first run
    }

    @Test fun savingCanBeLeftToFinishLaterWithoutTouchingTheModes() {
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        boots.calls.clear()
        run.abandonSave(t0 + 2000)
        val state = run.state(t0 + 2000)
        assertEquals(RunPhase.DONE, state.phase)
        assertTrue(state.message.contains("carries on in the background"))
        assertTrue(boots.calls.none { it.startsWith("mode") })
        assertNotNull(state.summary)
    }

    @Test fun savingThatNeverFinishesGivesUpAfterThirtyMinutes() {
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        run.tick(t0 + 1000 + RunController.SAVE_TIMEOUT_MS - 1)
        assertEquals(RunPhase.SAVING, run.state(t0 + 1000 + RunController.SAVE_TIMEOUT_MS - 1).phase)
        run.tick(t0 + 1000 + RunController.SAVE_TIMEOUT_MS)
        assertEquals(RunPhase.DONE, run.state(t0 + 1000 + RunController.SAVE_TIMEOUT_MS).phase)
    }

    @Test fun theCheapPhaseAccessorMatchesTheState() {
        assertEquals(RunPhase.READY, run.phase())
        run.start(0)
        assertEquals(RunPhase.ZEROING, run.phase())
    }

    @Test fun aFailedSaveKeepsTheBootsAsTheyAreAndSaysSo() {
        val t0 = startAndZero()
        run.stop(t0 + 1000)
        boots.failed = true
        boots.calls.clear()
        run.tick(t0 + 2000)
        val state = run.state(t0 + 2000)
        assertEquals(RunPhase.DONE, state.phase)
        assertTrue(state.message.contains("Saving did not finish"))
        assertTrue(boots.calls.none { it.startsWith("mode") })
        assertNotNull(state.summary)
    }

    @Test fun theSummaryNotesAFullFlashAndGapSeconds() {
        val t0 = startAndZero()
        boots.connectedSides.remove("L")
        run.tick(t0 + 1000)
        boots.connectedSides.add("L")
        run.tick(t0 + 41_000)
        run.stop(t0 + 50_000)
        boots.full.add("R"); boots.saved = true
        run.tick(t0 + 51_000)
        val summary = run.state(t0 + 51_000).summary!!
        assertTrue(summary.rawDataCapped)
        assertEquals(40, summary.gapSeconds)
    }

    @Test fun doneGoesBackToReadyWithAFreshRun() {
        val t0 = startAndZero()
        run.stop(t0 + 1000); boots.saved = true; run.tick(t0 + 2000)
        run.reset()
        val state = run.state(t0 + 3000)
        assertEquals(RunPhase.READY, state.phase)
        assertNull(state.summary)
        assertEquals(mapOf("L" to 0, "R" to 0), state.turns)
    }

    // demo and the mode switch ------------------------------------------------------------------------------

    @Test fun aDemoRunNeedsNoBootsAndSendsNoCommands() {
        boots.connectedSides.clear()
        assertNull(run.start(0, demoRun = true))
        run.tick(1000)
        assertEquals(RunPhase.RUNNING, run.state(1000).phase)
        run.onHalfTurn("L", event(1), 2000)
        run.stop(3000)
        run.tick(4000)
        val saving = run.state(4000)
        assertEquals(RunPhase.SAVING, saving.phase)
        assertEquals(16, saving.saveProgress["L"])      // 1000 ms of 6000
        run.tick(3000 + RunController.DEMO_SAVE_MS)
        assertEquals(RunPhase.DONE, run.state(3000 + RunController.DEMO_SAVE_MS).phase)
        assertTrue(boots.calls.none { it.startsWith("mode") || it.startsWith("zero") })
        assertFalse(run.state(10_000).summary!!.rawDataCapped)
    }

    @Test fun theModeSwitchOnlyActsOutsideARunAndOnlyOnConnectedBoots() {
        boots.connectedSides.remove("R")
        run.setOnSnowMode(true)
        assertEquals(listOf("mode L true"), boots.calls)
        boots.calls.clear()
        run.start(0)                      // fails: right boot missing
        boots.connectedSides.add("R")
        run.start(0)
        boots.calls.clear()
        run.setOnSnowMode(false)          // zeroing: ignored
        assertTrue(boots.calls.isEmpty())
    }
}
