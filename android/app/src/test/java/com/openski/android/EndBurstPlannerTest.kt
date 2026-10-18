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

    @Test fun duplicateSidesArePingedOnce() {
        val d = EndBurstPlanner().request("s1", false, listOf(boot("L"), boot("L"))) as EndBurstPlanner.Decision.Burst
        assertEquals(ClockSyncDefaults.END_BURST_PINGS, d.slots.size)
    }
}
