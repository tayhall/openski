package com.openski.android

/**
 * Wires [SkiDayReport]'s boot lambda to [LocalSessionStore]: the sensor sessions overlapping a day are loaded and
 * analysed once ([SessionBootAnalysis], movements over the whole session), only their event times are kept
 * ([SessionBootEvents]), and every descent is answered from those. Blocking and possibly slow (each session is loaded
 * with its samples): call it on a background thread. Synthetic demo sessions are never attached to a day.
 */
object DayBootSource {
    fun forDay(store: LocalSessionStore, startMs: Long, endMs: Long, correction: (String) -> AccelCorrection?,
               nowMs: Long = System.currentTimeMillis()): (Long, Long) -> BootRangeStats? {
        val sessions = DayBootStats.overlapping(store.listSessions(), startMs, endMs, nowMs)
        if (sessions.isEmpty()) return { _, _ -> null }
        val events = sessions.map { s ->
            val end = s.endedAtMs ?: nowMs
            try {
                val data = store.loadData(s.id) ?: return@map SessionBootEvents.failed(s.id, s.startedAtMs, end, "session no longer stored")
                SessionBootEvents.from(SessionBootAnalysis.build(data, correction, DayBootStats::detectWholeSession), nowMs)
            } catch (e: Exception) {
                SessionBootEvents.failed(s.id, s.startedAtMs, end, "boot analysis failed: ${e.message ?: e.javaClass.simpleName}")
            } catch (_: OutOfMemoryError) {
                // The session's samples are dropped with the failed attempt, so the day screen keeps working.
                SessionBootEvents.failed(s.id, s.startedAtMs, end, "session too large to analyse on this phone")
            }
        }
        return { a, b -> DayBootStats.forRange(events, a, b) }
    }
}
