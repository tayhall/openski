package com.openski.android

/**
 * Boot numbers for one stretch of a ski day (a descent), from every sensor session that overlaps it. Counts are per
 * boot and include only boots that have data of that kind; a boot without a saved calibration has no roll, so it has
 * no movement or heading count rather than a zero. Means are null when nothing was measured. EXPERIMENTAL dry-ski
 * boot roll descriptors, not ski turn classification, not technique.
 */
data class BootRangeStats(
    /** Sensor sessions overlapping the stretch. */
    val sessions: Int,
    /** Milliseconds of the stretch covered by those sessions; cadences are per minute of this time. */
    val overlapMs: Long,
    /** Detected dry-roll movements ([DrySkiAnalysis.detect]) starting inside the stretch, per boot. */
    val movements: Map<String, Int>,
    /** Experimental heading-rate turn candidates ([HeadingTurnAnalysis]) starting inside the stretch, per boot. */
    val headingTurns: Map<String, Int>,
    /** Half-turns the boots sent during a run (ski_v0 events, by phone receipt time), per boot. */
    val halfTurns: Map<String, Int>,
    /** Boot agreement pairs (left and right candidates overlapping) starting inside the stretch. */
    val agreementPairs: Int,
    val meanDerivativeSimilarity: Double?, val meanRollCorrelation: Double?, val meanWedgeFraction: Double?,
    /** Torso rows (phone in a pocket against a boot candidate) with metrics, starting inside the stretch. */
    val torsoTurns: Int,
    /** Mean phone gyro RMS over those rows: lower means a quieter upper body (or pocket). */
    val meanTorsoGyroRmsDps: Double?,
    val meanTorsoHorizontalAccelSd: Double?,
    /** Why something is missing, for example a refused torso analysis or a session that could not be analysed. */
    val notes: List<String>,
) {
    /** Events per minute of covered time; null when the coverage is under [DayBootStats.MIN_CADENCE_MS]. */
    fun perMinute(count: Int): Double? = if (overlapMs < DayBootStats.MIN_CADENCE_MS) null else count / (overlapMs / 60_000.0)

    /** The most useful turn count to show: half-turns when the boots sent them, else dry-roll movements; per boot. */
    val turnCounts: Map<String, Int> get() = halfTurns.ifEmpty { movements }
    val turnSource: String get() = if (halfTurns.isNotEmpty()) "half-turns" else "movements"
    val hasNumbers: Boolean get() = movements.isNotEmpty() || headingTurns.isNotEmpty() || halfTurns.isNotEmpty() ||
        agreementPairs > 0 || torsoTurns > 0
}

/**
 * Event times of one analysed session, kept small so a whole day's sessions can stay in memory without their samples.
 * All times are on each boot's analysis timeline (phone wall clock, phone-receipt referenced).
 */
class SessionBootEvents(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val movements: Map<String, List<Double>>,
    val headingTurns: Map<String, List<Double>>,
    val halfTurns: Map<String, List<Long>>,
    val agreement: List<BootAgreementPair>,
    val torso: List<TorsoTurnRow>,
    /** Null when torso metrics were computed, else the refusal reason. */
    val torsoRefusal: String?,
    val notes: List<String> = emptyList(),
) {
    companion object {
        /**
         * Pulls the event times out of [a]. Boots with no orientation (no saved calibration) are left out of the
         * movement and heading maps, so they show as missing, not as zero.
         */
        fun from(a: SessionBootAnalysis, nowMs: Long): SessionBootEvents {
            val s = a.data.session
            val withRoll = a.rollBySide.filterValues { it.isNotEmpty() }.keys
            val notes = ArrayList<String>()
            if (withRoll.isEmpty()) notes.add("no saved boot calibration, so no boot roll")
            // The same per-boot user offset the timeline (and so every movement) carries.
            fun offset(side: String) = if (side == "L") s.leftOffsetMs else s.rightOffsetMs
            val halfTurns = a.data.run?.events.orEmpty().groupBy { it.side }.mapValues { (side, rows) -> rows.map { it.receivedMs + offset(side) } }
            return SessionBootEvents(s.id, s.startedAtMs, s.endedAtMs ?: nowMs,
                withRoll.associateWith { side -> a.detections[side]?.movements.orEmpty().map { it.startMs } },
                withRoll.associateWith { side -> a.headingTurns[side].orEmpty().map { it.startMs } },
                halfTurns, a.agreementPairs, a.torso.rows, if (a.torso.refused) a.torso.reason else null, notes)
        }

        /** A session that could not be analysed: it overlaps, but contributes only a note. */
        fun failed(sessionId: String, startMs: Long, endMs: Long, reason: String) = SessionBootEvents(sessionId, startMs, endMs,
            emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), null, listOf(reason))
    }
}

/** Selects and summarises boot data for parts of a ski day. Pure, JVM-tested. */
object DayBootStats {
    /** Under this much covered time a cadence is not given (too few events to mean anything). */
    const val MIN_CADENCE_MS = 20_000L
    const val NOTE = "EXPERIMENTAL: boot numbers are dry-ski boot roll descriptors from the sensor sessions that overlap " +
        "this stretch in time (phone clock), not ski turn classification and not a technique score. Movements are counted " +
        "over the whole session, not only inside test-marker windows as the session export does."

    const val NOTE_STILL_RECORDING = "session still recording, boot stats not included"

    /** Real sensor sessions (never synthetic demo data) whose time overlaps [startMs, endMs]. An open session runs to [nowMs]. */
    fun overlapping(sessions: List<SkiSession>, startMs: Long, endMs: Long, nowMs: Long): List<SkiSession> =
        sessions.filter { it.origin == "sensor" && it.startedAtMs < endMs && (it.endedAtMs ?: nowMs) > startMs }

    /**
     * The overlapping sessions to analyse (ended ones only) and, for each one still open, a stand-in that carries only
     * [NOTE_STILL_RECORDING]: reading a session while the recorder writes to it could stall the recorder, so its boot
     * numbers wait until it has ended.
     */
    fun plan(sessions: List<SkiSession>, startMs: Long, endMs: Long, nowMs: Long): Pair<List<SkiSession>, List<SessionBootEvents>> {
        val over = overlapping(sessions, startMs, endMs, nowMs)
        return over.filter { it.endedAtMs != null } to
            over.filter { it.endedAtMs == null }.map { SessionBootEvents.failed(it.id, it.startedAtMs, nowMs, NOTE_STILL_RECORDING) }
    }

    /** The boot movement detector over a whole session (no test-marker windows); used for ski days. */
    fun detectWholeSession(rolls: List<BootRoll>, @Suppress("UNUSED_PARAMETER") markers: List<TestMarker>): DryDetection =
        DrySkiAnalysis.detect(rolls)

    /** Stats for [startMs, endMs) from [sessions]; null when none of them overlaps it. */
    fun forRange(sessions: List<SessionBootEvents>, startMs: Long, endMs: Long): BootRangeStats? {
        val over = sessions.filter { it.startMs < endMs && it.endMs > startMs }
        if (over.isEmpty() || endMs <= startMs) return null
        fun inRange(t: Double) = t >= startMs && t < endMs
        fun count(pick: (SessionBootEvents) -> Map<String, List<Double>>): Map<String, Int> {
            val out = sortedMapOf<String, Int>()
            over.forEach { s -> pick(s).forEach { (side, times) -> out[side] = (out[side] ?: 0) + times.count(::inRange) } }
            return out
        }
        val halfTurns = sortedMapOf<String, Int>()
        over.forEach { s -> s.halfTurns.forEach { (side, times) -> halfTurns[side] = (halfTurns[side] ?: 0) + times.count { it in startMs until endMs } } }
        val pairs = over.flatMap { s -> s.agreement.filter { inRange(it.startMs) } }
        val torso = over.flatMap { s -> s.torso.filter { inRange(it.startMs) && it.hasMetrics } }
        val notes = LinkedHashSet<String>()
        over.forEach { s ->
            notes.addAll(s.notes)
            s.torsoRefusal?.let { notes.add("torso metrics: $it") }
        }
        val overlap = coveredMs(over, startMs, endMs)
        fun mean(v: List<Double>): Double? = v.filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()
        return BootRangeStats(over.size, overlap, count { it.movements }, count { it.headingTurns }, halfTurns.toMap(),
            pairs.size, mean(pairs.mapNotNull { it.derivativeSimilarity }), mean(pairs.mapNotNull { it.rollCorrelation }),
            mean(pairs.mapNotNull { it.wedgeFraction }), torso.size, mean(torso.mapNotNull { it.gyroRmsDps }),
            mean(torso.mapNotNull { it.horizontalAccelSd }), notes.toList())
    }

    /** Length of the union of the sessions' spans inside [startMs, endMs). */
    fun coveredMs(sessions: List<SessionBootEvents>, startMs: Long, endMs: Long): Long {
        val spans = sessions.map { maxOf(it.startMs, startMs) to minOf(it.endMs, endMs) }.filter { it.second > it.first }.sortedBy { it.first }
        var total = 0L; var curStart = Long.MIN_VALUE; var curEnd = Long.MIN_VALUE
        for ((a, b) in spans) {
            if (a > curEnd) { if (curEnd > curStart) total += curEnd - curStart; curStart = a; curEnd = b }
            else curEnd = maxOf(curEnd, b)
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }
}
