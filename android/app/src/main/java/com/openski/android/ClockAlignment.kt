package com.openski.android

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Chooses the best available boot-to-boot clock alignment for the agreement stage of the analysis (left versus right
 * boot roll), and expresses it as a shift of the right boot's analysis timeline. Pure (no Android types) and
 * experimental; see docs/clock-sync-design.md ("Using the alignment in the analysis").
 *
 * Why: [SessionAnalysis.build] puts each boot on a phone-referenced timeline with its own anchor (the smallest
 * observed live transport delay per segment, plus the user offset), so the two boots carry independent, unmeasured
 * biases of up to tens of ms. Stomp sync and pings measure the sensor clocks directly.
 *
 * Notation (all ms): for each boot `timeline = sensorClock + a`, where `a` is that boot's anchor, read from the
 * timeline points themselves. The sync sources give `off(cL) = cR - cL` (right sensor clock minus left, at left
 * sensor time cL) with a drift d. An event seen at right timeline time tR is at left timeline time
 *   tL = refL + (tR + C - refL) / (1 + d),   C = aL - aR - off0 (folded by whole clock wraps to near zero),
 * where refL is the left timeline time of the sync reference and off0 the offset there. The shift added to a right
 * timeline time is tL - tR; at the reference it is exactly C. Positive: the right boot's timeline ran early.
 *
 * Choice: stomp when it is measured and agrees with the pings within [toleranceMs] (or there are no pings); pings when
 * the stomp is not measured; pings with `disagreement` when both exist and disagree, with the uncertainty widened to
 * the size of the disagreement. Nothing is applied (source none) when the per-boot anchors are not single-valued
 * (more than one live segment, a flash copy not tied to the live clock), when a boot's clock reset, or when the
 * implied shift is implausibly large.
 */
object ClockAlignment {
    /** 32-bit microsecond clock wrap, in ms. */
    const val WRAP_MS = 4_294_967.296
    /** Per-boot anchors (timeline minus sensor clock) may differ by this much before the timeline is called multi-segment. */
    const val ANCHOR_TOLERANCE_MS = 3.0
    /** Fixed margin added to the stomp-versus-ping tolerance (starting value; tune on hardware). */
    const val DISAGREEMENT_MARGIN_MS = 5.0
    /**
     * Stomp offset sigma multiplier in the tolerance and in the stomp uncertainty. Two, not three: the aligner's sigma is
     * already deliberately conservative (several times the error seen in simulation, docs/clock-sync-design.md).
     */
    const val STOMP_SIGMAS = 2.0
    /** The live timelines are phone-referenced, so a correct shift is small; above this the mapping is not trusted. */
    const val MAX_PLAUSIBLE_SHIFT_MS = 1000.0
    const val PING_BIAS_NOTE = "The ping offset assumes equal outbound and return delays; the kept lowest-round-trip pairs " +
        "can share a bias of about half a connection interval (several ms at 15 to 30 ms intervals), not measured yet. " +
        "The ping uncertainty includes half the best round trip, which bounds that bias for each boot."
    const val NOTE = "Experimental. The right boot's roll is shifted onto the left boot's timeline for the boot-to-boot " +
        "agreement figures only; samples.csv, boot-roll.csv's existing columns and the per-boot metrics are unchanged. " +
        "The shift replaces the per-boot user offsets for this stage. shift_ms is added to right-boot timeline times at " +
        "ref_time_ms (positive: the right boot's timeline ran early); drift_ppm stretches it over the session."

    enum class Source { STOMP, PING, NONE }

    /** Timeline minus sensor clock for one boot: its median and spread across the boot's timeline points. */
    data class SideAnchor(val anchorMs: Double, val spreadMs: Double, val points: Int, val firstMs: Double, val lastMs: Double)

    class Result(
        val source: Source,
        /** Shift added to a right-boot timeline time at [refTimeMs]; null when nothing is applied. */
        val shiftMs: Double?,
        /** Left timeline time (ms, the analysis timeline) of the reference. */
        val refTimeMs: Double?,
        /** Right clock against left clock, right minus left (ppm). */
        val driftPpm: Double?,
        /** Conservative bound on the alignment error over the session (ms); null when nothing is applied. */
        val uncertaintyMs: Double?,
        val reason: String,
        val stompMinusPingMs: Double?,
        val stompMinusPingEndMs: Double?,
        val toleranceMs: Double?,
        val disagreement: Boolean,
        val stompStatus: String?,
    ) {
        val applied get() = source != Source.NONE && shiftMs != null && refTimeMs != null

        /** Left timeline time of an event at right timeline time [rightMs]; identity when nothing is applied. */
        fun rightToLeft(rightMs: Double): Double {
            if (!applied) return rightMs
            val d = (driftPpm ?: 0.0) * 1e-6
            return refTimeMs!! + (rightMs + shiftMs!! - refTimeMs) / (1 + d)
        }

        fun shiftAt(rightMs: Double): Double = rightToLeft(rightMs) - rightMs

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "experimental" to true,
            "source" to source.name.lowercase(),
            "applied" to applied,
            "shift_ms" to shiftMs,
            "ref_time_ms" to refTimeMs,
            "drift_ppm" to driftPpm,
            "uncertainty_ms" to uncertaintyMs,
            "reason" to reason,
            "stomp_status" to stompStatus,
            "stomp_minus_ping_ms" to stompMinusPingMs,
            "stomp_minus_ping_end_ms" to stompMinusPingEndMs,
            "disagreement_tolerance_ms" to toleranceMs,
            "disagreement" to disagreement,
            "convention" to "shift_ms is added to right-boot analysis timeline times at ref_time_ms (positive: the right boot's timeline ran early)",
            "ping_bias_note" to PING_BIAS_NOTE,
            "note" to NOTE,
        )
    }

    private fun none(reason: String, report: GestureSync.Report?, dis: Double? = null, disEnd: Double? = null, tol: Double? = null) =
        Result(Source.NONE, null, null, null, null, reason, dis, disEnd, tol, false, report?.status?.name?.lowercase())

    private fun fold(v: Double, near: Double) = v - Math.round((v - near) / WRAP_MS) * WRAP_MS

    private fun clockMs(p: TimelinePoint): Double =
        if (p.source == "flash" && p.sensorTimeUs != null) p.sensorTimeUs / 1000.0 else p.sample.timestampMs.toDouble()

    /** The anchor of one boot's timeline, read from its points (whole clock wraps folded away); null with no points. */
    fun anchor(timeline: List<TimelinePoint>, side: String): SideAnchor? {
        val points = timeline.filter { it.side == side }
        if (points.isEmpty()) return null
        val first = points.first().let { it.timeMs - clockMs(it) }
        val values = points.map { fold(it.timeMs - clockMs(it), first) }.sorted()
        return SideAnchor(values[values.size / 2], values.last() - values.first(), points.size,
            points.minOf { it.timeMs }, points.maxOf { it.timeMs })
    }

    private class Candidate(val offsetSec: Double, val refSec: Double, val driftPpm: Double, val uncertaintyMs: Double)

    /** Ping offset (right minus left, s) at left sensor second [tSec] through two timelines, with its uncertainty (s). */
    private fun pingAt(left: ClockTimeline, right: ClockTimeline, tSec: Double): Pair<Double, Double> {
        val a = BootAlignment.map(left, right, (tSec * 1e6).roundToLong())
        return (a.value / 1e6 - tSec) to a.uncertaintyNs / 1e9
    }

    /**
     * The alignment for [timeline] (the analysis timeline of [SessionAnalysis.build]) from the stomp-sync [report] and,
     * when given, the per-boot ping [syncs] (used for the ping uncertainty at the end reference and for a ping-only
     * alignment when the report has none, for example a recording too short for the stomp windows).
     */
    fun estimate(timeline: List<TimelinePoint>, report: GestureSync.Report?, syncs: Map<String, ClockSync>? = null): Result {
        val l = anchor(timeline, "L") ?: return none("the left boot has no samples, so there is nothing to align", report)
        val r = anchor(timeline, "R") ?: return none("the right boot has no samples, so there is nothing to align", report)
        if (report?.status == GestureSync.Status.CLOCK_RESET)
            return none("not aligned: ${report.reason ?: "a boot clock restarted"}; the live-clock timeline is used as is", report)
        for ((name, a) in listOf("left" to l, "right" to r)) if (a.spreadMs > ANCHOR_TOLERANCE_MS)
            return none("not aligned: the $name boot's timeline is built from more than one live segment or a flash copy not tied " +
                "to its live clock (anchors differ by ${"%.1f".format(java.util.Locale.ROOT, a.spreadMs)} ms), so one shift cannot be applied", report)
        val anchorSlackMs = (l.spreadMs + r.spreadMs) / 2

        val leftTl = syncs?.get("L")?.takeIf { it.epochCount == 1 }?.timeline()
        val rightTl = syncs?.get("R")?.takeIf { it.epochCount == 1 }?.timeline()

        // Ping candidate: from the report when it has one, else straight from the timelines.
        var pingStartSec = report?.pingOffsetSec
        var pingRefSec = report?.pingRefSec
        var pingUncSec = report?.pingUncertaintySec
        var pingEndSec = report?.pingEndOffsetSec
        var pingEndRefSec = report?.pingEndRefSec
        if ((pingStartSec == null || pingRefSec == null) && leftTl != null && rightTl != null) {
            val span = l.lastMs - l.firstMs
            val t0 = (l.firstMs + 0.25 * span - l.anchorMs) / 1000.0
            val t1 = (l.firstMs + 0.75 * span - l.anchorMs) / 1000.0
            val (o0, u0) = pingAt(leftTl, rightTl, t0)
            val (o1, u1) = pingAt(leftTl, rightTl, t1)
            pingStartSec = o0; pingRefSec = t0; pingEndSec = o1; pingEndRefSec = t1; pingUncSec = max(u0, u1)
        }
        val pingEndUncSec = if (leftTl != null && rightTl != null && pingEndRefSec != null) pingAt(leftTl, rightTl, pingEndRefSec).second else pingUncSec
        val ping = if (pingStartSec != null && pingRefSec != null && pingUncSec != null) {
            val drift = if (pingEndSec != null && pingEndRefSec != null && abs(pingEndRefSec - pingRefSec) > 1.0)
                (pingEndSec - pingStartSec) / (pingEndRefSec - pingRefSec) * 1e6 else 0.0
            Candidate(pingStartSec, pingRefSec, drift, max(pingUncSec, pingEndUncSec ?: 0.0) * 1000.0 + anchorSlackMs)
        } else null

        val res = report?.result?.takeIf { report.ok && it.ok }
        val stomp = res?.let {
            val refTl = fold(it.refTimeSec * 1000.0 + l.anchorMs, (l.firstMs + l.lastMs) / 2)
            val farMs = max(abs(l.firstMs - refTl), abs(l.lastMs - refTl))
            val driftTerm = if (it.driftSigmaPpm.isFinite()) it.driftSigmaPpm * 1e-6 * farMs else 0.0
            Candidate(it.offsetSec, it.refTimeSec, it.driftPpm, STOMP_SIGMAS * it.offsetSigmaSec * 1000.0 + driftTerm + anchorSlackMs)
        }

        var dis: Double? = null
        var disEnd: Double? = null
        var tol: Double? = null
        if (res != null && ping != null) {
            dis = (res.offsetAt(ping.refSec) - ping.offsetSec) * 1000.0
            if (pingEndSec != null && pingEndRefSec != null) disEnd = (res.offsetAt(pingEndRefSec) - pingEndSec) * 1000.0
            tol = max(pingUncSec ?: 0.0, pingEndUncSec ?: 0.0) * 1000.0 + STOMP_SIGMAS * res.offsetSigmaSec * 1000.0 + DISAGREEMENT_MARGIN_MS
        }
        val worst = listOfNotNull(dis, disEnd).maxByOrNull { abs(it) }
        fun f1(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

        val (source, chosen, reason, disagreement) = when {
            stomp != null && ping == null -> Choice(Source.STOMP, stomp,
                "stomp sync measured; no ping pairs to cross-check it", false)
            stomp != null && ping != null && worst != null && abs(worst) <= tol!! -> Choice(Source.STOMP, stomp,
                "stomp sync measured and agrees with the pings (stomp minus ping ${f1(worst)} ms, tolerance ${f1(tol)} ms)", false)
            stomp != null && ping != null -> Choice(Source.PING,
                Candidate(ping.offsetSec, ping.refSec, ping.driftPpm, max(ping.uncertaintyMs, abs(worst ?: 0.0))),
                "DISAGREEMENT: stomp and ping differ by ${f1(worst ?: 0.0)} ms, more than the ${f1(tol ?: 0.0)} ms tolerance; " +
                    "pings are used (their error is bounded by half the round trip, a mismatched stomp is not) and the uncertainty " +
                    "is widened to the disagreement. Check the stomps and the connection intervals.", true)
            ping != null -> Choice(Source.PING, ping, "pings only: stomp sync " + when {
                report == null -> "was not run"
                report.ok -> "was not usable"
                else -> "not measured (${report.reason ?: report.status.name.lowercase()})"
            }, false)
            else -> return none("not aligned: no stomp sync measurement (${report?.reason ?: "not run"}) and no ping pairs " +
                "from both boots; the live-clock timeline is used as is, with an unmeasured bias of up to tens of ms", report, dis, disEnd, tol)
        }

        val refTl = fold(chosen.refSec * 1000.0 + l.anchorMs, (l.firstMs + l.lastMs) / 2)
        val c = fold(l.anchorMs - r.anchorMs - chosen.offsetSec * 1000.0, 0.0)
        if (abs(c) > MAX_PLAUSIBLE_SHIFT_MS)
            return none("not aligned: the ${source.name.lowercase()} offset implies a ${f1(c)} ms shift of the right boot's timeline, " +
                "more than ${MAX_PLAUSIBLE_SHIFT_MS.toInt()} ms, so the sync and the timeline do not describe the same clocks", report, dis, disEnd, tol)
        return Result(source, c, refTl, chosen.driftPpm, chosen.uncertaintyMs, reason, dis, disEnd, tol, disagreement,
            report?.status?.name?.lowercase())
    }

    private data class Choice(val source: Source, val candidate: Candidate, val reason: String, val disagreement: Boolean)

    /** The right boot's roll on the left boot's timeline. */
    fun alignRolls(rolls: List<BootRoll>, a: Result): List<BootRoll> =
        if (!a.applied) rolls else rolls.map { it.copy(timeMs = a.rightToLeft(it.timeMs)) }

    /** The right boot's detected movements on the left boot's timeline. */
    fun alignDetection(d: DryDetection, a: Result): DryDetection = if (!a.applied) d else
        DryDetection(d.movements.map { it.copy(startMs = a.rightToLeft(it.startMs), endMs = a.rightToLeft(it.endMs)) },
            d.transitions.map { a.rightToLeft(it) })
}
