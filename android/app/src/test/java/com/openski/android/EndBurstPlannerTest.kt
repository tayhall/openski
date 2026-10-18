package com.openski.android

import org.junit.Assert.*
import org.junit.Test

class EndBurstPlannerTest {
    private fun boot(side: String, connected: Boolean = true, timeSync: Boolean = true) = EndBurstPlanner.Boot(side, connected, timeSync)
    private val both = listOf(boot("R"), boot("L"))

    @Test fun twoBootsGetEightInterleavedPingsEachOverAboutThreeSeconds() {
        val d = EndBurstPlanner().request("s1", false, both) as EndBurstPlanner.Decision.Burst
        assertEquals(8, d.slots.count { it.boot == "L" })
        assertEquals(8, d.slots.count { it.boot == "R" })
        assertEquals(listOf("L", "R", "L", "R"), d.slots.take(4).map { it.boot })
        assertEquals(listOf(0L, 200L, 400L, 600L), d.slots.take(4).map { it.delayMs })
        val last = d.slots.maxOf { it.delayMs }
        assertEquals(3_000L, last)
        assertEquals(last + ClockSyncDefaults.END_BURST_REPLY_GRACE_MS, d.saveDelayMs)
        // All pings of the burst stay inside one fit cluster, so min-RTT filtering applies across the whole burst.
        assertTrue(last * ClockSyncDefaults.MS < ClockSyncDefaults.CLUSTER_GAP_NS)
    }

    @Test fun theSaveIsNeverHeldLongerThanTheBurst() {
        val planner = EndBurstPlanner()
        listOf(listOf(boot("L")), both, listOf(boot("L"), boot("R"), boot("X"))).forEachIndexed { i, boots ->
            val d = EndBurstPlanner().request("s$i", false, boots) as EndBurstPlanner.Decision.Burst
            assertTrue(d.saveDelayMs <= planner.maxDelayMs)
            assertTrue(d.slots.all { it.delayMs < d.saveDelayMs })
        }
        assertTrue(planner.maxDelayMs <= 3_500L)
    }

    @Test fun theBurstIsScheduledOncePerSession() {
        val planner = EndBurstPlanner()
        assertTrue(planner.request("s1", false, both) is EndBurstPlanner.Decision.Burst)
        assertEquals(EndBurstPlanner.Decision.AlreadyScheduled, planner.request("s1", false, both))
        assertEquals(EndBurstPlanner.Decision.AlreadyScheduled, planner.request("s1", false, emptyList()))
        assertTrue(planner.request("s2", false, both) is EndBurstPlanner.Decision.Burst)
    }

    @Test fun testAndDrillSessionsSaveAtOnce() {
        val planner = EndBurstPlanner()
        assertEquals(EndBurstPlanner.Decision.SaveNow, planner.request("t1", true, both))
        // A skipped request does not count as the session's burst.
        assertEquals(EndBurstPlanner.Decision.SaveNow, planner.request("t1", true, both))
    }

    @Test fun disconnectedAndGestureOnlyBootsAreSkipped() {
        val planner = EndBurstPlanner()
        val d = planner.request("s1", false, listOf(boot("L", connected = false), boot("R"))) as EndBurstPlanner.Decision.Burst
        assertEquals(setOf("R"), d.slots.map { it.boot }.toSet())
        val e = planner.request("s2", false, listOf(boot("L", timeSync = false), boot("R"))) as EndBurstPlanner.Decision.Burst
        assertEquals(setOf("R"), e.slots.map { it.boot }.toSet())
        assertEquals(listOf(0L, 400L), e.slots.take(2).map { it.delayMs })
    }

    @Test fun noBootAbleToAnswerMeansSaveNow() {
        val planner = EndBurstPlanner()
        assertEquals(EndBurstPlanner.Decision.SaveNow, planner.request("s1", false, emptyList()))
        assertEquals(EndBurstPlanner.Decision.SaveNow,
            planner.request("s1", false, listOf(boot("L", connected = false), boot("R", timeSync = false))))
        // Nothing was scheduled, so a later stop of the same session can still burst once the boots are back.
        assertTrue(planner.request("s1", false, both) is EndBurstPlanner.Decision.Burst)
    }

    @Test fun otherBurstsAreBlockedForASideOnlyWhileItsEndBurstRuns() {
        // A drift alarm or reconnect burst during the end burst would cancel the end burst's remaining pings.
        val planner = EndBurstPlanner()
        val d = planner.request("s1", false, listOf(boot("L"), boot("R", connected = false)), nowMs = 10_000L) as EndBurstPlanner.Decision.Burst
        assertTrue(planner.blocksOtherBursts("L", 10_000L))
        assertTrue(planner.blocksOtherBursts("L", 10_000L + d.saveDelayMs - 1))
        assertFalse(planner.blocksOtherBursts("L", 10_000L + d.saveDelayMs))
        assertFalse(planner.blocksOtherBursts("R", 10_000L))
        assertFalse(EndBurstPlanner().blocksOtherBursts("L", 0L))
        // Skipped requests block nothing.
        val skipped = EndBurstPlanner()
        skipped.request("t1", true, both, nowMs = 0L)
        assertFalse(skipped.blocksOtherBursts("L", 1L))
    }

    // StopSequencer: the stop, burst and save sequence the service follows.
    private val burst = { EndBurstPlanner.Decision.Burst(listOf(PingPlan.Slot(0, "L")), 3_300L) }

    @Test fun aStopWaitsForTheBurstAndSavesWithTheTapTime() {
        val seq = StopSequencer()
        val wait = seq.stop("s1", 1_000L, burst) as StopSequencer.Action.Wait
        assertEquals(3_300L, wait.delayMs)
        assertTrue(seq.waiting)
        assertEquals(1_000L, seq.deadline(wait.token, "s1"))
        assertFalse(seq.waiting)
    }

    @Test fun aDoubleStopDoesNotPlanASecondBurstOrMoveTheStopTime() {
        val seq = StopSequencer()
        var asked = 0
        val wait = seq.stop("s1", 1_000L) { asked++; burst() } as StopSequencer.Action.Wait
        assertEquals(StopSequencer.Action.Ignore, seq.stop("s1", 2_000L) { asked++; burst() })
        assertEquals(1, asked)
        assertEquals(1_000L, seq.deadline(wait.token, "s1"))
    }

    @Test fun noBurstOrAnAlreadyPlannedBurstWithNoWaitSavesAtOnce() {
        val seq = StopSequencer()
        assertEquals(StopSequencer.Action.SaveNow(5L), seq.stop("s1", 5L) { EndBurstPlanner.Decision.SaveNow })
        assertEquals(StopSequencer.Action.SaveNow(6L), seq.stop("s1", 6L) { EndBurstPlanner.Decision.AlreadyScheduled })
        assertFalse(seq.waiting)
    }

    @Test fun destroyMidBurstSavesNowWithTheTapTime() {
        val seq = StopSequencer()
        val wait = seq.stop("s1", 1_000L, burst) as StopSequencer.Action.Wait
        assertEquals(1_000L, seq.flush("s1"))
        assertNull(seq.deadline(wait.token, "s1"))   // the timer, if it still fired, does nothing
        assertNull(seq.flush("s1"))
    }

    @Test fun aStorageErrorDuringTheBurstDropsTheWaitingSave() {
        val seq = StopSequencer()
        val wait = seq.stop("s1", 1_000L, burst) as StopSequencer.Action.Wait
        seq.cancel()
        assertNull(seq.deadline(wait.token, null))
        // Even if the session id were still around, a cancelled wait never saves.
        assertNull(seq.deadline(wait.token, "s1"))
    }

    @Test fun aLateDeadlineOrAStaleTimerChangesNothing() {
        val seq = StopSequencer()
        val first = seq.stop("s1", 1_000L, burst) as StopSequencer.Action.Wait
        assertEquals(1_000L, seq.deadline(first.token, "s1"))
        assertNull(seq.deadline(first.token, "s1"))   // a second firing after the save
        // The session was closed some other way before the deadline (for example a storage error): no second save.
        val second = seq.stop("s2", 2_000L, burst) as StopSequencer.Action.Wait
        assertNull(seq.deadline(second.token, null))
        // A newer session's wait is not taken by an old timer.
        val third = seq.stop("s3", 3_000L, burst) as StopSequencer.Action.Wait
        assertNull(seq.deadline(second.token, "s3"))
        assertEquals(3_000L, seq.deadline(third.token, "s3"))
    }

    @Test fun anImmediateStopOverridesAWaitingOne() {
        val seq = StopSequencer()
        val wait = seq.stop("s1", 1_000L, burst) as StopSequencer.Action.Wait
        assertEquals(StopSequencer.Action.SaveNow(1_500L), seq.stopNow(1_500L))
        assertNull(seq.deadline(wait.token, "s1"))
    }

    @Test fun duplicateSidesArePingedOnce() {
        val d = EndBurstPlanner().request("s1", false, listOf(boot("L"), boot("L"))) as EndBurstPlanner.Decision.Burst
        assertEquals(ClockSyncDefaults.END_BURST_PINGS, d.slots.size)
    }
}
