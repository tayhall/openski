package com.openski.android

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/** Simulated boot clocks: sensor microseconds run at (1 + drift) against the phone clock, with an offset. */
class ClockSyncTest {
    private val ms = ClockSyncDefaults.MS
    private val s = ClockSyncDefaults.S

    private class SimBoot(val name: String, val offsetNs: Long, val driftPpm: Double) {
        private val scale get() = 1000.0 * (1 + driftPpm * 1e-6)
        fun sensorUsAt(phoneNs: Long) = ((phoneNs - offsetNs) / scale).toLong()
        fun truePhoneNs(sensorUs: Long) = offsetNs + (sensorUs * scale).toLong()
    }

    private val rng = Random(7)
    private var token = 0

    /** One way delay: a fixed base plus an exponential tail, so the best pair of a burst is nearly symmetric. */
    private fun delay(baseMs: Double = 15.0, tailMs: Double = 6.0) = ((baseMs - tailMs * ln(1 - rng.nextDouble())) * ms).toLong()

    private fun ping(boot: SimBoot, sendNs: Long, up: Long = delay(), down: Long = delay()) =
        PingPair(boot.name, token++ and 0xffff, sendNs, sendNs + up + down, boot.sensorUsAt(sendNs + up))

    private fun burst(sync: ClockSync, boot: SimBoot, startNs: Long, count: Int = 8) =
        (0 until count).map { sync.add(ping(boot, startNs + it * 100 * ms)) }

    private val phoneStart = 5_000_000_000_000L

    @Test fun recoversOffsetFromOneBurstWithinAFewMilliseconds() {
        val boot = SimBoot("L", offsetNs = phoneStart - 100 * s, driftPpm = 0.0)
        val sync = ClockSync("L")
        burst(sync, boot, phoneStart)
        val fit = sync.fit()!!
        val sensor = boot.sensorUsAt(phoneStart + 2 * s)
        val err = fit.toPhoneNs(sensor) - boot.truePhoneNs(sensor)
        assertTrue("offset error ${err / 1000} us", abs(err) < 4 * ms)
        assertEquals(0.0, fit.driftPpm, 0.0)   // no pairs far enough apart to fit a slope
        assertTrue(fit.baseUncertaintyNs >= fit.bestRttNs / 2)
    }

    @Test fun aShortBaselineDoesNotFitDriftSoItCannotSwingToTheClamp() {
        // One burst, then a single ping a minute later: the two pairs carry several ms of jitter each, which over
        // 60 s reads as hundreds of ppm. Below the minimum baseline the drift stays zero instead.
        repeat(20) { trial ->
            val boot = SimBoot("L", phoneStart - 50 * s, 20.0)
            val sync = ClockSync("L")
            burst(sync, boot, phoneStart)
            sync.add(ping(boot, phoneStart + 60 * s))
            assertEquals("trial $trial", 0.0, sync.fit()!!.driftPpm, 0.0)
        }
    }

    @Test fun recoversSkewOverTenMinutes() {
        for (drift in listOf(-40.0, 25.0, 60.0)) {
            val boot = SimBoot("L", phoneStart - 50 * s, drift)
            val sync = ClockSync("L")
            burst(sync, boot, phoneStart)
            for (minute in 1..10) sync.add(ping(boot, phoneStart + minute * 60 * s))
            val fit = sync.fit()!!
            assertEquals("drift", drift, fit.driftPpm, 8.0)
            val sensor = boot.sensorUsAt(phoneStart + 300 * s)
            assertTrue(abs(fit.toPhoneNs(sensor) - boot.truePhoneNs(sensor)) < 5 * ms)
        }
    }

    @Test fun minRttFilteringIgnoresSlowAsymmetricPings() {
        val boot = SimBoot("L", phoneStart - 10 * s, 0.0)
        val sync = ClockSync("L")
        // Six pings with a 400 ms slow uplink (midpoint biased by 200 ms) and two clean ones.
        (0 until 6).forEach { sync.add(ping(boot, phoneStart + it * 100 * ms, up = 415 * ms, down = 15 * ms)) }
        sync.add(ping(boot, phoneStart + 600 * ms, 15 * ms, 15 * ms))
        sync.add(ping(boot, phoneStart + 700 * ms, 16 * ms, 15 * ms))
        val fit = sync.fit()!!
        val sensor = boot.sensorUsAt(phoneStart + 5 * s)
        assertTrue(abs(fit.toPhoneNs(sensor) - boot.truePhoneNs(sensor)) < 3 * ms)
        val naive = sync.pairs.map { it.pair.offsetNs }.average()
        assertTrue("a plain mean would be ${abs(naive - (boot.offsetNs))/ms} ms off", abs(naive - boot.offsetNs) > 100 * ms)
    }

    @Test fun outliersDoNotMoveTheFit() {
        val boot = SimBoot("L", phoneStart - 10 * s, 12.0)
        val sync = ClockSync("L")
        burst(sync, boot, phoneStart)
        for (minute in 1..8) {
            if (minute == 4) sync.add(ping(boot, phoneStart + minute * 60 * s, up = 900 * ms, down = 20 * ms))
            else sync.add(ping(boot, phoneStart + minute * 60 * s))
        }
        val sensor = boot.sensorUsAt(phoneStart + 400 * s)
        assertTrue(abs(sync.fit()!!.toPhoneNs(sensor) - boot.truePhoneNs(sensor)) < 5 * ms)
    }

    @Test fun rejectsInsanePairs() {
        val sync = ClockSync("L")
        assertFalse(sync.add(PingPair("L", 1, 2_000, 1_000, 5)).accepted)                                  // reply before send
        assertFalse(sync.add(PingPair("L", 2, phoneStart, phoneStart + 3 * s, 5)).accepted)                 // 3 s round trip
        assertNull(sync.fit())
        assertEquals(2, sync.pairs.size)   // still kept as raw data
    }

    @Test fun uncertaintyGrowsWithTheGapSinceTheLastGoodPair() {
        val boot = SimBoot("L", phoneStart - 10 * s, 0.0)
        val sync = ClockSync("L")
        burst(sync, boot, phoneStart)
        val timeline = sync.timeline()!!
        val near = timeline.uncertaintyNs(boot.sensorUsAt(phoneStart + 700 * ms))
        val far = timeline.uncertaintyNs(boot.sensorUsAt(phoneStart + 600 * s))
        assertTrue("near $near far $far", far > near + 15 * ms)   // 600 s at 30 ppm is 18 ms
    }

    @Test fun interpolatesAcrossADisconnectGap() {
        val boot = SimBoot("L", phoneStart - 70 * s, 35.0)
        val sync = ClockSync("L")
        burst(sync, boot, phoneStart)
        for (m in 1..3) sync.add(ping(boot, phoneStart + m * 60 * s))
        // Link down for ten minutes, then a burst after reconnect.
        burst(sync, boot, phoneStart + 780 * s)
        for (m in 14..16) sync.add(ping(boot, phoneStart + m * 60 * s))
        val timeline = sync.timeline()!!
        for (at in listOf(300, 420, 600)) {
            val sensor = boot.sensorUsAt(phoneStart + at * s)
            val err = timeline.toPhoneNs(sensor) - boot.truePhoneNs(sensor)
            assertTrue("gap at ${at}s error ${err / 1000} us", abs(err) < 6 * ms)
        }
        // And back: the phone time inverts to the sensor time.
        val sensor = boot.sensorUsAt(phoneStart + 500 * s)
        assertEquals(sensor.toDouble(), timeline.toSensorUs(timeline.toPhoneNs(sensor)), 1.0)
    }

    @Test fun composesTwoBootsAndAddsUncertainties() {
        val a = SimBoot("L", phoneStart - 100 * s, 20.0)
        val b = SimBoot("R", phoneStart - 3_000 * s, -30.0)
        val syncA = ClockSync("L"); val syncB = ClockSync("R")
        burst(syncA, a, phoneStart); burst(syncB, b, phoneStart + 50 * ms)
        for (m in 1..10) { syncA.add(ping(a, phoneStart + m * 60 * s)); syncB.add(ping(b, phoneStart + m * 60 * s + 500 * ms)) }
        val ta = syncA.timeline()!!; val tb = syncB.timeline()!!
        val sensorA = a.sensorUsAt(phoneStart + 330 * s)
        val truth = b.sensorUsAt(a.truePhoneNs(sensorA))
        val mapped = BootAlignment.map(ta, tb, sensorA)
        assertEquals(truth.toDouble(), mapped.value, 6_000.0)    // within 6 ms
        assertEquals(ta.uncertaintyNs(sensorA) + tb.uncertaintyNs(mapped.value.toLong()), mapped.uncertaintyNs)
    }

    @Test fun aSteadyClockRaisesNoAlarm() {
        val boot = SimBoot("L", phoneStart - 10 * s, 30.0)
        val sync = ClockSync("L")
        val events = burst(sync, boot, phoneStart).flatMap { it.events }.toMutableList()
        for (m in 1..30) events += sync.add(ping(boot, phoneStart + m * 60 * s)).events
        assertEquals(emptyList<DriftReason>(), events)
        assertTrue(sync.stretches.isEmpty())
    }

    @Test fun anOffsetJumpFiresTheResidualAlarmAndMarksAStretch() {
        val before = SimBoot("L", phoneStart - 10 * s, 0.0)
        val after = SimBoot("L", phoneStart - 10 * s + 60 * ms, 0.0)   // the boot's clock steps by 60 ms
        val sync = ClockSync("L")
        burst(sync, before, phoneStart)
        for (m in 1..6) sync.add(ping(before, phoneStart + m * 60 * s))
        val result = sync.add(ping(after, phoneStart + 7 * 60 * s))
        assertTrue(result.events.contains(DriftReason.RESIDUAL))
        assertTrue(abs(result.residualNs!!) > 40 * ms)
        assertEquals(1, sync.stretches.size)
        val stretch = sync.stretches.single()
        assertNull(stretch.endNs)
        assertTrue(stretch.contains(phoneStart + 7 * 60 * s))
        assertTrue(sync.isUncertain(phoneStart + 7 * 60 * s + 1))
        assertFalse(sync.isUncertain(phoneStart + 3 * 60 * s))
        assertTrue(stretch.startNs in (phoneStart + 6 * 60 * s)..(phoneStart + 6 * 60 * s + 100 * ms))
    }

    @Test fun aSlopeChangeFiresAnAlarm() {
        val slow = SimBoot("L", phoneStart - 10 * s, 0.0)
        val sync = ClockSync("L")
        burst(sync, slow, phoneStart)
        val all = ArrayList<DriftReason>()
        for (m in 1..12) all += sync.add(ping(slow, phoneStart + m * 60 * s)).events
        assertTrue(all.isEmpty())
        // From minute 12 the crystal runs 150 ppm fast: anchored so the clock is continuous at the change.
        val changeNs = phoneStart + 12 * 60 * s
        val fast = SimBoot("L", slow.truePhoneNs(slow.sensorUsAt(changeNs)) - (slow.sensorUsAt(changeNs) * 1000.0 * (1 + 150e-6)).toLong(), 150.0)
        for (m in 13..40) all += sync.add(ping(fast, phoneStart + m * 60 * s)).events
        assertTrue("events $all", all.isNotEmpty())
        assertTrue(sync.stretches.isNotEmpty())
    }

    @Test fun liveGapsOpenAStretchThatAGoodPingCloses() {
        val boot = SimBoot("L", phoneStart - 10 * s, 0.0)
        val sync = ClockSync("L")
        burst(sync, boot, phoneStart)
        val monitor = LiveDisturbanceMonitor()
        var seq = 100
        var t = phoneStart + 2 * s
        repeat(20) { assertNull(monitor.onSample(seq++ and 0xffff, t)); t += 20 * ms }
        val d = monitor.onSample((seq + 12) and 0xffff, t + 700 * ms)!!   // 12 samples lost and 700 ms of silence
        sync.markUncertain(d.startNs, d.endNs, d.reason)
        assertTrue(sync.isUncertain(t + 100 * ms))
        assertNull(sync.stretches.single().endNs)
        sync.add(ping(boot, d.endNs + 5 * s))
        assertNotNull(sync.stretches.single().endNs)
        assertFalse(sync.isUncertain(d.endNs + 10 * s))
    }

    @Test fun liveMonitorIgnoresNormalAndReorderedFrames() {
        val m = LiveDisturbanceMonitor()
        assertNull(m.onSample(65_534, 0))
        assertNull(m.onSample(65_535, 20 * ms))
        assertNull(m.onSample(0, 40 * ms))              // wraps cleanly
        assertNull(m.onSample(2, 60 * ms))              // one frame lost is below the threshold
        assertNull(m.onSample(1, 80 * ms))              // reordered
        assertEquals(DriftReason.LIVE_GAP, m.onSample(40, 100 * ms)!!.reason)
        assertEquals(DriftReason.LIVE_BURST, m.onSample(41, 100 * ms + 800 * ms)!!.reason)
    }

    @Test fun aRebootStartsANewEpoch() {
        val first = SimBoot("L", phoneStart - 500 * s, 0.0)
        val second = SimBoot("L", phoneStart + 2_000 * s, 0.0)   // sensor time restarts from zero
        val sync = ClockSync("L")
        burst(sync, first, phoneStart)
        val result = burst(sync, second, phoneStart + 2_100 * s).first()
        assertTrue(result.newEpoch)
        assertEquals(2, sync.epochCount)
        val sensor = second.sensorUsAt(phoneStart + 2_102 * s)
        assertTrue(abs(sync.fit()!!.toPhoneNs(sensor) - second.truePhoneNs(sensor)) < 4 * ms)
    }

    @Test fun replayGivesTheSameFitAsTheLivePath() {
        val boot = SimBoot("L", phoneStart - 10 * s, 18.0)
        val live = ClockSync("L")
        burst(live, boot, phoneStart)
        for (m in 1..5) live.add(ping(boot, phoneStart + m * 60 * s))
        val replayed = ClockSync.replay("L", live.pairs.map { it.pair }.reversed())
        assertEquals(live.fit(), replayed.fit())
    }

    @Test fun burstPlanInterleavesBoots() {
        val plan = PingPlan.burst(listOf("R", "L"))
        assertEquals(16, plan.size)
        assertEquals(listOf("R", "L", "R", "L"), plan.take(4).map { it.boot })
        assertEquals(0L, plan[0].delayMs)
        assertEquals(50L, plan[1].delayMs)
        assertEquals(100L, plan[2].delayMs)
        assertEquals(750L, plan.last().delayMs)
        assertEquals(plan.map { it.delayMs }, plan.map { it.delayMs }.sorted())
        assertEquals(8, plan.count { it.boot == "L" })
        assertEquals(listOf(0L, 100L, 200L), PingPlan.burst(listOf("L"), count = 3).map { it.delayMs })
        assertTrue(PingPlan.burst(emptyList()).isEmpty())
    }

    @Test fun pingPairArithmetic() {
        val p = PingPair("L", 1, 1_000_000_000, 1_040_000_000, 5_000_000)
        assertEquals(40 * ms, p.rttNs)
        assertEquals(1_020_000_000, p.midpointNs)
        assertEquals(1_020_000_000 - 5_000_000_000L, p.offsetNs)
    }
}
