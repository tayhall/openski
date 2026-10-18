package com.openski.android

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One boot turn candidate (a roll excursion from turn-metrics.csv) on that boot's own analysis timeline. */
data class TorsoCandidate(val side: String, val direction: String, val startMs: Double, val endMs: Double, val bootRollPeakDeg: Double)

/**
 * How one boot's analysis timeline relates to the phone clock near one time: boot timeline minus phone time
 * ([biasMs]) and a bound on the error of using it ([uncertaintyMs]). [measured] is true when the boot's clock pings
 * measured the bias; otherwise the bias is taken as zero and the bound is a fixed allowance.
 */
data class TorsoTiming(val biasMs: Double, val uncertaintyMs: Double, val measured: Boolean)

/** Gives a boot's [TorsoTiming] at a time on that boot's analysis timeline. */
fun interface TorsoTimingSource { fun at(timeMs: Double): TorsoTiming }

/**
 * Phone gyro bias (rad/s, phone frame) estimated from still periods, removed before every gyro figure. [stillRows] is
 * the number of rows it came from (0: not estimated, nothing removed); [stillRmsDps] is the gyro RMS of those rows after
 * removal, the noise floor of [TorsoTurnRow.gyroRmsDps].
 */
data class PhoneGyroBias(val x: Double, val y: Double, val z: Double, val stillRows: Int, val stillRmsDps: Double?) {
    val estimated get() = stillRows > 0
}

/** Experimental torso descriptors over one boot turn candidate. Null = not available. */
data class TorsoTurnRow(
    val side: String, val direction: String, val startMs: Double, val endMs: Double,
    val coverage: Double, val bootCoverage: Double,
    val gyroRmsDps: Double?, val horizontalAccelSd: Double?,
    /** Total tilt of the phone's up direction from its neutral baseline: lateral and fore-aft together, unsigned. */
    val tiltPeakDeg: Double?, val bootRollPeakDeg: Double,
    val torsoHeadingChangeDeg: Double?, val bootHeadingChangeDeg: Double?,
    val yawRateCorrelation: Double?, val headingRatio: Double?,
    val timing: TorsoTiming, val timingSensitivity: Double?, val timingLimited: Boolean?,
) {
    val hasMetrics get() = gyroRmsDps != null
}

/**
 * The phone track in primitive columns, sorted by time (analysis timeline, wall-clock ms). [yaw] is the bias-removed
 * gyro dot up (rad/s), [w2] the bias-removed squared gyro magnitude; [ux], [uy], [uz] the up direction in the phone frame
 * and [hx], [hy] the gravity-frame horizontal acceleration (m/s^2). NaN marks a row whose quaternion is unusable.
 */
class PhoneTrack(val t: DoubleArray, val w2: DoubleArray, val yaw: DoubleArray, val ux: DoubleArray, val uy: DoubleArray,
                 val uz: DoubleArray, val hx: DoubleArray, val hy: DoubleArray, val bias: PhoneGyroBias) {
    val size get() = t.size

    fun firstAtOrAfter(time: Double): Int {
        var lo = 0; var hi = t.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (t[m] < time) lo = m + 1 else hi = m }
        return lo
    }

    /** Index range [first, last) of rows in [from, to]. */
    fun range(from: Double, to: Double): IntRange0 = IntRange0(firstAtOrAfter(from), firstAtOrAfter(to + 1e-9))

    companion object {
        /**
         * Builds the track: `phone_ns - offset` is elapsedRealtimeNanos ([offsetMs] from the phone_clock_check event),
         * mapped to wall-clock ms through the recording's clock_ref.
         */
        fun build(rows: List<StoredPhone>, ref: ClockSyncExport.ClockReference, offsetMs: Double): PhoneTrack {
            val n = rows.size
            val sorted = rows.zipWithNext().all { (a, b) -> a.phoneNs <= b.phoneNs }
            val order = if (sorted) null else rows.indices.sortedBy { rows[it].phoneNs }.toIntArray()
            fun row(i: Int) = rows[order?.get(i) ?: i]
            val offsetNs = (offsetMs * 1e6).roundToLong()
            val t = DoubleArray(n) { ref.wallMs + (row(it).phoneNs - offsetNs - ref.elapsedNs) / 1e6 }
            val bias = TorsoMetrics.estimateGyroBias(t, rows, order)
            val w2 = DoubleArray(n); val yaw = DoubleArray(n)
            val ux = DoubleArray(n); val uy = DoubleArray(n); val uz = DoubleArray(n)
            val hx = DoubleArray(n); val hy = DoubleArray(n)
            for (i in 0 until n) {
                val r = row(i)
                val gx = r.gx - bias.x; val gy = r.gy - bias.y; val gz = r.gz - bias.z
                w2[i] = gx * gx + gy * gy + gz * gz
                val q = TorsoMetrics.unitQuat(r.qw.toDouble(), r.qx.toDouble(), r.qy.toDouble(), r.qz.toDouble())
                if (q == null) {
                    yaw[i] = Double.NaN; ux[i] = Double.NaN; uy[i] = Double.NaN; uz[i] = Double.NaN; hx[i] = Double.NaN; hy[i] = Double.NaN
                    continue
                }
                val (a, b, c, d) = q
                ux[i] = 2 * (b * d - a * c); uy[i] = 2 * (c * d + a * b); uz[i] = 1 - 2 * (b * b + c * c)
                yaw[i] = gx * ux[i] + gy * uy[i] + gz * uz[i]
                val ax = r.ax.toDouble(); val ay = r.ay.toDouble(); val az = r.az.toDouble()
                hx[i] = (1 - 2 * (c * c + d * d)) * ax + 2 * (b * c - a * d) * ay + 2 * (b * d + a * c) * az
                hy[i] = 2 * (b * c + a * d) * ax + (1 - 2 * (b * b + d * d)) * ay + 2 * (c * d - a * b) * az
            }
            return PhoneTrack(t, w2, yaw, ux, uy, uz, hx, hy, bias)
        }
    }
}

/** A half-open index range [first, last). */
data class IntRange0(val first: Int, val last: Int) { val empty get() = last <= first }

/**
 * Experimental torso metrics: the phone in the ski jacket pocket as an upper-body reference against the boots. Pure
 * (no Android types), JVM-tested. Nothing here is validated: pocket placement, the phone sensors and the phone-to-boot
 * timing are all unverified on real recordings.
 *
 * The phone's orientation in the pocket is unknown, so only quantities that do not depend on it are used, all built
 * with gravity from the game rotation vector: gyro magnitude, horizontal acceleration (world frame, total variance, so
 * the arbitrary game-rotation yaw does not matter), the total tilt of the up direction against its own neutral baseline
 * (lateral and fore-aft together, unsigned; the fused gravity estimate is also disturbed by sustained linear
 * acceleration), and the rotation rate about the vertical (gyro dot up, signed: rotation about vertical is the same in
 * any frame). Every figure is a windowed statistic over a whole boot turn candidate, so a timing error of about 100 ms
 * moves it little; each row is recomputed with the phone moved by plus and minus the timing uncertainty and flagged when
 * it moves more than [TIMING_SENSITIVITY_LIMIT] or a figure disappears.
 */
object TorsoMetrics {
    /** Phone rows further apart than this are a gap (the phone runs at about 100 Hz). */
    const val MAX_PHONE_GAP_MS = 50.0
    /** Boot roll samples further apart than this are a gap. */
    const val MAX_BOOT_GAP_MS = 100.0
    /** A candidate needs this share of its window covered by phone rows for its metrics to be filled in. */
    const val MIN_TURN_COVERAGE = 0.8
    /**
     * The heading integrals (and so the heading ratio, yaw-rate correlation and sign agreement) skip gaps, so they need
     * this coverage on both the phone and the boot, or they are left blank rather than reading low.
     */
    const val MIN_YAW_COVERAGE = 0.95
    /** Boot roll within this of neutral marks a phone row as a tilt baseline sample (the dry-roll detector's neutral). */
    const val NEUTRAL_DEG = 3.0
    /** The tilt baseline is the mean up direction over neutral samples in this span before the candidate starts. */
    const val BASELINE_SPAN_MS = 10_000.0
    const val MIN_BASELINE_SAMPLES = 20
    /** Tilt peak: the largest angle after a moving mean over this many phone rows (cuts single-sample noise). */
    const val TILT_SMOOTH_ROWS = 5
    /** Heading ratio, yaw-rate correlation and sign agreement need the boot to turn by at least this much (deg). */
    const val MIN_BOOT_HEADING_DEG = 5.0
    /** Sign agreement also needs the torso to turn by at least this much (deg). */
    const val MIN_TORSO_HEADING_DEG = 2.0
    /** Bins of the per-candidate yaw-rate correlation (bin means are compared, not single samples). */
    const val YAW_BINS = 20
    /** A row is timing_limited when moving the phone by the uncertainty changes a figure by more than this (relative, or absolute for the correlation). */
    const val TIMING_SENSITIVITY_LIMIT = 0.15
    /** Still blocks for the phone gyro bias: this many contiguous rows (about 1 s). */
    const val STILL_BLOCK_ROWS = 100
    /** A still block has every gyro magnitude below this (rad/s)... */
    const val STILL_MAX_GYRO_RADPS = 0.12
    /** ...every gyro sample within this of the block mean (rad/s, a rotation-invariant spread)... */
    const val STILL_MAX_DEVIATION_RADPS = 0.06
    /** ...and every accelerometer magnitude within this share of 1 g. */
    const val STILL_ACCEL_TOLERANCE = 0.03
    private const val G = 9.80665
    private const val DEG = 180.0 / Math.PI

    // --------------------------------------------------------------------------------------------------- inputs

    /** Normalised quaternion (w, x, y, z), or null when its norm is not near 1. */
    fun unitQuat(w: Double, x: Double, y: Double, z: Double): DoubleArray? {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (!n.isFinite() || n < 0.9 || n > 1.1) return null
        return doubleArrayOf(w / n, x / n, y / n, z / n)
    }

    /** The phone's up direction in its own frame from a device-to-world unit quaternion (third row of the rotation matrix). */
    fun upInPhone(w: Double, x: Double, y: Double, z: Double): Vector3? {
        val (a, b, c, d) = unitQuat(w, x, y, z) ?: return null
        return Vector3(2 * (b * d - a * c), 2 * (c * d + a * b), 1 - 2 * (b * b + c * c))
    }

    /** World-frame horizontal acceleration (first two components of R a) from a device-to-world unit quaternion. */
    fun horizontalWorld(w: Double, x: Double, y: Double, z: Double, acc: Vector3): Pair<Double, Double>? {
        val (a, b, c, d) = unitQuat(w, x, y, z) ?: return null
        val hx = (1 - 2 * (c * c + d * d)) * acc.x + 2 * (b * c - a * d) * acc.y + 2 * (b * d + a * c) * acc.z
        val hy = 2 * (b * c + a * d) * acc.x + (1 - 2 * (b * b + d * d)) * acc.y + 2 * (c * d - a * b) * acc.z
        return hx to hy
    }

    /**
     * Gyro bias from still blocks of [STILL_BLOCK_ROWS] contiguous rows (no gap over [MAX_PHONE_GAP_MS]) in which every
     * row is slow, close to the block mean and near 1 g; the bias is the mean gyro over all still rows. Phone frame, so it
     * holds while the phone does not move in the pocket. Rows are read through [order] when given (time order).
     */
    internal fun estimateGyroBias(t: DoubleArray, rows: List<StoredPhone>, order: IntArray?): PhoneGyroBias {
        fun row(i: Int) = rows[order?.get(i) ?: i]
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        val blocks = ArrayList<IntRange0>()
        var start = 0
        while (start + STILL_BLOCK_ROWS <= t.size) {
            val end = start + STILL_BLOCK_ROWS
            var ok = true
            var mx = 0.0; var my = 0.0; var mz = 0.0
            for (i in start until end) {
                val r = row(i)
                if (i > start && t[i] - t[i - 1] > MAX_PHONE_GAP_MS) { ok = false; break }
                val g2 = r.gx.toDouble() * r.gx + r.gy.toDouble() * r.gy + r.gz.toDouble() * r.gz
                val a = sqrt(r.ax.toDouble() * r.ax + r.ay.toDouble() * r.ay + r.az.toDouble() * r.az)
                if (g2 > STILL_MAX_GYRO_RADPS * STILL_MAX_GYRO_RADPS || abs(a - G) > STILL_ACCEL_TOLERANCE * G) { ok = false; break }
                mx += r.gx; my += r.gy; mz += r.gz
            }
            if (ok) {
                mx /= STILL_BLOCK_ROWS; my /= STILL_BLOCK_ROWS; mz /= STILL_BLOCK_ROWS
                for (i in start until end) {
                    val r = row(i)
                    val dx = r.gx - mx; val dy = r.gy - my; val dz = r.gz - mz
                    if (dx * dx + dy * dy + dz * dz > STILL_MAX_DEVIATION_RADPS * STILL_MAX_DEVIATION_RADPS) { ok = false; break }
                }
            }
            if (ok) { blocks.add(IntRange0(start, end)); sx += mx * STILL_BLOCK_ROWS; sy += my * STILL_BLOCK_ROWS; sz += mz * STILL_BLOCK_ROWS; n += STILL_BLOCK_ROWS }
            start = end
        }
        if (n == 0) return PhoneGyroBias(0.0, 0.0, 0.0, 0, null)
        val bx = sx / n; val by = sy / n; val bz = sz / n
        var s2 = 0.0
        for (b in blocks) for (i in b.first until b.last) {
            val r = row(i); val dx = r.gx - bx; val dy = r.gy - by; val dz = r.gz - bz
            s2 += dx * dx + dy * dy + dz * dz
        }
        return PhoneGyroBias(bx, by, bz, n, sqrt(s2 / n) * DEG)
    }

    // --------------------------------------------------------------------------------------------------- helpers

    private fun firstRollAtOrAfter(r: List<BootRoll>, t: Double): Int {
        var lo = 0; var hi = r.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (r[m].timeMs < t) lo = m + 1 else hi = m }
        return lo
    }

    /** Boot roll interpolated at [t]; null in a gap or outside the boot's data. */
    private fun rollAt(r: List<BootRoll>, t: Double): Double? {
        val i = firstRollAtOrAfter(r, t)
        if (i == 0 || i >= r.size) return null
        val a = r[i - 1]; val b = r[i]
        if (b.timeMs - a.timeMs > MAX_BOOT_GAP_MS) return null
        val f = if (b.timeMs > a.timeMs) (t - a.timeMs) / (b.timeMs - a.timeMs) else 0.0
        return a.degrees + f * (b.degrees - a.degrees)
    }

    /** Share of [from, to] covered by samples at [times] (sorted) no more than [maxGap] apart; 0 when no sample lies inside. */
    private fun coverageOf(size: Int, time: (Int) -> Double, first: Int, last: Int, from: Double, to: Double, maxGap: Double): Double {
        if (to <= from || last <= first) return 0.0
        var covered = 0.0
        for (k in first + 1 until last) { val dt = time(k) - time(k - 1); if (dt <= maxGap) covered += dt }
        // The sample just outside each end still covers the edge interval.
        if (first > 0 && time(first) - time(first - 1) <= maxGap) covered += time(first) - from
        if (last < size && time(last) - time(last - 1) <= maxGap) covered += to - time(last - 1)
        return (covered / (to - from)).coerceIn(0.0, 1.0)
    }

    fun coverage(p: PhoneTrack, from: Double, to: Double): Double {
        val r = p.range(from, to)
        return coverageOf(p.size, { p.t[it] }, r.first, r.last, from, to, MAX_PHONE_GAP_MS)
    }

    fun bootCoverage(r: List<BootRoll>, from: Double, to: Double): Double =
        coverageOf(r.size, { r[it].timeMs }, firstRollAtOrAfter(r, from), firstRollAtOrAfter(r, to + 1e-9), from, to, MAX_BOOT_GAP_MS)

    fun gyroRmsDps(p: PhoneTrack, w: IntRange0): Double? {
        if (w.empty) return null
        var s = 0.0
        for (i in w.first until w.last) s += p.w2[i]
        return sqrt(s / (w.last - w.first)) * DEG
    }

    /** Total SD of the world-frame horizontal acceleration vector (m/s^2): sqrt(var x + var y), independent of the yaw reference. */
    fun horizontalAccelSd(p: PhoneTrack, w: IntRange0): Double? {
        var n = 0; var mx = 0.0; var my = 0.0
        for (i in w.first until w.last) if (!p.hx[i].isNaN()) { mx += p.hx[i]; my += p.hy[i]; n++ }
        if (n < 2) return null
        mx /= n; my /= n
        var s = 0.0
        for (i in w.first until w.last) if (!p.hx[i].isNaN()) { val dx = p.hx[i] - mx; val dy = p.hy[i] - my; s += dx * dx + dy * dy }
        return sqrt(s / (n - 1))
    }

    /** Largest angle (deg, after a [TILT_SMOOTH_ROWS] moving mean) between the up direction in [w] and the unit [baseline]. */
    fun tiltPeakDeg(p: PhoneTrack, w: IntRange0, baseline: Vector3): Double? {
        val angles = DoubleArray(max(0, w.last - w.first))
        var n = 0
        for (i in w.first until w.last) {
            if (p.ux[i].isNaN()) continue
            val c = (p.ux[i] * baseline.x + p.uy[i] * baseline.y + p.uz[i] * baseline.z) /
                sqrt(p.ux[i] * p.ux[i] + p.uy[i] * p.uy[i] + p.uz[i] * p.uz[i])
            angles[n++] = acos(c.coerceIn(-1.0, 1.0)) * DEG
        }
        if (n == 0) return null
        val k = min(TILT_SMOOTH_ROWS, n)
        var sum = 0.0
        for (i in 0 until k) sum += angles[i]
        var best = sum / k
        for (i in k until n) { sum += angles[i] - angles[i - k]; best = max(best, sum / k) }
        return best
    }

    /** Rotation about the vertical (deg): gyro dot up, integrated over rows no more than [MAX_PHONE_GAP_MS] apart (gaps skipped). */
    fun torsoHeadingChangeDeg(p: PhoneTrack, w: IntRange0): Double? {
        var sum = 0.0; var used = 0
        for (k in w.first + 1 until w.last) {
            val dt = p.t[k] - p.t[k - 1]
            if (p.yaw[k].isNaN() || p.yaw[k - 1].isNaN() || dt > MAX_PHONE_GAP_MS || dt <= 0) continue
            sum += 0.5 * (p.yaw[k] + p.yaw[k - 1]) * dt / 1000.0; used++
        }
        return if (used == 0) null else sum * DEG
    }

    /** The boot's heading change (deg) over [from, to]: its heading rate (rotation about its own vertical) integrated, gaps skipped. */
    fun bootHeadingChangeDeg(r: List<BootRoll>, from: Double, to: Double): Double? {
        val i = firstRollAtOrAfter(r, from)
        val j = firstRollAtOrAfter(r, to + 1e-9)
        if (j - i < 2) return null
        var sum = 0.0
        for (k in i + 1 until j) {
            val dt = r[k].timeMs - r[k - 1].timeMs
            if (dt > MAX_BOOT_GAP_MS || dt <= 0) continue
            sum += 0.5 * (r[k].headingRateRadps + r[k - 1].headingRateRadps) * dt / 1000.0
        }
        return sum * DEG
    }

    /** Mean up direction over phone rows in [from, to] where the boot (at row time + [biasMs]) is within neutral. */
    private fun neutralBaseline(p: PhoneTrack, r: List<BootRoll>, from: Double, to: Double, biasMs: Double): Vector3? {
        val w = p.range(from, to)
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (i in w.first until w.last) {
            if (p.ux[i].isNaN()) continue
            val roll = rollAt(r, p.t[i] + biasMs) ?: continue
            if (abs(roll) > NEUTRAL_DEG) continue
            sx += p.ux[i]; sy += p.uy[i]; sz += p.uz[i]; n++
        }
        if (n < MIN_BASELINE_SAMPLES) return null
        val v = Vector3(sx, sy, sz)
        return if (v.norm() < 1e-9) null else v.unit()
    }

    fun pearson(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 3) return null
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        if (saa < 1e-12 || sbb < 1e-12) return null
        return (sab / sqrt(saa * sbb)).takeIf { it.isFinite() }
    }

    /** Pearson of bin means of the torso yaw rate (phone moved by [biasMs]) and the boot heading rate over [from, to]. */
    fun yawRateCorrelation(p: PhoneTrack, r: List<BootRoll>, from: Double, to: Double, biasMs: Double): Double? {
        if (to <= from) return null
        val torso = ArrayList<Double>(); val boot = ArrayList<Double>()
        val step = (to - from) / YAW_BINS
        for (k in 0 until YAW_BINS) {
            val a = from + k * step; val b = a + step
            val w = p.range(a - biasMs, b - biasMs)
            var s = 0.0; var n = 0
            for (i in w.first until w.last) if (!p.yaw[i].isNaN()) { s += p.yaw[i]; n++ }
            val i = firstRollAtOrAfter(r, a); val j = firstRollAtOrAfter(r, b)
            if (n == 0 || j <= i) return null
            torso.add(s / n); boot.add((i until j).sumOf { r[it].headingRateRadps } / (j - i))
        }
        return pearson(torso, boot)
    }

    // --------------------------------------------------------------------------------------------------- per candidate

    private class Figures(val coverage: Double, val gyro: Double?, val hsd: Double?, val tilt: Double?, val heading: Double?, val corr: Double?) {
        fun list() = listOf(gyro, hsd, tilt, heading, corr)
    }

    private fun figures(p: PhoneTrack, r: List<BootRoll>, c: TorsoCandidate, biasMs: Double, baseline: Vector3?, bootHeading: Double?, bootCov: Double): Figures {
        val from = c.startMs - biasMs; val to = c.endMs - biasMs
        val cov = coverage(p, from, to)
        if (cov < MIN_TURN_COVERAGE) return Figures(cov, null, null, null, null, null)
        val w = p.range(from, to)
        // Heading integrals skip gaps, so they would read low on a patchy window: blank them below MIN_YAW_COVERAGE.
        val yawOk = cov >= MIN_YAW_COVERAGE && bootCov >= MIN_YAW_COVERAGE
        val meaningfulYaw = yawOk && bootHeading != null && abs(bootHeading) >= MIN_BOOT_HEADING_DEG
        return Figures(cov, gyroRmsDps(p, w), horizontalAccelSd(p, w), baseline?.let { tiltPeakDeg(p, w, it) },
            if (yawOk) torsoHeadingChangeDeg(p, w) else null,
            if (meaningfulYaw) yawRateCorrelation(p, r, c.startMs, c.endMs, biasMs) else null)
    }

    /**
     * Torso figures for one candidate. [rolls] the candidate's boot, sorted; [runBaseline] the fallback tilt baseline when
     * the span before the candidate has too few neutral rows.
     */
    fun row(phone: PhoneTrack, rolls: List<BootRoll>, c: TorsoCandidate, timing: TorsoTiming, runBaseline: Vector3?): TorsoTurnRow {
        val bias = timing.biasMs
        val bootCov = bootCoverage(rolls, c.startMs, c.endMs)
        val bootHeading = if (bootCov >= MIN_YAW_COVERAGE) bootHeadingChangeDeg(rolls, c.startMs, c.endMs) else null
        val baseline = neutralBaseline(phone, rolls, c.startMs - bias - BASELINE_SPAN_MS, c.startMs - bias, bias) ?: runBaseline
        val base = figures(phone, rolls, c, bias, baseline, bootHeading, bootCov)
        val ratio = if (bootHeading != null && abs(bootHeading) >= MIN_BOOT_HEADING_DEG && base.heading != null) base.heading / bootHeading else null
        if (base.gyro == null)
            return TorsoTurnRow(c.side, c.direction, c.startMs, c.endMs, base.coverage, bootCov, null, null, null, c.bootRollPeakDeg,
                null, bootHeading, null, null, timing, null, null)
        val u = timing.uncertaintyMs.takeIf { it.isFinite() && it > 0 }
        var sensitivity: Double? = null
        var limited: Boolean? = null
        if (u != null) {
            val moved = listOf(figures(phone, rolls, c, bias - u, baseline, bootHeading, bootCov), figures(phone, rolls, c, bias + u, baseline, bootHeading, bootCov))
            fun rel(x: Double?, y: Double?, floor: Double) = if (x == null || y == null) null else abs(y - x) / max(abs(x), floor)
            sensitivity = moved.flatMap { m -> listOfNotNull(rel(base.gyro, m.gyro, 1.0), rel(base.hsd, m.hsd, 0.05),
                rel(base.tilt, m.tilt, 1.0), rel(base.heading, m.heading, MIN_TORSO_HEADING_DEG),
                if (base.corr == null || m.corr == null) null else abs(m.corr - base.corr)) }.maxOrNull()
            // A figure that exists at the best timing but vanishes within the uncertainty is timing limited too.
            val lost = moved.any { m -> base.list().zip(m.list()).any { (x, y) -> x != null && y == null } }
            limited = lost || (sensitivity != null && sensitivity > TIMING_SENSITIVITY_LIMIT)
        }
        return TorsoTurnRow(c.side, c.direction, c.startMs, c.endMs, base.coverage, bootCov, base.gyro, base.hsd, base.tilt, c.bootRollPeakDeg,
            base.heading, bootHeading, base.corr, ratio, timing, sensitivity, limited)
    }

    /** Run-wide neutral baseline for one boot (all phone rows where that boot is within neutral). */
    fun runBaseline(phone: PhoneTrack, rolls: List<BootRoll>, biasMs: Double): Vector3? =
        if (phone.size == 0) null else neutralBaseline(phone, rolls, phone.t.first(), phone.t.last(), biasMs)
}

/**
 * Wires [TorsoMetrics] into the session export: phone placement and timing, refusal rules, torso-metrics.csv and the
 * torso_metrics block of analysis-summary.json. Pure (no Android types), JVM-tested. Raw rows are never altered.
 */
object TorsoMetricsExport {
    const val DETECTOR = "experimental_torso_metrics"
    const val NOTE = "EXPERIMENTAL and unvalidated: the phone in the ski jacket pocket as an upper-body reference against " +
        "each boot turn candidate (dry-ski boot roll excursions from turn-metrics.csv). Pocket placement is unvalidated and the " +
        "phone's orientation in the pocket is unknown, so only frame-independent figures are used (gyro magnitude, horizontal " +
        "acceleration in the gravity frame, total tilt as an unsigned angle from the phone's own neutral baseline, rotation about " +
        "the vertical). The tilt mixes sideways and fore-aft lean and is disturbed by sustained acceleration; it is not lateral " +
        "roll. Not a quality score, not technique classification. Synthetic signals only so far."
    const val TIMING_NOTE = "Phone and boot clocks are joined through the phone_clock_check offset and the clock_ref event; each " +
        "boot's analysis timeline is phone-receipt referenced with an unmeasured BLE delay bias. When the boot's clock pings exist " +
        "the bias is measured and removed and the uncertainty is the ping uncertainty; otherwise it is taken as zero with an " +
        "allowance of tens of ms. Every figure is a windowed statistic over a whole candidate; timing_limited is true when moving " +
        "the phone by plus or minus timing_uncertainty_ms changes a figure by more than ${TorsoMetrics.TIMING_SENSITIVITY_LIMIT} " +
        "(relative, or absolute for the yaw-rate correlation) or makes a figure unavailable."
    const val PLACEMENT_NOTE = "The phone is assumed to ride with the upper body in a jacket pocket; how firmly it is held, and " +
        "whether a pocket follows the torso at all, is unverified. Pocket movement shows up as torso motion."
    const val GYRO_BIAS_NOTE = "A phone gyro bias is estimated from still periods (about 1 s blocks with the gyro slow and steady " +
        "and the accelerometer near 1 g) and removed before the gyro RMS and the heading integrals; still_gyro_rms_dps is the " +
        "RMS left in those still rows, the noise floor of the gyro RMS figures. With no still period nothing is removed."

    /** Bound on the unmeasured boot-timeline bias against the phone clock (BLE transport delay, tens of ms). */
    const val UNMEASURED_BIAS_BOUND_MS = 50.0
    /** Allowance for the phone sensor-clock base estimate (least negative of the first delivery deltas). */
    const val PHONE_CLOCK_SLACK_MS = 5.0
    /** A phone_clock_check offset beyond this means the phone's sensor clock may not share the elapsed-realtime base. */
    const val PHONE_OFFSET_LIMIT_MS = 50.0
    /** A measured boot-minus-phone bias beyond this is not trusted (the timelines are already phone referenced). */
    const val MAX_PLAUSIBLE_BIAS_MS = 500.0
    /** The phone must cover at least this share of the session, and of the candidates, or the metrics are refused. */
    const val MIN_RUN_COVERAGE = 0.5

    val HEADER = listOf("side", "direction", "start_ms", "end_ms", "phone_coverage_fraction", "boot_coverage_fraction",
        "torso_gyro_rms_dps", "torso_horizontal_accel_sd", "torso_tilt_peak_deg", "boot_roll_peak_deg", "torso_heading_change_deg",
        "boot_heading_change_deg", "torso_to_boot_heading_ratio", "torso_boot_yaw_rate_correlation", "timing_source",
        "timing_bias_removed_ms", "timing_uncertainty_ms", "timing_sensitivity", "timing_limited", "detector")

    /** The allowance used when a boot's timeline bias against the phone is not measured. */
    fun unmeasured(userOffsetMs: Double) = TorsoTiming(0.0, UNMEASURED_BIAS_BOUND_MS + abs(userOffsetMs) + PHONE_CLOCK_SLACK_MS, false)

    /** Timing of one boot's analysis timeline against the phone, as a function of timeline time. */
    class SideTiming(private val anchor: ClockAlignment.SideAnchor?, private val sync: ClockSync?, private val ref: ClockSyncExport.ClockReference?,
                     userOffsetMs: Double) : TorsoTimingSource {
        private val pingTimeline = sync?.takeIf { it.epochCount == 1 }?.timeline()
        private val fallback = unmeasured(userOffsetMs)

        override fun at(timeMs: Double): TorsoTiming {
            val a = anchor ?: return fallback
            val tl = pingTimeline ?: return fallback
            val r = ref ?: return fallback
            if (a.spreadMs > ClockAlignment.ANCHOR_TOLERANCE_MS) return fallback
            val sensorSec = ClockAlignment.toPingFrame((timeMs - a.anchorMs) / 1000.0, sync)
            val sensorUs = (sensorSec * 1e6).roundToLong()
            val phoneWallMs = r.wallMs + (tl.toPhoneNs(sensorUs) - r.elapsedNs) / 1e6
            val bias = timeMs - phoneWallMs
            val unc = tl.uncertaintyNs(sensorUs) / 1e6 + PHONE_CLOCK_SLACK_MS
            if (!bias.isFinite() || !unc.isFinite() || abs(bias) > MAX_PLAUSIBLE_BIAS_MS) return fallback
            return TorsoTiming(bias, unc, true)
        }
    }

    class Result(val refused: Boolean, val reason: String, val rows: List<TorsoTurnRow>, val block: Map<String, Any?>)

    /** A refusal for an unexpected failure, so the export still carries the entry and the block. */
    fun failed(reason: String) = Result(true, reason, emptyList(), base(false, reason, 0, null, null))

    private fun spread(v: List<Double>): Map<String, Any?>? {
        if (v.isEmpty()) return null
        val mean = v.average()
        val sd = if (v.size < 2) null else sqrt(v.sumOf { (it - mean) * (it - mean) } / (v.size - 1))
        return mapOf("n" to v.size, "mean" to mean, "sd" to sd)
    }

    /**
     * Torso metrics for a session. [candidates] per side come from the turn-metrics rows; [rollsBySide] is each boot's
     * orientation output; [timing] gives each side's timing ([SideTiming] in the export); a side without one gets the
     * unmeasured allowance with its [userOffsetMs]. [offsetMs] is the phone_clock_check offset (null when missing) and
     * [ref] the clock_ref event; the session spans [sessionStartMs, sessionEndMs] on the wall clock.
     */
    fun analyse(phoneRows: List<StoredPhone>, offsetMs: Double?, ref: ClockSyncExport.ClockReference?,
                candidates: List<TorsoCandidate>, rollsBySide: Map<String, List<BootRoll>>, timing: Map<String, TorsoTimingSource>,
                sessionStartMs: Double, sessionEndMs: Double, alignmentSource: String? = null,
                userOffsetMs: Map<String, Double> = emptyMap()): Result {
        fun refuse(reason: String, extra: Map<String, Any?> = emptyMap()) =
            Result(true, reason, emptyList(), base(false, reason, phoneRows.size, offsetMs, alignmentSource) + extra)
        if (phoneRows.isEmpty()) return refuse("refused: no phone motion rows in this session (phone motion off, unavailable or not recorded)")
        if (offsetMs == null || !offsetMs.isFinite())
            return refuse("refused: no phone_clock_check event, so the phone's sensor clock base is unknown and its rows cannot be placed against the boots")
        if (abs(offsetMs) > PHONE_OFFSET_LIMIT_MS)
            return refuse("refused: the phone clock check failed (offset ${f1(offsetMs)} ms, beyond ${f1(PHONE_OFFSET_LIMIT_MS)} ms), so the phone's sensor clock may not share the elapsed-realtime base")
        if (ref == null) return refuse("refused: no clock_ref event, so the phone's elapsed clock cannot be tied to the boots' timeline")
        val phone = PhoneTrack.build(phoneRows, ref, offsetMs)
        val runCoverage = TorsoMetrics.coverage(phone, sessionStartMs, sessionEndMs)
        if (runCoverage < MIN_RUN_COVERAGE)
            return refuse("refused: the phone covers ${pct(runCoverage)} of the session, under ${pct(MIN_RUN_COVERAGE)}",
                mapOf("run_coverage_fraction" to runCoverage))
        fun timingOf(side: String): TorsoTimingSource = timing[side] ?: TorsoTimingSource { unmeasured(userOffsetMs[side] ?: 0.0) }
        val sorted = rollsBySide.mapValues { (_, r) -> if (r.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }) r else r.sortedBy { it.timeMs } }
        val mid = (sessionStartMs + sessionEndMs) / 2
        val baselines = sorted.mapValues { (side, r) -> TorsoMetrics.runBaseline(phone, r, timingOf(side).at(mid).biasMs) }
        val all = candidates.map { c ->
            TorsoMetrics.row(phone, sorted[c.side].orEmpty(), c, timingOf(c.side).at((c.startMs + c.endMs) / 2), baselines[c.side])
        }
        val covered = all.count { it.hasMetrics }
        if (candidates.isNotEmpty() && covered < MIN_RUN_COVERAGE * candidates.size)
            return refuse("refused: only $covered of ${candidates.size} boot turn candidates have phone coverage of at least " +
                "${pct(TorsoMetrics.MIN_TURN_COVERAGE)}", mapOf("run_coverage_fraction" to runCoverage, "candidates" to candidates.size))
        val rows = all.filter { it.coverage > 0 }
        val session = phone.range(sessionStartMs, sessionEndMs)
        val reason = if (candidates.isEmpty()) "computed: no boot turn candidates, so only the run-wide quietness is reported" else "computed"
        val perSide = rows.groupBy { it.side }.toSortedMap().mapValues { (_, r) -> sideBlock(r) }
        val bias = phone.bias
        val block = base(true, reason, phoneRows.size, offsetMs, alignmentSource) + linkedMapOf(
            "run_coverage_fraction" to runCoverage,
            "candidates" to candidates.size,
            "rows" to rows.size,
            "rows_with_metrics" to rows.count { it.hasMetrics },
            "rows_timing_limited" to rows.count { it.timingLimited == true },
            "timing" to sorted.keys.sorted().associateWith { side ->
                val ts = rows.filter { it.side == side }.map { it.timing }
                val one = timingOf(side).at(mid)
                mapOf("source" to if (one.measured) "ping" else "unmeasured",
                    "bias_removed_ms" to one.biasMs, "max_uncertainty_ms" to (ts.maxOfOrNull { it.uncertaintyMs } ?: one.uncertaintyMs))
            },
            "phone_gyro_bias" to linkedMapOf(
                "estimated" to bias.estimated,
                "bias_radps" to if (bias.estimated) listOf(bias.x, bias.y, bias.z) else null,
                "still_rows" to bias.stillRows,
                "still_gyro_rms_dps" to bias.stillRmsDps,
                "note" to GYRO_BIAS_NOTE),
            "quietness" to linkedMapOf(
                "run_gyro_rms_dps" to TorsoMetrics.gyroRmsDps(phone, session),
                "run_horizontal_accel_sd_mps2" to TorsoMetrics.horizontalAccelSd(phone, session),
                "still_gyro_rms_dps" to bias.stillRmsDps,
                "per_turn_gyro_rms_dps" to spread(rows.mapNotNull { it.gyroRmsDps }),
                "per_turn_horizontal_accel_sd_mps2" to spread(rows.mapNotNull { it.horizontalAccelSd }),
                "note" to "Spread statistics of the phone's gyro magnitude (RMS, bias removed; still_gyro_rms_dps is its noise floor) " +
                    "and world-frame horizontal acceleration (total SD); lower means a quieter upper body (or a quieter pocket). Not a quality score."),
            "per_boot" to perSide,
        )
        return Result(false, reason, rows, block)
    }

    private fun sideBlock(rows: List<TorsoTurnRow>): Map<String, Any?> {
        val tilt = rows.filter { it.tiltPeakDeg != null }
        val yaw = rows.filter { it.bootHeadingChangeDeg != null && it.torsoHeadingChangeDeg != null && abs(it.bootHeadingChangeDeg) >= TorsoMetrics.MIN_BOOT_HEADING_DEG }
        val signed = yaw.filter { abs(it.torsoHeadingChangeDeg!!) >= TorsoMetrics.MIN_TORSO_HEADING_DEG }
        return linkedMapOf(
            "rows" to rows.size,
            "rows_with_metrics" to rows.count { it.hasMetrics },
            "tilt_vs_roll" to linkedMapOf(
                "turns" to tilt.size,
                "torso_tilt_peak_deg" to spread(tilt.map { it.tiltPeakDeg!! }),
                "boot_roll_peak_deg" to spread(tilt.map { abs(it.bootRollPeakDeg) }),
                "tilt_to_roll_ratio" to spread(tilt.filter { abs(it.bootRollPeakDeg) > 1.0 }.map { it.tiltPeakDeg!! / abs(it.bootRollPeakDeg) }),
                "correlation_across_turns" to TorsoMetrics.pearson(tilt.map { it.tiltPeakDeg!! }, tilt.map { abs(it.bootRollPeakDeg) }),
                "note" to "Magnitudes only: the torso tilt is the unsigned total angle (sideways and fore-aft together) of the phone's up " +
                    "direction from its neutral baseline, compared with the boot's peak roll per turn. It is not lateral roll, and " +
                    "sustained acceleration disturbs the fused gravity estimate it uses. Correlation across turns needs three."),
            "yaw" to linkedMapOf(
                "turns" to yaw.size,
                "heading_change_correlation_across_turns" to TorsoMetrics.pearson(yaw.map { it.torsoHeadingChangeDeg!! }, yaw.map { it.bootHeadingChangeDeg!! }),
                "torso_to_boot_heading_ratio" to spread(yaw.mapNotNull { it.headingRatio }),
                "yaw_rate_correlation" to spread(yaw.mapNotNull { it.yawRateCorrelation }),
                "sign_agreement_fraction" to if (signed.size < 3) null else
                    signed.count { (it.torsoHeadingChangeDeg!! > 0) == (it.bootHeadingChangeDeg!! > 0) }.toDouble() / signed.size,
                "sign_turns" to signed.size,
                "note" to "Rotation about the vertical is frame independent, so signs are compared. Only turns where the boot turned " +
                    "${TorsoMetrics.MIN_BOOT_HEADING_DEG.toInt()} degrees or more and both phone and boot cover " +
                    "${pct(TorsoMetrics.MIN_YAW_COVERAGE)} of the window count; sign agreement also needs " +
                    "${TorsoMetrics.MIN_TORSO_HEADING_DEG.toInt()} degrees of torso rotation and three turns."),
        )
    }

    private fun base(computed: Boolean, reason: String, rows: Int, offsetMs: Double?, alignmentSource: String?): Map<String, Any?> = linkedMapOf(
        "experimental" to true,
        "status" to if (computed) "computed" else "refused",
        "reason" to reason,
        "detector" to DETECTOR,
        "note" to NOTE,
        "placement_note" to PLACEMENT_NOTE,
        "timing_note" to TIMING_NOTE,
        "phone_rows" to rows,
        "phone_clock_check_offset_ms" to offsetMs,
        "boot_to_boot_alignment_source" to alignmentSource,
    )

    private fun f1(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)
    private fun pct(v: Double) = "%.0f %%".format(java.util.Locale.ROOT, v * 100)

    fun csv(rows: List<TorsoTurnRow>, reason: String? = null): String {
        val sb = StringBuilder()
        sb.append("# ").append(NOTE).append('\n')
        sb.append("# ").append(TIMING_NOTE).append('\n')
        sb.append("# Blank cell = not available. Units: dps, m/s^2 (torso_horizontal_accel_sd), degrees, ms. Definitions: docs/turn-metrics.md (Torso metrics).\n")
        if (reason != null && reason.startsWith("refused")) sb.append("# ").append(reason).append('\n')
        sb.append(HEADER.joinToString(",")).append('\n')
        rows.forEach { r ->
            sb.append(listOf(r.side, r.direction, r.startMs, r.endMs, r.coverage, r.bootCoverage, r.gyroRmsDps, r.horizontalAccelSd, r.tiltPeakDeg,
                abs(r.bootRollPeakDeg), r.torsoHeadingChangeDeg, r.bootHeadingChangeDeg, r.headingRatio, r.yawRateCorrelation,
                if (r.timing.measured) "ping" else "unmeasured", r.timing.biasMs, r.timing.uncertaintyMs, r.timingSensitivity,
                r.timingLimited, DETECTOR).joinToString(",") { v ->
                when (v) { null -> ""; is Double -> if (v.isFinite()) v.toString() else ""; else -> v.toString() }
            }).append('\n')
        }
        return sb.toString()
    }

    /** Candidates from the turn-metrics rows (the detector's start, end and peak, on each boot's own timeline). */
    fun candidates(rows: List<TurnMetricsRow>): List<TorsoCandidate> =
        rows.map { TorsoCandidate(it.side, it.direction, it.metrics.startMs, it.metrics.endMs, abs(it.metrics.peakDegrees)) }
}
