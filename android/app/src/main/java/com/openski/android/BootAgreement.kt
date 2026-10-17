package com.openski.android

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experimental agreement between the two boots over one window: how alike the roll-rate shapes are and how often
 * the boots lean opposite ways (a wedge). Descriptors of dry-ski boot roll, not technique scores. Both boots must
 * use the same roll sign convention; every function is null when either boot has fewer than [MIN_SAMPLES]
 * samples in the window or a gap over [MAX_GAP_MS].
 */
object BootAgreement {
    const val MAX_GAP_MS = 100.0
    private const val MIN_SAMPLES = 10
    private const val GRID_POINTS = 50
    private const val NEUTRAL_DEGREES = 2.0

    /** Cosine similarity of the derivatives of the two roll-rate series on a common grid; null for a zero-norm derivative. */
    fun derivativeSimilarity(first: List<BootRoll>, second: List<BootRoll>, startMs: Double, endMs: Double): Double? {
        val a = resample(first, startMs, endMs) { it.rollRateRadps } ?: return null
        val b = resample(second, startMs, endMs) { it.rollRateRadps } ?: return null
        val da = a.zipWithNext { x, y -> y - x }
        val db = b.zipWithNext { x, y -> y - x }
        val na = sqrt(da.sumOf { it * it })
        val nb = sqrt(db.sumOf { it * it })
        if (na < 1e-12 || nb < 1e-12) return null
        return da.indices.sumOf { da[it] * db[it] } / (na * nb)
    }

    /** Fraction of grid points with opposite roll signs, ignoring points where either boot is within neutral. */
    fun wedgeFraction(first: List<BootRoll>, second: List<BootRoll>, startMs: Double, endMs: Double): Double? {
        val a = resample(first, startMs, endMs) { it.degrees } ?: return null
        val b = resample(second, startMs, endMs) { it.degrees } ?: return null
        val used = a.indices.filter { abs(a[it]) >= NEUTRAL_DEGREES && abs(b[it]) >= NEUTRAL_DEGREES }
        if (used.isEmpty()) return null
        return used.count { a[it] * b[it] < 0 }.toDouble() / used.size
    }

    private fun resample(rolls: List<BootRoll>, startMs: Double, endMs: Double, value: (BootRoll) -> Double): List<Double>? {
        if (endMs <= startMs) return null
        val w = rolls.filter { it.timeMs in startMs..endMs }.sortedBy { it.timeMs }
        if (w.size < MIN_SAMPLES || w.zipWithNext().any { (p, q) -> q.timeMs - p.timeMs > MAX_GAP_MS } ||
            w.first().timeMs - startMs > MAX_GAP_MS || endMs - w.last().timeMs > MAX_GAP_MS) return null
        var j = 0
        return (0 until GRID_POINTS).map { g ->
            val t = startMs + (endMs - startMs) * g / (GRID_POINTS - 1)
            while (j < w.size - 2 && w[j + 1].timeMs <= t) j++
            val p = w[j]
            val q = w[j + 1]
            val f = if (q.timeMs > p.timeMs) ((t - p.timeMs) / (q.timeMs - p.timeMs)).coerceIn(0.0, 1.0) else 0.0
            value(p) + (value(q) - value(p)) * f
        }
    }
}
