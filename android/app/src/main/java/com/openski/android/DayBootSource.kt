package com.openski.android

import java.util.concurrent.ConcurrentHashMap

/**
 * Wires [SkiDayReport]'s boot lambda to [LocalSessionStore]: the ended sensor sessions overlapping a day are read in
 * chunks ([LocalSessionStore.loadDataInChunks]) and analysed once ([SessionBootAnalysis], movements over the whole
 * session); only their event times are kept ([SessionBootEvents]) and cached per session (until the session's end time
 * or calibration changes), and every descent is answered from those. A session still recording is not read at all
 * (reading it could stall the recorder): it only adds the note [DayBootStats.NOTE_STILL_RECORDING]. Blocking: call it
 * on a background thread. Synthetic demo sessions are never attached to a day.
 */
object DayBootSource {
    private class Cached(val key: String, val events: SessionBootEvents)
    private val cache = ConcurrentHashMap<String, Cached>()
    /** Bounds the cache; event times are small, but a phone may keep many sessions. */
    private const val MAX_CACHED = 64

    fun forDay(store: LocalSessionStore, startMs: Long, endMs: Long, correction: (String) -> AccelCorrection?,
               nowMs: Long = System.currentTimeMillis()): (Long, Long) -> BootRangeStats? {
        val (closed, stillRecording) = DayBootStats.plan(store.listSessions(), startMs, endMs, nowMs)
        if (closed.isEmpty() && stillRecording.isEmpty()) return { _, _ -> null }
        val events = closed.map { s -> eventsFor(store, s, correction, nowMs) } + stillRecording
        return { a, b -> DayBootStats.forRange(events, a, b) }
    }

    private fun eventsFor(store: LocalSessionStore, s: SkiSession, correction: (String) -> AccelCorrection?, nowMs: Long): SessionBootEvents {
        val end = s.endedAtMs ?: nowMs
        // Anything that changes the analysis is in the key: the end, the offsets, calibration, review settings and corrections.
        val key = listOf(s.endedAtMs, s.leftOffsetMs, s.rightOffsetMs, s.calibrationJson, s.analysisJson,
            correction("L")?.toStorage(), correction("R")?.toStorage()).joinToString("|")
        cache[s.id]?.takeIf { it.key == key }?.let { return it.events }
        val events = try {
            val data = store.loadDataInChunks(s.id) ?: return SessionBootEvents.failed(s.id, s.startedAtMs, end, "session no longer stored")
            SessionBootEvents.from(SessionBootAnalysis.build(data, correction, DayBootStats::detectWholeSession), nowMs)
        } catch (e: Exception) {
            return SessionBootEvents.failed(s.id, s.startedAtMs, end, "boot analysis failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (_: OutOfMemoryError) {
            // The session's samples are dropped with the failed attempt, so the day screen keeps working.
            return SessionBootEvents.failed(s.id, s.startedAtMs, end, "session too large to analyse on this phone")
        }
        if (cache.size >= MAX_CACHED) cache.clear()
        cache[s.id] = Cached(key, events)
        return events
    }
}
