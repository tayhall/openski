package com.openski.android

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Stomp-sync of a stored session: builds each boot's acceleration-magnitude series, picks the start and end search
 * windows, runs [ClockAlign] and, when ping pairs exist, compares the result with the ping-based boot-to-boot offset.
 * Pure (no Android types) and experimental; see docs/clock-sync-design.md ("Gesture sync in the app").
 *
 * Left is stream A and right is stream B, so offsets are "right sensor clock minus left sensor clock" in seconds.
 */
object GestureSync {
    /** Seconds at each end of a recording searched for the stomp. */
    const val WINDOW_SEC = 20.0
    /**
     * Seconds at the end of a recording searched for the end stomp. Wider than the start window because a run that
     * ends on its own does so only after 20 s without a half-turn, so a stomp made as the skier stops lands about
     * 20 s or more before the last sample.
     */
    const val END_WINDOW_SEC = 45.0
    /** A recording shorter than this cannot hold two well separated windows. */
    const val MIN_SPAN_SEC = 45.0
    /** Extra seconds either side of the second boot's window when no ping estimate exists. */
    const val DEFAULT_MARGIN_SEC = 5.0
    /** Extra seconds either side of the mapped window when pings exist (plus twice their uncertainty). */
    const val PING_MARGIN_SEC = 1.0
    const val NOTE = "Experimental. Stomp sync finds a sharp acceleration transient near the start and end of each boot's " +
        "recording and aligns them; it refuses rather than guesses. The ping comparison is an independent cross-check, " +
        "and the ping estimate may carry a bias of about half a connection interval. Raw data is unchanged."

    enum class Status { MEASURED, REFUSED, TOO_SHORT, MISSING_BOOT, CLOCK_RESET }

    class Report(
        val status: Status,
        val reject: ClockAlign.Reject?,
        val result: ClockAlign.Result?,
        /** Why nothing was measured, in words. Null when [status] is MEASURED. */
        val reason: String?,
        /** Ping-based right-minus-left offset in seconds at [pingRefSec] (left sensor seconds); null without pairs. */
        val pingOffsetSec: Double?,
        val pingRefSec: Double?,
        val pingUncertaintySec: Double?,
        /** Ping offset at the end of the recording, for a ping-based drift. */
        val pingEndOffsetSec: Double?,
        val pingEndRefSec: Double?,
        /** Stomp offset minus ping offset at the same left time, in ms; null unless both exist. */
        val disagreementMs: Double?,
        val windowsUsedPings: Boolean,
    ) {
        val ok get() = status == Status.MEASURED
    }

    fun rejectText(r: ClockAlign.Reject) = when (r) {
        ClockAlign.Reject.NO_TRANSIENT -> "no clear stomp at one end in one of the boots"
        ClockAlign.Reject.AMBIGUOUS -> "more than one comparable stomp in a window"
        ClockAlign.Reject.NO_MATCH -> "the two boots' stomps could not be matched"
        ClockAlign.Reject.LOW_CORRELATION -> "the two boots' stomps looked too different"
        ClockAlign.Reject.WINDOWS_TOO_CLOSE -> "the start and end stomps are too close together"
        ClockAlign.Reject.DRIFT_IMPLAUSIBLE -> "the implied clock drift is implausibly large"
    }

    private fun distanceToSpans(t: Double, spans: List<Pair<Double, Double>>): Double =
        spans.minOf { if (t in it.first..it.second) 0.0 else minOf(abs(t - it.first), abs(t - it.second)) }

    /** The whole number of wraps (-1, 0 or 1) times the wrap length that puts [t] nearest the flash [spans]. */
    internal fun nearestWrapShift(t: Double, spans: List<Pair<Double, Double>>): Double {
        val best = listOf(0, 1, -1).minByOrNull { distanceToSpans(t + it * BootClockWrap.WRAP_SEC, spans) } ?: 0
        return best * BootClockWrap.WRAP_SEC
    }

    /**
     * One boot's acceleration magnitude (m/s2) against its own sensor clock in seconds. Flash samples are used inside
     * the span each capture covers and live samples fill the rest. Flash timestamps are unwrapped as [SessionHealth]
     * does, starting from the wrap count that lands nearest the boot's live (or ping) clock, because the 32-bit
     * microsecond counter wraps every 71.6 minutes. Null when the boot has no samples.
     */
    fun series(data: SessionData, side: String): ClockAlign.Series? {
        val live = data.live.filter { it.side == side }
        // Live frames carry the 32-bit microsecond clock in ms, which wraps every 71.6 minutes. Unwrap it the way
        // SessionAnalysis does, or the post-wrap samples land far from the rest of the series and break the windows.
        var previousMs: Long? = null
        var wrapSec = 0.0
        val liveSec = live.map { l ->
            val ms = l.sample.timestampMs
            val p = previousMs
            if (p != null && BootClockWrap.isRollover(p, ms)) wrapSec += BootClockWrap.WRAP_SEC
            previousMs = ms
            ms / 1000.0 + wrapSec
        }
        val referenceUs = liveSec.minOrNull()?.times(1e6)
            ?: data.clockPairs.filter { it.side == side }.minOfOrNull { it.sensorUs.toDouble() }
        val times = ArrayList<Double>()
        val values = ArrayList<Double>()
        val spans = ArrayList<Pair<Double, Double>>()
        fun add(t: Double, s: SensorSample) {
            times.add(t); values.add(sqrt((s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ).toDouble()))
        }
        for (capture in data.captures.filter { it.side == side }) {
            val records = data.flash.filter { it.capture.id == capture.id }.sortedBy { it.index }
            if (records.isEmpty()) continue
            val wrapUs = 0x1_0000_0000L.toDouble()
            var wrap = if (referenceUs == null) 0.0
                else Math.round((referenceUs - records.first().record.timestampUs) / wrapUs).coerceAtLeast(0L) * wrapUs
            var prev = records.first().record.timestampUs
            val start = times.size
            for (r in records) {
                val us = r.record.timestampUs
                if (us < prev) wrap += wrapUs
                prev = us
                add((us + wrap) / 1e6, r.record.sample)
            }
            spans.add(times[start] to times[times.size - 1])
        }
        // The live stream only knows its own wraps from the ones it saw. If it started after a wrap that flash saw (a
        // blackout at the start), it sits one wrap early against flash, so shift it to the epoch nearest the flash spans.
        val liveShift = if (spans.isNotEmpty() && liveSec.isNotEmpty()) nearestWrapShift(liveSec.first(), spans) else 0.0
        for ((i, l) in live.withIndex()) {
            val t = liveSec[i] + liveShift
            if (spans.none { t >= it.first - 0.005 && t <= it.second + 0.005 }) add(t, l.sample)
        }
        if (times.isEmpty()) return null
        val order = times.indices.sortedBy { times[it] }
        return ClockAlign.Series(DoubleArray(order.size) { times[order[it]] }, DoubleArray(order.size) { values[order[it]] })
    }

    /**
     * Why this boot's samples cannot be put on one clock, or null. A boot that reset or was recorded in more than one
     * segment (new capture) has a restarted sensor clock, and merging its segments by timestamp would interleave
     * two epochs, so the stomp sync refuses instead of guessing.
     */
    internal fun clockProblem(data: SessionData, side: String): String? {
        if (data.captures.count { it.side == side } > 1) return "the ${sideName(side)} boot was recorded in more than one segment, so its clock may have restarted"
        var prev: Long? = null
        for (l in data.live) {
            if (l.side != side) continue
            val t = l.sample.timestampMs
            val p = prev
            if (p != null && t < p - 1000) {
                if (!BootClockWrap.isRollover(p, t)) return "the ${sideName(side)} boot's clock went backwards during the session (a reset)"
            }
            prev = t
        }
        return null
    }

    private fun sideName(side: String) = if (side == "L") "left" else "right"

    fun analyse(data: SessionData, syncs: Map<String, ClockSync> = ClockSyncExport.replay(data)): Report {
        val problem = clockProblem(data, "L") ?: clockProblem(data, "R")
        if (problem != null) return Report(Status.CLOCK_RESET, null, null, problem, null, null, null, null, null, null, false)
        val a = series(data, "L")
        val b = series(data, "R")
        if (a == null || b == null) {
            return missing(if (a == null && b == null) "neither boot has samples" else "only the ${if (a == null) "right" else "left"} boot has samples")
        }
        val aStart = a.timesSec.first(); val aEnd = a.timesSec.last()
        val bStart = b.timesSec.first(); val bEnd = b.timesSec.last()
        if (aEnd - aStart < MIN_SPAN_SEC || bEnd - bStart < MIN_SPAN_SEC) {
            return Report(Status.TOO_SHORT, null, null, "the recording is shorter than ${MIN_SPAN_SEC.toInt()} s, too short for a start and an end stomp",
                null, null, null, null, null, null, false)
        }
        val left = syncs["L"]?.timeline()
        val right = syncs["R"]?.timeline()
        val pings = left != null && right != null
        fun ping(tSec: Double): Aligned? = if (!pings) null else BootAlignment.map(left!!, right!!, (tSec * 1e6).roundToLong())
        fun windowB(from: Double, to: Double, ownFrom: Double, lengthSec: Double): ClockAlign.Window {
            val lo = ping(from); val hi = ping(to)
            return if (lo != null && hi != null) {
                val margin = PING_MARGIN_SEC + 2 * maxOf(lo.uncertaintyNs, hi.uncertaintyNs) / 1e9
                ClockAlign.Window(lo.value / 1e6 - margin, hi.value / 1e6 + margin)
            } else ClockAlign.Window(ownFrom - DEFAULT_MARGIN_SEC, ownFrom + lengthSec + DEFAULT_MARGIN_SEC)
        }
        val startA = ClockAlign.Window(aStart, aStart + WINDOW_SEC)
        val endA = ClockAlign.Window(aEnd - END_WINDOW_SEC, aEnd)
        val start = ClockAlign.EndWindows(startA, windowB(startA.fromSec, startA.toSec, bStart, WINDOW_SEC))
        val end = ClockAlign.EndWindows(endA, windowB(endA.fromSec, endA.toSec, bEnd - END_WINDOW_SEC, END_WINDOW_SEC))
        val result = ClockAlign.align(a, b, start, end)

        val startRef = if (result.ok) result.refTimeSec else aStart + WINDOW_SEC / 2
        val endRef = if (result.ok) result.end!!.refTimeSec else aEnd - END_WINDOW_SEC / 2
        val pingStart = ping(startRef)
        val pingEnd = ping(endRef)
        val pingOffset = pingStart?.let { it.value / 1e6 - startRef }
        val disagreement = if (result.ok && pingOffset != null) (result.offsetSec - pingOffset) * 1000.0 else null
        return Report(
            if (result.ok) Status.MEASURED else Status.REFUSED, result.reject, result,
            result.reject?.let(::rejectText),
            pingOffset, pingStart?.let { startRef }, pingStart?.let { it.uncertaintyNs / 1e9 },
            pingEnd?.let { it.value / 1e6 - endRef }, pingEnd?.let { endRef }, disagreement, pings,
        )
    }

    private fun missing(why: String) =
        Report(Status.MISSING_BOOT, null, null, why, null, null, null, null, null, null, false)

    private fun ms(sec: Double) = "%.1f".format(java.util.Locale.ROOT, sec * 1000.0)

    /** The Details-tab line. */
    fun summaryLine(r: Report): String = if (r.ok) {
        val head = "Boot clocks: stomp sync says ${ms(r.result!!.offsetSec)} ms"
        val tail = if (r.pingOffsetSec != null) ", ping sync says ${ms(r.pingOffsetSec)} ms (difference ${ms(r.disagreementMs!! / 1000.0)} ms)"
            else " (no ping pairs to compare)"
        "$head$tail, drift ${"%.0f".format(java.util.Locale.ROOT, r.result.driftPpm)} ppm. Experimental."
    } else {
        "Boot clocks: stomp sync could not be measured, ${r.reason}." +
            (r.pingOffsetSec?.let { " Ping sync says ${ms(it)} ms." } ?: "")
    }

    fun toJson(r: Report): JSONObject {
        val o = JSONObject().put("format_version", 1).put("experimental", true).put("note", NOTE)
            .put("convention", "offsets are right boot sensor clock minus left boot sensor clock, in the left clock's time")
            .put("status", r.status.name.lowercase())
            .put("search_window_s", WINDOW_SEC).put("end_search_window_s", END_WINDOW_SEC)
            .put("second_boot_windows_from_pings", r.windowsUsedPings)
        if (r.reason != null) o.put("reason", r.reason)
        if (r.reject != null) o.put("reject", r.reject.name.lowercase())
        val res = r.result
        if (res != null && res.ok) {
            fun end(e: ClockAlign.EndFit) = JSONObject().put("left_time_s", e.refTimeSec).put("offset_s", e.offsetSec)
                .put("correlation", e.ncc).put("sigma_s", e.sigmaSec)
            o.put("offset_s", res.offsetSec).put("ref_left_time_s", res.refTimeSec).put("drift_ppm", res.driftPpm)
                .put("offset_sigma_s", res.offsetSigmaSec).put("drift_sigma_ppm", res.driftSigmaPpm)
                .put("start", end(res.start!!)).put("end", end(res.end!!))
        }
        o.put("ping", if (r.pingOffsetSec == null) JSONObject().put("available", false) else JSONObject().put("available", true)
            .put("offset_s", r.pingOffsetSec).put("left_time_s", r.pingRefSec ?: JSONObject.NULL)
            .put("uncertainty_s", r.pingUncertaintySec ?: JSONObject.NULL)
            .put("end_offset_s", r.pingEndOffsetSec ?: JSONObject.NULL).put("end_left_time_s", r.pingEndRefSec ?: JSONObject.NULL))
        o.put("stomp_minus_ping_ms", r.disagreementMs ?: JSONObject.NULL)
        return o
    }

    /** True when stomp and ping agree to within [toleranceMs]; null if either is missing. */
    fun agrees(r: Report, toleranceMs: Double): Boolean? = r.disagreementMs?.let { abs(it) <= toleranceMs }
}
