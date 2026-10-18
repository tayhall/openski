package com.openski.android

import android.os.Handler
import android.os.SystemClock

/**
 * Schedules time-sync pings and feeds the replies to one [ClockSync] per boot (docs/clock-sync-design.md).
 * Everything runs on the main thread. The pure logic (fit, drift monitor, burst plan) is in ClockSync.kt and
 * unit tested; this class is the thin Android wiring and is only checked on hardware.
 *
 * Cadence: a burst of [ClockSyncDefaults.BURST_PINGS] pings, [ClockSyncDefaults.BURST_SPACING_MS] apart, at connect
 * and at recording start (boots interleaved), one ping per [ClockSyncDefaults.PERIODIC_PING_MS] while recording,
 * and an extra burst for a boot whose drift monitor fires. Connection priority is raised to high for a burst and set
 * back to balanced afterwards. A boot answering result 6 gets no further pings: it is "gesture sync only".
 */
class ClockSyncCoordinator(
    private val handler: Handler,
    private val client: (String) -> BleSensorClient?,
    private val firmwareHasRecorder: (String) -> Boolean,
    /** Called with each new pair so the service can store it when a session is recording. */
    private val onPair: (StoredClockPair) -> Unit,
    private val onGestureOnly: (String) -> Unit,
) {
    private class Pending(val side: String, val createdMs: Long) { var sendNs = 0L }

    private val syncs = mutableMapOf<String, ClockSync>()
    private val addresses = mutableMapOf<String, String>()
    private val gestureOnly = mutableSetOf<String>()
    private val liveMonitors = mutableMapOf<String, LiveDisturbanceMonitor>()
    private val pending = mutableMapOf<Int, Pending>()
    private val burstTasks = mutableMapOf<String, MutableList<Runnable>>()
    private val lastExtraBurstMs = mutableMapOf<String, Long>()
    private var nextToken = 0
    private var lastPeriodicMs = 0L

    fun isGestureOnly(side: String) = side in gestureOnly
    fun sync(side: String): ClockSync? = syncs[side]

    private fun ready(side: String) = client(side)?.isReady == true && firmwareHasRecorder(side) && side !in gestureOnly

    /** A new connection to [address]; state is kept for the same sensor (so gaps can be interpolated) and dropped for another one. */
    fun connecting(side: String, address: String) {
        if (addresses[side] != address) { syncs.remove(side); addresses[side] = address }
        gestureOnly.remove(side)
        dropSide(side)
    }

    fun disconnected(side: String) = dropSide(side)

    private fun dropSide(side: String) {
        burstTasks.remove(side)?.forEach(handler::removeCallbacks)
        pending.entries.removeAll { it.value.side == side }
        liveMonitors.remove(side)
    }

    /** The boot is ready (called after the recorder is known): burst at connect. */
    fun becameReady(side: String, supported: Boolean) { if (supported) burst(listOf(side)) }

    fun recordingStarted(nowMs: Long) { lastPeriodicMs = nowMs }

    /** Pairs worth storing with a session that has just started: the current fit window of each boot. */
    fun recentPairs(): List<StoredClockPair> = syncs.values.flatMap { s -> s.pairs.takeLast(ClockSyncDefaults.WINDOW_PAIRS).map { it.stored() } }

    fun burst(sides: List<String>) {
        val sel = sides.filter(::ready).sorted()
        if (sel.isEmpty()) return
        sel.forEach { side ->
            burstTasks.remove(side)?.forEach(handler::removeCallbacks)
            burstTasks[side] = mutableListOf()
            client(side)?.setHighPriority(true)
        }
        val plan = PingPlan.burst(sel)
        plan.forEach { slot ->
            val task = Runnable { sendPing(slot.boot) }
            burstTasks[slot.boot]?.add(task)
            handler.postDelayed(task, slot.delayMs)
        }
        val endDelay = (plan.maxOfOrNull { it.delayMs } ?: 0L) + 1_500L
        sel.forEach { side ->
            val restore = Runnable { client(side)?.setHighPriority(false); burstTasks.remove(side) }
            burstTasks[side]?.add(restore)
            handler.postDelayed(restore, endDelay)
        }
    }

    private fun sendPing(side: String) {
        val c = client(side)?.takeIf { ready(side) } ?: return
        val nowMs = SystemClock.elapsedRealtime()
        pending.entries.removeAll { nowMs - it.value.createdMs > 10_000 }
        val token = nextToken
        nextToken = (nextToken + 1) and 0xffff
        val entry = Pending(side, nowMs)
        pending[token] = entry
        if (!c.ping(token) { ns -> entry.sendNs = ns }) pending.remove(token)
    }

    fun onPong(side: String, pong: ClockPong, recvNs: Long) {
        val entry = pending.remove(pong.token)?.takeIf { it.side == side && it.sendNs > 0 } ?: return   // unknown or foreign token
        if (pong.unsupported) {
            gestureOnly.add(side)
            dropSide(side)
            client(side)?.setHighPriority(false)
            onGestureOnly(side)
            return
        }
        if (!pong.ok) return
        val sync = syncs.getOrPut(side) { ClockSync(side) }
        val pair = PingPair(side, pong.token, entry.sendNs, recvNs, pong.sensorUs)
        val result = sync.add(pair)
        onPair(StoredClockPair(side, pair.token, pair.tSendNs, pair.tRecvNs, pair.sensorUs, pair.rttNs, result.accepted))
        if (result.events.isNotEmpty()) extraBurst(side)
    }

    /** Live samples feed the gap and burst check of the drift monitor. */
    fun onLiveSample(side: String, sequence: Int) {
        if (!ready(side)) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val d = liveMonitors.getOrPut(side) { LiveDisturbanceMonitor() }.onSample(sequence, nowNs) ?: return
        syncs.getOrPut(side) { ClockSync(side) }.markUncertain(d.startNs, d.endNs, d.reason)
        extraBurst(side)
    }

    private fun extraBurst(side: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastExtraBurstMs[side] ?: -ClockSyncDefaults.MIN_EXTRA_BURST_INTERVAL_MS) < ClockSyncDefaults.MIN_EXTRA_BURST_INTERVAL_MS) return
        lastExtraBurstMs[side] = now
        burst(listOf(side))
    }

    /** Call from the service tick: one ping per minute per boot while recording, the boots half a second apart. */
    fun tick(nowMs: Long, recording: Boolean) {
        if (!recording || nowMs - lastPeriodicMs < ClockSyncDefaults.PERIODIC_PING_MS) return
        lastPeriodicMs = nowMs
        syncs.keys.plus(addresses.keys).distinct().filter(::ready).sorted().forEachIndexed { i, side ->
            handler.postDelayed({ sendPing(side) }, i * 500L)
        }
    }

    fun stop() {
        burstTasks.values.flatten().forEach(handler::removeCallbacks)
        burstTasks.clear()
    }

    private fun RecordedPair.stored() = StoredClockPair(pair.boot, pair.token, pair.tSendNs, pair.tRecvNs, pair.sensorUs, pair.rttNs, accepted)
}
