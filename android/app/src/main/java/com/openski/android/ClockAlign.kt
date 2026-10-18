package com.openski.android

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline gesture aligner: finds a sharp transient (a stomp or tap) near the start and near the end of two
 * recordings of the same event and cross-correlates it to get the clock offset and drift between the streams.
 * Experimental; see docs/clock-sync-design.md ("Gesture sync") and tools/analysis/clock_align_validate.py, which
 * this mirrors.
 *
 * Time is in seconds on each stream's own clock; values are any magnitude (acceleration or angular rate).
 * The result maps stream A time to stream B time: `tB = tA + offset(tA)` with `offset(tA) = offsetSec +
 * driftPpm * 1e-6 * (tA - refTimeSec)`. It rejects rather than guesses when a window has no clear single transient
 * or the two ends disagree.
 */
object ClockAlign {
    class Series(val timesSec: DoubleArray, val values: DoubleArray) {
        init { require(timesSec.size == values.size) { "times and values differ in length" } }
        val size get() = timesSec.size
    }

    /** A search window in one stream's own clock, in seconds. */
    data class Window(val fromSec: Double, val toSec: Double)

    /** Where to look for the gesture in A and in B. B's window only needs to be right to within the search margin. */
    data class EndWindows(val a: Window, val b: Window)

    enum class Reject { NO_TRANSIENT, AMBIGUOUS, NO_MATCH, LOW_CORRELATION, WINDOWS_TOO_CLOSE, DRIFT_IMPLAUSIBLE }

    class Config(
        val gridSec: Double = 0.002,
        val patchHalfSec: Double = 0.4,
        val searchSec: Double = 0.15,
        val minNcc: Double = 0.7,
        /** A second peak this fraction of the main one, apart from it, makes the window ambiguous. */
        val doubleRatio: Double = 0.6,
        val doubleGapSec: Double = 0.12,
        /** The peak must stand this many robust standard deviations above the window's median level. */
        val minSnr: Double = 8.0,
        /**
         * Absolute floor for the peak, in the series' own units. It stops a one-count blip in a quantised or
         * constant stretch (where the robust noise estimate collapses to zero) passing as a transient. The default
         * suits acceleration magnitude in m/s2, where a stomp is tens of m/s2; use about 1 for gyro magnitude in rad/s.
         */
        val minPeak: Double = 2.0,
        val maxDriftPpm: Double = 2000.0,
        val minSpanSec: Double = 1.0,
    )

    /** One end's measurement: A time of the transient, offset (B minus A) there, correlation and uncertainty. */
    data class EndFit(val refTimeSec: Double, val offsetSec: Double, val ncc: Double, val sigmaSec: Double)

    class Result(
        val ok: Boolean,
        val reject: Reject?,
        val start: EndFit?,
        val end: EndFit?,
        /** Offset (B minus A) at [refTimeSec]. */
        val offsetSec: Double,
        val refTimeSec: Double,
        val driftPpm: Double,
        val offsetSigmaSec: Double,
        val driftSigmaPpm: Double,
    ) {
        /** Offset B minus A at A time [tASec]. */
        fun offsetAt(tASec: Double): Double = offsetSec + driftPpm * 1e-6 * (tASec - refTimeSec)

        companion object {
            fun rejected(reason: Reject, start: EndFit? = null, end: EndFit? = null) =
                Result(false, reason, start, end, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
    }

    fun align(a: Series, b: Series, start: EndWindows, end: EndWindows, cfg: Config = Config()): Result {
        val first = measureEnd(a, b, start, cfg)
        if (first.reject != null) return Result.rejected(first.reject, start = first.fit)
        val second = measureEnd(a, b, end, cfg)
        if (second.reject != null) return Result.rejected(second.reject, start = first.fit, end = second.fit)
        val s = first.fit!!
        val e = second.fit!!
        val span = e.refTimeSec - s.refTimeSec
        if (span <= cfg.minSpanSec) return Result.rejected(Reject.WINDOWS_TOO_CLOSE, s, e)
        val drift = (e.offsetSec - s.offsetSec) / span
        if (abs(drift) * 1e6 > cfg.maxDriftPpm) return Result.rejected(Reject.DRIFT_IMPLAUSIBLE, s, e)
        return Result(
            ok = true, reject = null, start = s, end = e,
            offsetSec = s.offsetSec, refTimeSec = s.refTimeSec, driftPpm = drift * 1e6,
            offsetSigmaSec = s.sigmaSec, driftSigmaPpm = hypot(s.sigmaSec, e.sigmaSec) / span * 1e6,
        )
    }

    private class End(val fit: EndFit?, val reject: Reject?)

    private fun measureEnd(a: Series, b: Series, w: EndWindows, cfg: Config): End {
        val ta = findTransient(a, w.a, cfg)
        val tb = findTransient(b, w.b, cfg)
        if (ta.status == Status.FLAT || tb.status == Status.FLAT) return End(null, Reject.NO_TRANSIENT)
        if (ta.status == Status.DOUBLE || tb.status == Status.DOUBLE) return End(null, Reject.AMBIGUOUS)
        val r = refine(a, b, ta.timeSec, tb.timeSec, cfg) ?: return End(null, Reject.NO_MATCH)
        val dt = max(medianStep(a), medianStep(b))
        val sigma = 0.5 * dt * (1 + 2 * (1 - r.second))
        val fit = EndFit(ta.timeSec, r.first, r.second, sigma)
        // A low-correlation fit is kept for diagnostics only; the caller must not use it.
        return End(fit, if (r.second < cfg.minNcc) Reject.LOW_CORRELATION else null)
    }

    private enum class Status { OK, FLAT, DOUBLE }
    private class Peak(val timeSec: Double, val status: Status)

    private fun findTransient(s: Series, w: Window, cfg: Config): Peak {
        val idx = (0 until s.size).filter { s.timesSec[it] >= w.fromSec && s.timesSec[it] <= w.toSec }
        if (idx.size < 20) return Peak(Double.NaN, Status.FLAT)
        val t = DoubleArray(idx.size) { s.timesSec[idx[it]] }
        val m = DoubleArray(idx.size) { s.values[idx[it]] }
        val base = median(m)
        val f = DoubleArray(m.size) { abs(m[it] - base) }
        val noise = max(1.4826 * median(f), 1e-9)
        var i = 0
        for (k in f.indices) if (f[k] > f[i]) i = k
        if (f[i] < cfg.minSnr * noise || f[i] < cfg.minPeak) return Peak(Double.NaN, Status.FLAT)
        var tp = t[i]
        if (i > 0 && i < f.size - 1) {
            val den = f[i - 1] - 2 * f[i] + f[i + 1]
            if (den != 0.0) tp = t[i] + 0.5 * (f[i - 1] - f[i + 1]) / den * (t[i + 1] - t[i - 1]) / 2
        }
        var j = -1
        for (k in f.indices) if (abs(t[k] - t[i]) > cfg.doubleGapSec && (j < 0 || f[k] > f[j])) j = k
        if (j >= 0 && f[j] >= cfg.doubleRatio * f[i]) {
            var localMax = true
            for (k in max(j - 2, 0) until min(j + 3, f.size)) if (f[k] > f[j]) localMax = false
            if (localMax) return Peak(tp, Status.DOUBLE)
        }
        return Peak(tp, Status.OK)
    }

    /** Normalised cross-correlation of a patch of A around [pa] against B around [pb]; returns (offset B-A, ncc). */
    private fun refine(a: Series, b: Series, pa: Double, pb: Double, cfg: Config): Pair<Double, Double>? {
        val g = cfg.gridSec
        val n = (2 * cfg.patchHalfSec / g).toInt() + 1
        val medA = median(a.values)
        val medB = median(b.values)
        val pat = DoubleArray(n)
        for (k in 0 until n) {
            val v = interp(a, pa - cfg.patchHalfSec + k * g, medA)
            if (v.isNaN()) return null
            pat[k] = v
        }
        val ma = pat.average()
        var na = 0.0
        for (k in 0 until n) { pat[k] -= ma; na += pat[k] * pat[k] }
        na = sqrt(na)
        if (na < 1e-12) return null
        val nl = (2 * cfg.searchSec / g).toInt() + 1
        val ncc = DoubleArray(nl) { -1.0 }
        val buf = DoubleArray(n)
        for (li in 0 until nl) {
            val lag = -cfg.searchSec + li * g
            var bad = false
            for (k in 0 until n) {
                val v = interp(b, pb + lag - cfg.patchHalfSec + k * g, medB)
                if (v.isNaN()) { bad = true; break }
                buf[k] = v
            }
            if (bad) continue
            val mb = buf.average()
            var nb = 0.0
            var dot = 0.0
            for (k in 0 until n) { buf[k] -= mb; nb += buf[k] * buf[k]; dot += pat[k] * buf[k] }
            nb = sqrt(nb)
            if (nb > 1e-12) ncc[li] = dot / (na * nb)
        }
        var k = 0
        for (i in ncc.indices) if (ncc[i] > ncc[k]) k = i
        if (k == 0 || k == nl - 1) return null
        val den = ncc[k - 1] - 2 * ncc[k] + ncc[k + 1]
        val frac = if (den < 0) 0.5 * (ncc[k - 1] - ncc[k + 1]) / den else 0.0
        val lag = -cfg.searchSec + k * g + frac * g
        return Pair((pb - pa) + lag, ncc[k])
    }

    /** Linear interpolation of (value - [centre]) at [t]; NaN outside the series. */
    private fun interp(s: Series, t: Double, centre: Double): Double {
        val ts = s.timesSec
        if (ts.isEmpty() || t < ts[0] || t > ts[ts.size - 1]) return Double.NaN
        var lo = 0
        var hi = ts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= t) lo = mid else hi = mid
        }
        val span = ts[hi] - ts[lo]
        val u = if (span <= 0.0) 0.0 else (t - ts[lo]) / span
        return s.values[lo] + u * (s.values[hi] - s.values[lo]) - centre
    }

    private fun medianStep(s: Series): Double {
        if (s.size < 2) return 0.0
        return median(DoubleArray(s.size - 1) { s.timesSec[it + 1] - s.timesSec[it] })
    }

    private fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val c = x.copyOf()
        c.sort()
        return if (c.size % 2 == 1) c[c.size / 2] else 0.5 * (c[c.size / 2 - 1] + c[c.size / 2])
    }
}
