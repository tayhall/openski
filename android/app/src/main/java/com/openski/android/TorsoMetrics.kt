package com.openski.android

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * One phone motion row on the analysis timeline (wall-clock ms, phone clock), with the phone's up direction in its
 * own frame from the game rotation vector ([up] null when the quaternion is unusable).
 */
data class PhonePoint(val timeMs: Double, val gyro: Vector3, val accel: Vector3, val up: Vector3?, val horizontal: Pair<Double, Double>?)

/** One boot turn candidate (a roll excursion from turn-metrics.csv) on that boot's own analysis timeline. */
data class TorsoCandidate(val side: String, val direction: String, val startMs: Double, val endMs: Double, val bootRollPeakDeg: Double)

/**
 * How one boot's analysis timeline relates to the phone clock near one time: boot timeline minus phone time
 * ([biasMs]) and a bound on the error of using it ([uncertaintyMs]). [measured] is true when the boot's clock pings
 * measured the bias; otherwise the bias is taken as zero and the bound is a fixed allowance.
 */
data class TorsoTiming(val biasMs: Double, val uncertaintyMs: Double, val measured: Boolean)

/** Experimental torso descriptors over one boot turn candidate. Null = not available. */
data class TorsoTurnRow(
    val side: String, val direction: String, val startMs: Double, val endMs: Double,
    val coverage: Double,
    val gyroRmsDps: Double?, val horizontalAccelSd: Double?,
    val leanPeakDeg: Double?, val bootRollPeakDeg: Double,
    val torsoHeadingChangeDeg: Double?, val bootHeadingChangeDeg: Double?,
    val yawRateCorrelation: Double?, val headingRatio: Double?,
    val timing: TorsoTiming, val timingSensitivity: Double?, val timingLimited: Boolean?,
) {
    val hasMetrics get() = gyroRmsDps != null
}

/**
 * Experimental torso metrics: the phone in the ski jacket pocket as an upper-body reference against the boots. Pure
 * (no Android types), JVM-tested. Nothing here is validated: pocket placement, the phone sensors and the phone-to-boot
 * timing are all unverified on real recordings.
 *
 * The phone's orientation in the pocket is unknown, so only quantities that do not depend on it are used, all built
 * with gravity from the game rotation vector: gyro magnitude, horizontal acceleration (world frame, total variance, so
 * the arbitrary game-rotation yaw does not matter), the angle of the up direction against its own baseline (a
 * magnitude, never signed), and the rotation rate about the vertical (gyro dot up, signed: rotation about vertical is
 * the same in any frame). Every figure is a windowed statistic over a whole boot turn candidate, so a timing error of
 * about 100 ms moves it little; each row is recomputed with the phone moved by plus and minus the timing uncertainty
 * and flagged when it moves more than [TIMING_SENSITIVITY_LIMIT].
 */
object TorsoMetrics {
    /** Phone rows further apart than this are a gap (the phone runs at about 100 Hz). */
    const val MAX_PHONE_GAP_MS = 50.0
    /** Boot roll samples further apart than this are a gap. */
    const val MAX_BOOT_GAP_MS = 100.0
    /** A candidate needs this share of its window covered by phone rows for its metrics to be filled in. */
    const val MIN_TURN_COVERAGE = 0.8
    /** Boot roll within this of neutral marks a phone row as a lean baseline sample (the dry-roll detector's neutral). */
    const val NEUTRAL_DEG = 3.0
    /** The lean baseline is the mean up direction over neutral samples in this span before the candidate starts. */
    const val BASELINE_SPAN_MS = 10_000.0
    const val MIN_BASELINE_SAMPLES = 20
    /** Lean peak: the largest angle after a moving mean over this many phone rows (cuts single-sample noise). */
    const val LEAN_SMOOTH_ROWS = 5
    /** Heading ratio, yaw-rate correlation and sign agreement need the boot to turn by at least this much (deg). */
    const val MIN_BOOT_HEADING_DEG = 5.0
    /** Sign agreement also needs the torso to turn by at least this much (deg). */
    const val MIN_TORSO_HEADING_DEG = 2.0
    /** Bins of the per-candidate yaw-rate correlation (bin means are compared, not single samples). */
    const val YAW_BINS = 20
    /** A row is timing_limited when moving the phone by the uncertainty changes a figure by more than this (relative, or absolute for the correlation). */
    const val TIMING_SENSITIVITY_LIMIT = 0.15
    private const val DEG = 180.0 / Math.PI

    // --------------------------------------------------------------------------------------------------- inputs

    /** The phone's up direction in its own frame from a device-to-world unit quaternion (third row of the rotation matrix). */
    fun upInPhone(w: Double, x: Double, y: Double, z: Double): Vector3? {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (!n.isFinite() || n < 0.9 || n > 1.1) return null
        val a = w / n; val b = x / n; val c = y / n; val d = z / n
        return Vector3(2 * (b * d - a * c), 2 * (c * d + a * b), 1 - 2 * (b * b + c * c))
    }

    /** World-frame horizontal acceleration (first two components of R a) from a device-to-world unit quaternion. */
    fun horizontalWorld(w: Double, x: Double, y: Double, z: Double, acc: Vector3): Pair<Double, Double>? {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (!n.isFinite() || n < 0.9 || n > 1.1) return null
        val a = w / n; val b = x / n; val c = y / n; val d = z / n
        val hx = (1 - 2 * (c * c + d * d)) * acc.x + 2 * (b * c - a * d) * acc.y + 2 * (b * d + a * c) * acc.z
        val hy = 2 * (b * c + a * d) * acc.x + (1 - 2 * (b * b + d * d)) * acc.y + 2 * (c * d - a * b) * acc.z
        return hx to hy
    }

    /**
     * Phone rows on the analysis timeline: `phone_ns - offset` is elapsedRealtimeNanos ([offsetMs] from the
     * phone_clock_check event), mapped to wall-clock ms through the recording's clock_ref.
     */
    fun points(rows: List<StoredPhone>, ref: ClockSyncExport.ClockReference, offsetMs: Double): List<PhonePoint> =
        rows.map { r ->
            val t = ref.wallMs + (r.phoneNs - (offsetMs * 1e6).roundToLong() - ref.elapsedNs) / 1e6
            val acc = Vector3(r.ax.toDouble(), r.ay.toDouble(), r.az.toDouble())
            val q = doubleArrayOf(r.qw.toDouble(), r.qx.toDouble(), r.qy.toDouble(), r.qz.toDouble())
            PhonePoint(t, Vector3(r.gx.toDouble(), r.gy.toDouble(), r.gz.toDouble()), acc,
                upInPhone(q[0], q[1], q[2], q[3]), horizontalWorld(q[0], q[1], q[2], q[3], acc))
        }.sortedBy { it.timeMs }

    // --------------------------------------------------------------------------------------------------- helpers

    private fun firstAtOrAfter(p: List<PhonePoint>, t: Double): Int {
        var lo = 0; var hi = p.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (p[m].timeMs < t) lo = m + 1 else hi = m }
        return lo
    }

    private fun firstRollAtOrAfter(r: List<BootRoll>, t: Double): Int {
        var lo = 0; var hi = r.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (r[m].timeMs < t) lo = m + 1 else hi = m }
        return lo
    }

    private fun window(p: List<PhonePoint>, from: Double, to: Double): List<PhonePoint> {
        val i = firstAtOrAfter(p, from)
        val j = firstAtOrAfter(p, to + 1e-9)
        return if (i >= j) emptyList() else p.subList(i, j)
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

    /** Share of [from, to] covered by phone rows no more than [MAX_PHONE_GAP_MS] apart. */
    fun coverage(p: List<PhonePoint>, from: Double, to: Double): Double {
        if (to <= from) return 0.0
        val w = window(p, from, to)
        var covered = 0.0
        for (k in 1 until w.size) { val dt = w[k].timeMs - w[k - 1].timeMs; if (dt <= MAX_PHONE_GAP_MS) covered += dt }
        // The row just outside each end still covers the edge interval.
        val i = firstAtOrAfter(p, from)
        if (i > 0 && i < p.size && p[i].timeMs - p[i - 1].timeMs <= MAX_PHONE_GAP_MS) covered += p[i].timeMs - from
        val j = firstAtOrAfter(p, to + 1e-9)
        if (j > 0 && j < p.size && p[j].timeMs - p[j - 1].timeMs <= MAX_PHONE_GAP_MS) covered += to - p[j - 1].timeMs
        return (covered / (to - from)).coerceIn(0.0, 1.0)
    }

    fun gyroRmsDps(w: List<PhonePoint>): Double? =
        if (w.isEmpty()) null else sqrt(w.sumOf { it.gyro.dot(it.gyro) } / w.size) * DEG

    /** Total SD of the world-frame horizontal acceleration vector (m/s^2): sqrt(var x + var y), independent of the yaw reference. */
    fun horizontalAccelSd(w: List<PhonePoint>): Double? {
        val h = w.mapNotNull { it.horizontal }
        if (h.size < 2) return null
        val mx = h.sumOf { it.first } / h.size
        val my = h.sumOf { it.second } / h.size
        return sqrt(h.sumOf { (it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) } / (h.size - 1))
    }

    private fun angleDeg(a: Vector3, b: Vector3): Double = acos((a.dot(b) / (a.norm() * b.norm())).coerceIn(-1.0, 1.0)) * DEG

    /** Largest angle (deg, after a [LEAN_SMOOTH_ROWS] moving mean) between the up direction in [w] and [baseline]. */
    fun leanPeakDeg(w: List<PhonePoint>, baseline: Vector3): Double? {
        val angles = w.mapNotNull { it.up?.let { u -> angleDeg(u, baseline) } }
        if (angles.isEmpty()) return null
        val k = min(LEAN_SMOOTH_ROWS, angles.size)
        var best = 0.0
        var sum = angles.take(k).sum()
        best = max(best, sum / k)
        for (i in k until angles.size) { sum += angles[i] - angles[i - k]; best = max(best, sum / k) }
        return best
    }

    /** Rotation about the vertical (deg): gyro dot up, integrated over rows no more than [MAX_PHONE_GAP_MS] apart. */
    fun torsoHeadingChangeDeg(w: List<PhonePoint>): Double? {
        var sum = 0.0; var used = 0
        for (k in 1 until w.size) {
            val a = w[k - 1]; val b = w[k]
            val dt = b.timeMs - a.timeMs
            val ua = a.up ?: continue
            val ub = b.up ?: continue
            if (dt > MAX_PHONE_GAP_MS || dt <= 0) continue
            sum += 0.5 * (a.gyro.dot(ua) + b.gyro.dot(ub)) * dt / 1000.0; used++
        }
        return if (used == 0) null else sum * DEG
    }

    /** The boot's heading change (deg) over [from, to]: its heading rate (rotation about its own vertical) integrated. */
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
    private fun neutralBaseline(p: List<PhonePoint>, r: List<BootRoll>, from: Double, to: Double, biasMs: Double): Vector3? {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (pt in window(p, from, to)) {
            val u = pt.up ?: continue
            val roll = rollAt(r, pt.timeMs + biasMs) ?: continue
            if (abs(roll) > NEUTRAL_DEG) continue
            sx += u.x; sy += u.y; sz += u.z; n++
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
    fun yawRateCorrelation(p: List<PhonePoint>, r: List<BootRoll>, from: Double, to: Double, biasMs: Double): Double? {
        if (to <= from) return null
        val torso = ArrayList<Double>(); val boot = ArrayList<Double>()
        val step = (to - from) / YAW_BINS
        for (k in 0 until YAW_BINS) {
            val a = from + k * step; val b = a + step
            val ph = window(p, a - biasMs, b - biasMs).mapNotNull { pt -> pt.up?.let { pt.gyro.dot(it) } }
            val i = firstRollAtOrAfter(r, a); val j = firstRollAtOrAfter(r, b)
            if (ph.isEmpty() || j <= i) return null
            torso.add(ph.average()); boot.add((i until j).sumOf { r[it].headingRateRadps } / (j - i))
        }
        return pearson(torso, boot)
    }

    // --------------------------------------------------------------------------------------------------- per candidate

    private class Figures(val gyro: Double?, val hsd: Double?, val lean: Double?, val heading: Double?, val corr: Double?)

    private fun figures(p: List<PhonePoint>, r: List<BootRoll>, c: TorsoCandidate, biasMs: Double, baseline: Vector3?, bootHeading: Double?): Figures {
        val w = window(p, c.startMs - biasMs, c.endMs - biasMs)
        val meaningfulYaw = bootHeading != null && abs(bootHeading) >= MIN_BOOT_HEADING_DEG
        return Figures(gyroRmsDps(w), horizontalAccelSd(w), baseline?.let { leanPeakDeg(w, it) }, torsoHeadingChangeDeg(w),
            if (meaningfulYaw) yawRateCorrelation(p, r, c.startMs, c.endMs, biasMs) else null)
    }

    /**
     * Torso figures for one candidate. [phone] sorted by time; [rolls] the candidate's boot, sorted; [runBaseline] the
     * fallback lean baseline when the span before the candidate has too few neutral rows.
     */
    fun row(phone: List<PhonePoint>, rolls: List<BootRoll>, c: TorsoCandidate, timing: TorsoTiming, runBaseline: Vector3?): TorsoTurnRow {
        val bias = timing.biasMs
        val cov = coverage(phone, c.startMs - bias, c.endMs - bias)
        val bootHeading = bootHeadingChangeDeg(rolls, c.startMs, c.endMs)
        if (cov < MIN_TURN_COVERAGE)
            return TorsoTurnRow(c.side, c.direction, c.startMs, c.endMs, cov, null, null, null, c.bootRollPeakDeg, null, bootHeading,
                null, null, timing, null, null)
        val baseline = neutralBaseline(phone, rolls, c.startMs - bias - BASELINE_SPAN_MS, c.startMs - bias, bias) ?: runBaseline
        val base = figures(phone, rolls, c, bias, baseline, bootHeading)
        val u = timing.uncertaintyMs.takeIf { it.isFinite() && it > 0 }
        var sensitivity: Double? = null
        if (u != null) {
            val moved = listOf(figures(phone, rolls, c, bias - u, baseline, bootHeading), figures(phone, rolls, c, bias + u, baseline, bootHeading))
            fun rel(x: Double?, y: Double?, floor: Double) = if (x == null || y == null) null else abs(y - x) / max(abs(x), floor)
            sensitivity = moved.flatMap { m -> listOfNotNull(rel(base.gyro, m.gyro, 1.0), rel(base.hsd, m.hsd, 0.05),
                rel(base.lean, m.lean, 1.0), rel(base.heading, m.heading, MIN_TORSO_HEADING_DEG),
                if (base.corr == null || m.corr == null) null else abs(m.corr - base.corr)) }.maxOrNull()
        }
        val ratio = if (bootHeading != null && abs(bootHeading) >= MIN_BOOT_HEADING_DEG && base.heading != null) base.heading / bootHeading else null
        return TorsoTurnRow(c.side, c.direction, c.startMs, c.endMs, cov, base.gyro, base.hsd, base.lean, c.bootRollPeakDeg,
            base.heading, bootHeading, base.corr, ratio, timing, sensitivity, sensitivity?.let { it > TIMING_SENSITIVITY_LIMIT })
    }

    /** Run-wide neutral baseline for one boot (all phone rows where that boot is within neutral). */
    fun runBaseline(phone: List<PhonePoint>, rolls: List<BootRoll>, biasMs: Double): Vector3? =
        if (phone.isEmpty()) null else neutralBaseline(phone, rolls, phone.first().timeMs, phone.last().timeMs, biasMs)
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
        "acceleration in the gravity frame, lean as an unsigned angle from the phone's own neutral baseline, rotation about the " +
        "vertical). Not a quality score, not technique classification. Synthetic signals only so far."
    const val TIMING_NOTE = "Phone and boot clocks are joined through the phone_clock_check offset and the clock_ref event; each " +
        "boot's analysis timeline is phone-receipt referenced with an unmeasured BLE delay bias. When the boot's clock pings exist " +
        "the bias is measured and removed and the uncertainty is the ping uncertainty; otherwise it is taken as zero with an " +
        "allowance of tens of ms. Every figure is a windowed statistic over a whole candidate; timing_limited is true when moving " +
        "the phone by plus or minus timing_uncertainty_ms changes a figure by more than ${TorsoMetrics.TIMING_SENSITIVITY_LIMIT} " +
        "(relative, or absolute for the yaw-rate correlation)."
    const val PLACEMENT_NOTE = "The phone is assumed to ride with the upper body in a jacket pocket; how firmly it is held, and " +
        "whether a pocket follows the torso at all, is unverified. Pocket movement shows up as torso motion."

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

    val HEADER = listOf("side", "direction", "start_ms", "end_ms", "phone_coverage_fraction", "torso_gyro_rms_dps",
        "torso_horizontal_accel_sd", "torso_lean_peak_deg", "boot_roll_peak_deg", "torso_heading_change_deg",
        "boot_heading_change_deg", "torso_to_boot_heading_ratio", "torso_boot_yaw_rate_correlation", "timing_source",
        "timing_bias_removed_ms", "timing_uncertainty_ms", "timing_sensitivity", "timing_limited", "detector")

    /** Timing of one boot's analysis timeline against the phone, as a function of timeline time. */
    class SideTiming(private val anchor: ClockAlignment.SideAnchor?, private val sync: ClockSync?, private val ref: ClockSyncExport.ClockReference?,
                     private val userOffsetMs: Double) {
        private val pingTimeline = sync?.takeIf { it.epochCount == 1 }?.timeline()
        private val unmeasured = TorsoTiming(0.0, UNMEASURED_BIAS_BOUND_MS + abs(userOffsetMs) + PHONE_CLOCK_SLACK_MS, false)

        fun at(timeMs: Double): TorsoTiming {
            val a = anchor ?: return unmeasured
            val tl = pingTimeline ?: return unmeasured
            val r = ref ?: return unmeasured
            if (a.spreadMs > ClockAlignment.ANCHOR_TOLERANCE_MS) return unmeasured
            val sensorSec = ClockAlignment.toPingFrame((timeMs - a.anchorMs) / 1000.0, sync)
            val sensorUs = (sensorSec * 1e6).roundToLong()
            val phoneWallMs = r.wallMs + (tl.toPhoneNs(sensorUs) - r.elapsedNs) / 1e6
            val bias = timeMs - phoneWallMs
            val unc = tl.uncertaintyNs(sensorUs) / 1e6 + PHONE_CLOCK_SLACK_MS
            if (!bias.isFinite() || !unc.isFinite() || abs(bias) > MAX_PLAUSIBLE_BIAS_MS) return unmeasured
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
     * orientation output; [timing] gives each side's [SideTiming]. [offsetMs] is the phone_clock_check offset (null when
     * missing) and [ref] the clock_ref event; the session spans [sessionStartMs, sessionEndMs] on the wall clock.
     */
    fun analyse(phoneRows: List<StoredPhone>, offsetMs: Double?, ref: ClockSyncExport.ClockReference?,
                candidates: List<TorsoCandidate>, rollsBySide: Map<String, List<BootRoll>>, timing: Map<String, SideTiming>,
                sessionStartMs: Double, sessionEndMs: Double, alignmentSource: String? = null): Result {
        fun refuse(reason: String, extra: Map<String, Any?> = emptyMap()) =
            Result(true, reason, emptyList(), base(false, reason, phoneRows.size, offsetMs, alignmentSource) + extra)
        if (phoneRows.isEmpty()) return refuse("refused: no phone motion rows in this session (phone motion off, unavailable or not recorded)")
        if (offsetMs == null || !offsetMs.isFinite())
            return refuse("refused: no phone_clock_check event, so the phone's sensor clock base is unknown and its rows cannot be placed against the boots")
        if (abs(offsetMs) > PHONE_OFFSET_LIMIT_MS)
            return refuse("refused: the phone clock check failed (offset ${f1(offsetMs)} ms, beyond ${f1(PHONE_OFFSET_LIMIT_MS)} ms), so the phone's sensor clock may not share the elapsed-realtime base")
        if (ref == null) return refuse("refused: no clock_ref event, so the phone's elapsed clock cannot be tied to the boots' timeline")
        val phone = TorsoMetrics.points(phoneRows, ref, offsetMs)
        val runCoverage = TorsoMetrics.coverage(phone, sessionStartMs, sessionEndMs)
        if (runCoverage < MIN_RUN_COVERAGE)
            return refuse("refused: the phone covers ${pct(runCoverage)} of the session, under ${pct(MIN_RUN_COVERAGE)}",
                mapOf("run_coverage_fraction" to runCoverage))
        val sorted = rollsBySide.mapValues { (_, r) -> r.sortedBy { it.timeMs } }
        val mid = (sessionStartMs + sessionEndMs) / 2
        val baselines = sorted.mapValues { (side, r) -> TorsoMetrics.runBaseline(phone, r, timing[side]?.at(mid)?.biasMs ?: 0.0) }
        val all = candidates.map { c ->
            val t = timing[c.side]?.at((c.startMs + c.endMs) / 2) ?: TorsoTiming(0.0, UNMEASURED_BIAS_BOUND_MS + PHONE_CLOCK_SLACK_MS, false)
            TorsoMetrics.row(phone, sorted[c.side].orEmpty(), c, t, baselines[c.side])
        }
        val covered = all.count { it.hasMetrics }
        if (candidates.isNotEmpty() && covered < MIN_RUN_COVERAGE * candidates.size)
            return refuse("refused: only $covered of ${candidates.size} boot turn candidates have phone coverage of at least " +
                "${pct(TorsoMetrics.MIN_TURN_COVERAGE)}", mapOf("run_coverage_fraction" to runCoverage, "candidates" to candidates.size))
        val rows = all.filter { it.coverage > 0 }
        val inSession = phone.filter { it.timeMs >= sessionStartMs && it.timeMs <= sessionEndMs }
        val reason = if (candidates.isEmpty()) "computed: no boot turn candidates, so only the run-wide quietness is reported" else "computed"
        val perSide = rows.groupBy { it.side }.toSortedMap().mapValues { (_, r) -> sideBlock(r) }
        val block = base(true, reason, phoneRows.size, offsetMs, alignmentSource) + linkedMapOf(
            "run_coverage_fraction" to runCoverage,
            "candidates" to candidates.size,
            "rows" to rows.size,
            "rows_with_metrics" to rows.count { it.hasMetrics },
            "rows_timing_limited" to rows.count { it.timingLimited == true },
            "timing" to sorted.keys.sorted().associateWith { side ->
                val ts = rows.filter { it.side == side }.map { it.timing }
                val one = timing[side]?.at(mid)
                mapOf("source" to if (one?.measured == true) "ping" else "unmeasured",
                    "bias_removed_ms" to one?.biasMs, "max_uncertainty_ms" to (ts.maxOfOrNull { it.uncertaintyMs } ?: one?.uncertaintyMs))
            },
            "quietness" to linkedMapOf(
                "run_gyro_rms_dps" to TorsoMetrics.gyroRmsDps(inSession),
                "run_horizontal_accel_sd_mps2" to TorsoMetrics.horizontalAccelSd(inSession),
                "per_turn_gyro_rms_dps" to spread(rows.mapNotNull { it.gyroRmsDps }),
                "per_turn_horizontal_accel_sd_mps2" to spread(rows.mapNotNull { it.horizontalAccelSd }),
                "note" to "Spread statistics of the phone's gyro magnitude (RMS) and world-frame horizontal acceleration (total SD); " +
                    "lower means a quieter upper body (or a quieter pocket). Not a quality score."),
            "per_boot" to perSide,
        )
        return Result(false, reason, rows, block)
    }

    private fun sideBlock(rows: List<TorsoTurnRow>): Map<String, Any?> {
        val lean = rows.filter { it.leanPeakDeg != null }
        val yaw = rows.filter { it.bootHeadingChangeDeg != null && it.torsoHeadingChangeDeg != null && abs(it.bootHeadingChangeDeg) >= TorsoMetrics.MIN_BOOT_HEADING_DEG }
        val signed = yaw.filter { abs(it.torsoHeadingChangeDeg!!) >= TorsoMetrics.MIN_TORSO_HEADING_DEG }
        return linkedMapOf(
            "rows" to rows.size,
            "rows_with_metrics" to rows.count { it.hasMetrics },
            "lean_vs_roll" to linkedMapOf(
                "turns" to lean.size,
                "torso_lean_peak_deg" to spread(lean.map { it.leanPeakDeg!! }),
                "boot_roll_peak_deg" to spread(lean.map { abs(it.bootRollPeakDeg) }),
                "lean_to_roll_ratio" to spread(lean.filter { abs(it.bootRollPeakDeg) > 1.0 }.map { it.leanPeakDeg!! / abs(it.bootRollPeakDeg) }),
                "correlation_across_turns" to TorsoMetrics.pearson(lean.map { it.leanPeakDeg!! }, lean.map { abs(it.bootRollPeakDeg) }),
                "note" to "Magnitudes only: the torso lean is the unsigned angle of the phone's up direction from its neutral baseline, " +
                    "compared with the boot's peak roll per turn. Correlation across turns needs three."),
            "yaw" to linkedMapOf(
                "turns" to yaw.size,
                "heading_change_correlation_across_turns" to TorsoMetrics.pearson(yaw.map { it.torsoHeadingChangeDeg!! }, yaw.map { it.bootHeadingChangeDeg!! }),
                "torso_to_boot_heading_ratio" to spread(yaw.mapNotNull { it.headingRatio }),
                "yaw_rate_correlation" to spread(yaw.mapNotNull { it.yawRateCorrelation }),
                "sign_agreement_fraction" to if (signed.size < 3) null else
                    signed.count { (it.torsoHeadingChangeDeg!! > 0) == (it.bootHeadingChangeDeg!! > 0) }.toDouble() / signed.size,
                "sign_turns" to signed.size,
                "note" to "Rotation about the vertical is frame independent, so signs are compared. Only turns where the boot turned " +
                    "${TorsoMetrics.MIN_BOOT_HEADING_DEG.toInt()} degrees or more count; sign agreement also needs " +
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
            sb.append(listOf(r.side, r.direction, r.startMs, r.endMs, r.coverage, r.gyroRmsDps, r.horizontalAccelSd, r.leanPeakDeg,
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
