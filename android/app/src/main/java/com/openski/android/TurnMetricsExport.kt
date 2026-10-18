package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One roll-curve turn candidate with its experimental metrics, for one boot. */
data class TurnMetricsRow(
    val side: String, val direction: String, val metrics: TurnMetrics,
    val yawTurnShape: Double?, val yawTurnClosureDegrees: Double?, val yawLeadMs: Double?
) {
    /** True when the shape window was gap-free with near-neutral ends, so the shape metrics are filled in. */
    val usable: Boolean get() = metrics.peakPhase != null
}

/** Boot-to-boot agreement over one pair of overlapping left and right candidates (union window). */
data class BootAgreementPair(
    val startMs: Double, val endMs: Double,
    val derivativeSimilarity: Double?, val rollCorrelation: Double?, val wedgeFraction: Double?,
    /** Largest change of the three figures when the right boot is moved by plus or minus the alignment uncertainty. */
    val timingSensitivity: Double? = null,
    /** True when [timingSensitivity] exceeds [TurnMetricsExport.TIMING_SENSITIVITY_LIMIT]; null when the uncertainty is unknown. */
    val timingLimited: Boolean? = null,
)

/**
 * Wires the experimental turn-metric extractors into the session export. Everything here is a descriptor of dry-ski
 * boot roll, unvalidated on real recordings, and never alters raw samples. Pure (no Android types in the CSV and
 * summary logic) so it is JVM-tested.
 */
object TurnMetricsExport {
    const val DETECTOR = "experimental_turn_metrics"
    const val NOTE = "EXPERIMENTAL and unvalidated: shape descriptors of dry-ski boot roll excursions (not edge angle, " +
        "not ski turn classification, not a quality score). Built on synthetic signals only so far. Shape metrics are blank " +
        "when the excursion has fewer than 10 samples, a gap over 100 ms, or does not start and end within 2 degrees of neutral " +
        "(usable=false). Direction uses the saved per-boot roll polarity, like detected-movements.csv."

    val HEADER = listOf("side", "direction", "positive_roll", "start_ms", "end_ms", "usable", "peak_estimated_boot_roll_deg",
        "duration_ms", "build_ms", "early_roll_rate_dps", "peak_phase", "roughness", "heading_change_deg", "edge_hold",
        "entry_roll_deg", "peak_from_entry_deg", "yaw_turn_shape", "yaw_turn_closure_deg", "yaw_lead_ms", "detector")

    fun rows(side: String, rolls: List<BootRoll>, movements: List<DryMovement>, headingTurns: List<HeadingTurn>,
             positiveLeft: Boolean): List<TurnMetricsRow> {
        val ordered = rolls.sortedBy { it.timeMs }
        // extract() returns one TurnMetrics per movement, in order.
        return TurnMetricsExtractor.extract(ordered, movements, headingTurns).zip(movements).map { (m, movement) ->
            val usable = m.peakPhase != null
            // Yaw descriptors use the same neutral-to-neutral window as the roll shape metrics, and are blank when
            // those are (the detector's own start and end lag the roll).
            val window = if (usable) TurnMetricsExtractor.shapeWindow(ordered, movement) else emptyList()
            val start = window.firstOrNull()?.timeMs
            val end = window.lastOrNull()?.timeMs
            // The window is already the contiguous run of samples from start to end, which is all the yaw functions read.
            TurnMetricsRow(side, if (m.positive == positiveLeft) "LEFT" else "RIGHT", m,
                if (start != null && end != null) YawTurnShape.turnShape(window, start, end) else null,
                if (start != null && end != null) YawTurnShape.turnClosure(window, start, end) else null,
                if (start != null && end != null) YawTurnShape.yawLeadMs(window, start, end) else null)
        }
    }

    fun csv(rows: List<TurnMetricsRow>): String {
        val sb = StringBuilder()
        sb.append("# ").append(NOTE).append('\n')
        sb.append("# Blank cell = not available. Metric definitions: docs/turn-metrics.md and docs/turn-metric-starters.md.\n")
        sb.append(HEADER.joinToString(",")).append('\n')
        rows.forEach { r ->
            val m = r.metrics
            sb.append(listOf(r.side, r.direction, m.positive, m.startMs, m.endMs, r.usable, m.peakDegrees, m.durationMs,
                m.buildMs, m.earlyRateDps, m.peakPhase, m.roughness, m.headingChangeDegrees, m.edgeHold, m.entryDegrees,
                m.peakFromEntryDegrees, r.yawTurnShape, r.yawTurnClosureDegrees, r.yawLeadMs, DETECTOR)
                .joinToString(",") { it?.toString() ?: "" }).append('\n')
        }
        return sb.toString()
    }

    /** Boot rolls with positive roll meaning LEFT, so both boots share one sign convention for the agreement functions. */
    private fun leftPositive(rolls: List<BootRoll>, positiveLeft: Boolean): List<BootRoll> =
        if (positiveLeft) rolls else rolls.map { it.copy(degrees = -it.degrees, rollRateRadps = -it.rollRateRadps) }

    /** A pair whose figures move by more than this within the alignment uncertainty is flagged timing_limited. */
    const val TIMING_SENSITIVITY_LIMIT = 0.05

    /**
     * Pairs each left candidate with the right candidate it overlaps most, and compares the boots over the union.
     * With [timingUncertaintyMs] each pair is recomputed with the right boot moved by plus and minus that amount, and
     * flagged when its figures depend on timing finer than the alignment can resolve.
     */
    fun agreementPairs(left: List<BootRoll>, right: List<BootRoll>, leftMoves: List<DryMovement>, rightMoves: List<DryMovement>,
                       leftPositiveLeft: Boolean, rightPositiveLeft: Boolean, timingUncertaintyMs: Double? = null): List<BootAgreementPair> {
        val l = leftPositive(left, leftPositiveLeft)
        val r = leftPositive(right, rightPositiveLeft)
        val u = timingUncertaintyMs?.takeIf { it.isFinite() && it >= 0 }
        val early = u?.let { d -> r.map { it.copy(timeMs = it.timeMs - d) } }
        val late = u?.let { d -> r.map { it.copy(timeMs = it.timeMs + d) } }
        fun figures(rr: List<BootRoll>, start: Double, end: Double) = listOf(BootAgreement.derivativeSimilarity(l, rr, start, end),
            TurnMetricsExtractor.agreement(l, rr, start, end), BootAgreement.wedgeFraction(l, rr, start, end))
        return leftMoves.mapNotNull { a ->
            val b = rightMoves.map { it to (minOf(it.endMs, a.endMs) - maxOf(it.startMs, a.startMs)) }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: return@mapNotNull null
            val start = minOf(a.startMs, b.startMs)
            val end = maxOf(a.endMs, b.endMs)
            val base = figures(r, start, end)
            var sensitivity: Double? = null
            if (early != null && late != null) {
                val moved = listOf(figures(early, start, end), figures(late, start, end))
                val changes = moved.flatMap { m -> m.indices.mapNotNull { i ->
                    val x = base[i]; val y = m[i]
                    if (x == null || y == null || !x.isFinite() || !y.isFinite()) null else abs(y - x)
                } }
                sensitivity = changes.maxOrNull()
            }
            BootAgreementPair(start, end, base[0], base[1], base[2], sensitivity,
                if (u == null) null else sensitivity?.let { it > TIMING_SENSITIVITY_LIMIT })
        }
    }

    /** JSON-ready tree (nulls stay null; [toJson] maps them to JSON null). */
    fun block(rowsBySide: Map<String, List<TurnMetricsRow>>, pairs: List<BootAgreementPair>,
              alignment: ClockAlignment.Result? = null): Map<String, Any?> {
        fun spread(s: MetricSpread?): Any? = s?.let { mapOf("mean" to it.mean, "sd" to it.sd) }
        fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.average()
        val sides = rowsBySide.mapValues { (_, rows) ->
            val s = TurnMetricsExtractor.summarise(rows.map { it.metrics })
            mapOf("candidates" to s.count, "usable" to rows.count { it.usable },
                "peak_deg" to spread(s.peakDegrees), "duration_ms" to spread(s.durationMs), "build_ms" to spread(s.buildMs),
                "early_roll_rate_dps" to spread(s.earlyRateDps), "peak_phase" to spread(s.peakPhase),
                "roughness" to spread(s.roughness), "edge_hold" to spread(s.edgeHold))
        }
        val agreement: LinkedHashMap<String, Any?> = linkedMapOf(
            "pairs" to pairs.size,
            "pairs_with_data" to pairs.count { it.derivativeSimilarity != null || it.rollCorrelation != null || it.wedgeFraction != null },
            "mean_derivative_similarity" to mean(pairs.mapNotNull { it.derivativeSimilarity }),
            "mean_roll_correlation" to mean(pairs.mapNotNull { it.rollCorrelation }),
            "mean_wedge_fraction" to mean(pairs.mapNotNull { it.wedgeFraction }),
            "per_pair" to pairs.map { mapOf("start_ms" to it.startMs, "end_ms" to it.endMs,
                "derivative_similarity" to it.derivativeSimilarity, "roll_correlation" to it.rollCorrelation,
                "wedge_fraction" to it.wedgeFraction, "timing_sensitivity" to it.timingSensitivity,
                "timing_limited" to it.timingLimited) },
            "note" to "Each left candidate is paired with the right candidate it overlaps most and the boots are compared over the union window. " +
                "Both boots are put on one sign convention (positive roll = LEFT) first. Null when either boot has gaps over 100 ms or too few samples. " +
                "Spread is mean and sample SD (needs two candidates).")
        if (alignment != null) {
            agreement["clock_alignment"] = alignment.toMap()
            agreement["timing_uncertainty_ms"] = alignment.uncertaintyMs
            agreement["pairs_timing_limited"] = if (alignment.uncertaintyMs == null) null else pairs.count { it.timingLimited == true }
            agreement["timing_note"] = "timing_sensitivity is the largest change of derivative similarity, roll correlation or wedge " +
                "fraction when the right boot is moved by plus or minus timing_uncertainty_ms; timing_limited is true above " +
                "$TIMING_SENSITIVITY_LIMIT, meaning the figure depends on timing finer than the alignment resolves. Null when the " +
                "alignment uncertainty is unknown (source none)."
        }
        return mapOf("experimental" to true, "note" to NOTE, "detector" to DETECTOR, "per_boot" to sides, "boot_agreement" to agreement)
    }

    fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        is Double -> if (value.isNaN() || value.isInfinite()) JSONObject.NULL else value
        else -> value
    }
}
