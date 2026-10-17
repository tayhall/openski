package com.openski.android

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-candidate shape numbers for one boot-roll excursion. Experimental descriptors of dry-ski boot roll,
 * not edge angle and not a turn quality score. Shape metrics are null when the window has too few samples
 * or a gap over [TurnMetricsExtractor.MAX_GAP_MS].
 */
data class TurnMetrics(
    val side: String, val startMs: Double, val endMs: Double, val positive: Boolean,
    val peakDegrees: Double, val durationMs: Double,
    /** Time for the roll to go from 10% to 80% of its peak. */
    val buildMs: Double?,
    /**
     * Median absolute roll rate over the first quarter of the shape window (deg/s), after a 3-sample rolling
     * mean of the rate, so one noisy sample cannot dominate.
     */
    val earlyRateDps: Double?,
    /** Where the peak falls in the excursion, 0 (start) to 1 (end). */
    val peakPhase: Double?,
    /** RMS roll acceleration divided by peak roll (1/s^2); jerky edging scores higher. */
    val roughness: Double?,
    /** Heading change of the overlapping heading-rate lobe, if one overlaps. */
    val headingChangeDegrees: Double?,
    /**
     * Median absolute roll over the middle 10 to 80% (by time) of the shape window, divided by sqrt([peakDegrees]).
     * Higher means the edge is held rather than peaked. Not [buildMs], which is the time from 10% to 80% of the peak.
     */
    val edgeHold: Double? = null,
    /**
     * Roll at the first sample of the shape window, signed. Needs a gap-free window but not the near-neutral ends
     * the shape metrics need, so a turn that starts off neutral still reports where it started.
     */
    val entryDegrees: Double? = null,
    /** [peakDegrees] measured from [entryDegrees] in the turn's direction (entry on the turn's side is subtracted). */
    val peakFromEntryDegrees: Double? = null
)

data class MetricSpread(val mean: Double, val sd: Double)
data class TurnMetricsSummary(
    val count: Int, val peakDegrees: MetricSpread?, val durationMs: MetricSpread?, val buildMs: MetricSpread?,
    val earlyRateDps: MetricSpread?, val peakPhase: MetricSpread?, val roughness: MetricSpread?,
    val edgeHold: MetricSpread? = null
)

object TurnMetricsExtractor {
    const val MAX_GAP_MS = 100.0
    private const val MIN_SAMPLES = 10
    private const val NEUTRAL_DEGREES = 2.0

    /** [rolls] and [movements] must be for one boot. */
    fun extract(rolls: List<BootRoll>, movements: List<DryMovement>, headingTurns: List<HeadingTurn>): List<TurnMetrics> {
        val ordered = rolls.sortedBy { it.timeMs }
        return movements.map { m ->
            val window = shapeWindow(ordered, m)
            val usable = window.size >= MIN_SAMPLES && abs(window.first().degrees) <= NEUTRAL_DEGREES && abs(window.last().degrees) <= NEUTRAL_DEGREES && window.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs in 0.0..MAX_GAP_MS }
            val continuous = window.size >= MIN_SAMPLES && window.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs in 0.0..MAX_GAP_MS }
            val entry = if (continuous) window.first().degrees else null
            val duration = m.endMs - m.startMs
            val span = if (window.size >= 2) window.last().timeMs - window.first().timeMs else 0.0
            val abs = window.map { abs(it.degrees) }
            val peakIndex = abs.indices.maxByOrNull { abs[it] }
            val heading = headingTurns
                .map { it to (minOf(it.endMs, m.endMs) - maxOf(it.startMs, m.startMs)) }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first
            TurnMetrics(
                side = window.firstOrNull()?.side ?: "", startMs = m.startMs, endMs = m.endMs, positive = m.positive,
                peakDegrees = m.peakRoll, durationMs = duration,
                buildMs = if (usable && peakIndex != null) build(window, abs, peakIndex, m.peakRoll) else null,
                earlyRateDps = if (usable) earlyRate(window, window.first().timeMs, span) else null,
                peakPhase = if (usable && peakIndex != null && span > 0) (window[peakIndex].timeMs - window.first().timeMs) / span else null,
                roughness = if (usable && m.peakRoll > 0) roughness(window) / m.peakRoll else null,
                headingChangeDegrees = heading?.changeDegrees,
                edgeHold = if (usable && m.peakRoll > 0) edgeHold(window, abs, span) / sqrt(m.peakRoll) else null,
                entryDegrees = entry,
                peakFromEntryDegrees = entry?.let { m.peakRoll - (if (m.positive) it else -it) }
            )
        }
    }

    /**
     * The detector's start and end lag the roll (low-pass, hysteresis), which would skew phase and rates.
     * Shape metrics use the raw samples from the roll's own return to near zero before the peak to the one after it.
     */
    private fun shapeWindow(ordered: List<BootRoll>, m: DryMovement): List<BootRoll> {
        val inside = ordered.indices.filter { ordered[it].timeMs in m.startMs..m.endMs }
        val peak = inside.maxByOrNull { abs(ordered[it].degrees) } ?: return emptyList()
        var lo = peak
        while (lo > 0 && abs(ordered[lo].degrees) > NEUTRAL_DEGREES && ordered[lo].timeMs - ordered[lo - 1].timeMs <= MAX_GAP_MS) lo--
        var hi = peak
        while (hi < ordered.size - 1 && abs(ordered[hi].degrees) > NEUTRAL_DEGREES && ordered[hi + 1].timeMs - ordered[hi].timeMs <= MAX_GAP_MS) hi++
        return ordered.subList(lo, hi + 1)
    }

    private fun build(window: List<BootRoll>, abs: List<Double>, peakIndex: Int, peak: Double): Double? {
        val lo = abs.take(peakIndex + 1).indexOfFirst { it >= 0.1 * peak }
        val hi = abs.take(peakIndex + 1).indexOfFirst { it >= 0.8 * peak }
        return if (lo < 0 || hi < lo) null else window[hi].timeMs - window[lo].timeMs
    }

    private fun earlyRate(window: List<BootRoll>, start: Double, duration: Double): Double? {
        val rates = window.map { Math.toDegrees(it.rollRateRadps) }
        val smooth = rates.indices.map { i -> rates.subList(maxOf(0, i - 1), minOf(rates.size, i + 2)).average() }
        val early = window.indices.filter { window[it].timeMs <= start + duration / 4 }.map { abs(smooth[it]) }
        return median(early)
    }

    private fun edgeHold(window: List<BootRoll>, abs: List<Double>, span: Double): Double {
        val t0 = window.first().timeMs
        val mid = abs.filterIndexed { i, _ -> (window[i].timeMs - t0) / span in 0.1..0.8 }
        return median(mid) ?: 0.0
    }

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** RMS of the second difference of roll, in deg/s^2. */
    private fun roughness(window: List<BootRoll>): Double {
        val acc = (1 until window.size - 1).map { i ->
            val dt1 = (window[i].timeMs - window[i - 1].timeMs) / 1000
            val dt2 = (window[i + 1].timeMs - window[i].timeMs) / 1000
            val v1 = (window[i].degrees - window[i - 1].degrees) / dt1
            val v2 = (window[i + 1].degrees - window[i].degrees) / dt2
            (v2 - v1) / ((dt1 + dt2) / 2)
        }
        return sqrt(acc.map { it * it }.average())
    }

    /**
     * Pearson correlation of two boots' roll curves over [startMs, endMs], each resampled to 50 points.
     * The caller supplies both with the same sign convention. Null if either boot has gaps or too few samples.
     */
    fun agreement(first: List<BootRoll>, second: List<BootRoll>, startMs: Double, endMs: Double): Double? {
        val a = resample(first, startMs, endMs) ?: return null
        val b = resample(second, startMs, endMs) ?: return null
        val ma = a.average(); val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val va = a.sumOf { (it - ma) * (it - ma) }; val vb = b.sumOf { (it - mb) * (it - mb) }
        return if (va <= 0 || vb <= 0) null else cov / sqrt(va * vb)
    }

    private fun resample(rolls: List<BootRoll>, startMs: Double, endMs: Double): List<Double>? {
        val w = rolls.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }
        if (w.size < MIN_SAMPLES || w.zipWithNext().any { (x, y) -> y.timeMs - x.timeMs > MAX_GAP_MS }) return null
        return (0 until 50).map { k ->
            val t = startMs + (endMs - startMs) * k / 49
            val i = w.indexOfLast { it.timeMs <= t }.coerceAtLeast(0)
            val j = (i + 1).coerceAtMost(w.size - 1)
            val span = w[j].timeMs - w[i].timeMs
            if (span <= 0) w[i].degrees else w[i].degrees + (w[j].degrees - w[i].degrees) * ((t - w[i].timeMs) / span).coerceIn(0.0, 1.0)
        }
    }

    fun summarise(turns: List<TurnMetrics>): TurnMetricsSummary = TurnMetricsSummary(
        turns.size, spread(turns.map { it.peakDegrees }), spread(turns.map { it.durationMs }),
        spread(turns.mapNotNull { it.buildMs }), spread(turns.mapNotNull { it.earlyRateDps }),
        spread(turns.mapNotNull { it.peakPhase }), spread(turns.mapNotNull { it.roughness }),
        spread(turns.mapNotNull { it.edgeHold })
    )

    private fun spread(v: List<Double>): MetricSpread? {
        if (v.size < 2) return null
        val mean = v.average()
        return MetricSpread(mean, sqrt(v.sumOf { (it - mean) * (it - mean) } / (v.size - 1)))
    }
}
