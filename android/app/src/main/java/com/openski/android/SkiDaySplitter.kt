package com.openski.android

import kotlin.math.max
import kotlin.math.min

/** What the skier was doing during one stretch of a day. */
enum class SegmentKind { DESCENT, LIFT, REST }

/** Where the altitude used for splitting came from. */
enum class AltitudeSource { BAROMETER, GPS, NONE }

/** One barometric altitude reading in metres. Only differences are used, so a pressure altitude with any offset is fine. */
data class BaroSample(val timeMs: Long, val altM: Double)

/**
 * Every threshold of the day splitter and the piste matcher, in one place. All are starting values chosen from
 * typical lift and skiing speeds and GPS behaviour; none has been tuned on a real day on snow.
 */
data class SkiDayConfig(
    /** Width (s) of the moving average applied to positions before distances and speeds, to damp GPS jitter. */
    val positionSmoothS: Double = 5.0,
    /** Half-width (s) of the chord used for the ground speed that decides moving or resting. */
    val speedHalfWindowS: Double = 5.0,
    /** Width (s) of the least-squares fit giving vertical speed from GPS altitude (noisy, so wide). */
    val gpsVerticalWindowS: Double = 30.0,
    /** Width (s) of the same fit with barometric altitude (quieter, so narrower and sharper boundaries). */
    val baroVerticalWindowS: Double = 15.0,
    /** Ground speed (m/s) below which a moving skier counts as resting (queue, stop, standing at the top). */
    val restEnterMps: Double = 0.8,
    /** Ground speed (m/s) a resting skier must exceed to count as moving again (hysteresis against jitter). */
    val restExitMps: Double = 1.5,
    /** Vertical speed (m/s, downward) that starts a descent from rest or flat travel. */
    val descentEnterMps: Double = 0.4,
    /** Downward vertical speed (m/s) below which a descent turns into flat travel (resolved from its neighbours). */
    val descentStayMps: Double = 0.1,
    /** Vertical speed (m/s, upward) that starts a lift. */
    val liftEnterMps: Double = 0.4,
    /** Upward vertical speed (m/s) below which a lift turns into flat travel (resolved from its neighbours). */
    val liftStayMps: Double = 0.1,
    /** A descent shorter than this (s) is absorbed into its neighbours (a dip during a traverse, a short slide). */
    val minDescentS: Double = 30.0,
    /** A lift shorter than this (s) is absorbed into its neighbours (a pole push or short climb on foot). */
    val minLiftS: Double = 60.0,
    /** A rest shorter than this (s) is absorbed into its neighbours (a brief stop or a short queue). */
    val minRestS: Double = 60.0,
    /** A rest shorter than this (s) between two descents or two lifts joins them (a stop mid-run, a lift that halts). */
    val bridgeRestS: Double = 180.0,
    /** GPS altitude with a reported vertical accuracy worse than this (m) is not used. */
    val maxVerticalAccuracyM: Double = 30.0,
    /** Fewer usable altitude readings than this means no altitude at all. */
    val minAltitudeFixes: Int = 10,
    /** Fraction of fixes the barometer series must cover before it is used instead of GPS altitude. */
    val minBaroCoverage: Double = 0.9,
    /** A fix counts as covered by the barometer when readings lie within this many seconds on both sides. */
    val baroMaxGapS: Double = 10.0,
    /** A top speed must be held for this long (s), so one noisy fix cannot set it. */
    val topSpeedHoldS: Double = 2.0,
    /** Stopped time (s) inside a descent or lift above which the segment notes it. */
    val noteStoppedS: Double = 10.0,
    /** Matching tolerance (m) for pistes before adding fix accuracy: piste ways are centre lines of wide runs. */
    val pisteBaseToleranceM: Double = 30.0,
    /** Matching tolerance (m) for lift lines before adding fix accuracy: a lift follows its line closely. */
    val liftBaseToleranceM: Double = 15.0,
    /** Multiple of the fix's reported horizontal accuracy added to the base tolerance. */
    val accuracyFactor: Double = 1.5,
    /** Below this ground speed (m/s) the travel direction is unknown and not compared with the way's direction. */
    val directionMinSpeedMps: Double = 1.0,
    /** Cosine between travel and way direction below which the fix runs against the way (pistes downhill, lifts up). */
    val wrongWayCos: Double = -0.2,
    /** Matching cost added per fix that runs against the way. */
    val wrongWayCost: Double = 2.0,
    /** Factor on a fix's match quality when it runs against the way. */
    val wrongWayScoreFactor: Double = 0.5,
    /** Matching cost of changing way (or leaving or joining "unmatched"); favours continuity. */
    val switchCost: Double = 4.0,
    /** Matching cost of a fix left unmatched; equal to the cost of a fix about 0.87 tolerances from a way. */
    val unmatchedCost: Double = 1.5,
    /** A matched way covering less than this fraction of a segment's fixes is dropped from the list. */
    val minWayFraction: Double = 0.1,
    /** A segment (and each listed way) scoring below this is reported unmatched rather than guessed. */
    val minMatchScore: Double = 0.5,
    /** Fraction of a segment's fixes that must lie on a way of the right kind for the map to confirm it. */
    val mapConfirmFraction: Double = 0.5,
) {
    /** Every threshold by name, for the export. */
    fun asMap(): Map<String, Any> = linkedMapOf(
        "position_smooth_s" to positionSmoothS, "speed_half_window_s" to speedHalfWindowS,
        "gps_vertical_window_s" to gpsVerticalWindowS, "baro_vertical_window_s" to baroVerticalWindowS,
        "rest_enter_mps" to restEnterMps, "rest_exit_mps" to restExitMps,
        "descent_enter_mps" to descentEnterMps, "descent_stay_mps" to descentStayMps,
        "lift_enter_mps" to liftEnterMps, "lift_stay_mps" to liftStayMps,
        "min_descent_s" to minDescentS, "min_lift_s" to minLiftS, "min_rest_s" to minRestS, "bridge_rest_s" to bridgeRestS,
        "max_vertical_accuracy_m" to maxVerticalAccuracyM, "min_altitude_fixes" to minAltitudeFixes,
        "min_baro_coverage" to minBaroCoverage, "baro_max_gap_s" to baroMaxGapS, "top_speed_hold_s" to topSpeedHoldS,
        "note_stopped_s" to noteStoppedS,
        "piste_base_tolerance_m" to pisteBaseToleranceM, "lift_base_tolerance_m" to liftBaseToleranceM,
        "accuracy_factor" to accuracyFactor, "direction_min_speed_mps" to directionMinSpeedMps,
        "wrong_way_cos" to wrongWayCos, "wrong_way_cost" to wrongWayCost, "wrong_way_score_factor" to wrongWayScoreFactor,
        "switch_cost" to switchCost, "unmatched_cost" to unmatchedCost, "min_way_fraction" to minWayFraction,
        "min_match_score" to minMatchScore, "map_confirm_fraction" to mapConfirmFraction,
    )
}

/**
 * One stretch of a day. [firstIndex] and [lastIndex] index [SkiDaySplit.fixes] (the usable fixes); neighbouring
 * segments share their boundary fix, so the segments cover the day without gaps.
 */
data class SkiDaySegment(
    val kind: SegmentKind, val startMs: Long, val endMs: Long, val firstIndex: Int, val lastIndex: Int,
    /** Metres travelled while moving, on smoothed positions; time without signal counts as a straight line. */
    val distanceM: Double,
    /** Smoothed altitude at the end minus at the start: negative for a descent. Null without altitude. */
    val verticalM: Double?,
    /** Distance over moving time; null when the segment never moved. */
    val meanSpeedMps: Double?,
    /** Highest speed held for [SkiDayConfig.topSpeedHoldS]; null when no stretch was long enough. */
    val topSpeedMps: Double?,
    /** True when the map agrees (a lift line for LIFT, a piste for DESCENT), false when not, null without a map or for REST. */
    val mapConfirmed: Boolean?,
    /** Confidence notes, for example "altitude from GPS only" or "no resort map". */
    val notes: List<String>,
) {
    val durationS: Double get() = (endMs - startMs) / 1000.0
}

/** The splitter's result: the usable fixes it worked on and the ordered segments over them. */
data class SkiDaySplit(
    val fixes: List<GpsFix>, val segments: List<SkiDaySegment>, val altitudeSource: AltitudeSource,
    /** Day-level notes (altitude source, map, too few fixes). */
    val notes: List<String>, val totalFixes: Int, val config: SkiDayConfig,
)

/**
 * Splits a day of phone GPS fixes into DESCENT, LIFT and REST segments. Pure: no Android types, no I/O. Raw fixes are
 * never altered; every smoothing here is derived and reproducible from them. EXPERIMENTAL and unvalidated on real days.
 */
object SkiDaySplitter {
    const val NOTE_GPS_ALTITUDE = "altitude from GPS only"
    const val NOTE_BARO_ALTITUDE = "altitude from the barometer"
    const val NOTE_BARO_SHORT = "barometer did not cover the day; altitude from GPS only"
    const val NOTE_NO_ALTITUDE = "no usable altitude: lifts told from descents only by the resort map, otherwise moving time is labelled descent"
    const val NOTE_NO_MAP = "no resort map"

    private const val REST = 0
    private const val DESCENT = 1
    private const val LIFT = 2
    private const val UNKNOWN = 3

    private class Run(var kind: Int, val start: Int)

    fun split(raw: List<GpsFix>, baro: List<BaroSample> = emptyList(), map: ResortMap? = null,
              config: SkiDayConfig = SkiDayConfig()): SkiDaySplit {
        val fixes = RunTrack.usable(raw)
        val n = fixes.size
        if (n < 2) return SkiDaySplit(fixes, emptyList(), AltitudeSource.NONE, listOf("too few usable fixes"), raw.size, config)
        val frame = LocalFrame(fixes[0].lat, fixes[0].lon)
        val t = DoubleArray(n) { (fixes[it].timeMs - fixes[0].timeMs) / 1000.0 }
        val sx = windowMean(t, DoubleArray(n) { frame.x(fixes[it].lon) }, config.positionSmoothS / 2)
        val sy = windowMean(t, DoubleArray(n) { frame.y(fixes[it].lat) }, config.positionSmoothS / 2)

        val dayNotes = ArrayList<String>()
        val (alt, source) = altitudes(fixes, t, baro, config, dayNotes)
        val verticalWindow = if (source == AltitudeSource.BAROMETER) config.baroVerticalWindowS else config.gpsVerticalWindowS
        val vz = if (alt != null) windowSlope(t, alt, verticalWindow / 2) else DoubleArray(n) { Double.NaN }
        val smoothAlt = alt?.let { windowMean(t, it, verticalWindow / 2) }
        val speed = chordSpeed(t, sx, sy, config.speedHalfWindowS)
        if (map == null) dayNotes.add(NOTE_NO_MAP)

        val labels = label(speed, vz, config)
        var runs = runsOf(labels)
        // Flat travel takes the kind its context gives it.
        for (i in runs.indices) {
            val r = runs[i]
            if (r.kind != UNKNOWN) continue
            val prev = runs.getOrNull(i - 1)?.kind
            val next = runs.getOrNull(i + 1)?.kind
            val end = runs.getOrNull(i + 1)?.start ?: (n - 1)
            val onLift = map != null && PisteMatcher.fractionOnWays(fixes.subList(r.start, end + 1), WayKind.LIFT, map, config) >=
                config.mapConfirmFraction
            r.kind = when {
                source == AltitudeSource.NONE -> if (onLift) LIFT else DESCENT
                prev == DESCENT || next == DESCENT -> DESCENT
                onLift || prev == LIFT || next == LIFT -> LIFT
                else -> REST
            }
        }
        runs = merged(runs)
        runs = absorbShort(runs, t, config)
        runs = bridgeRests(runs, t, config)
        runs = absorbShort(runs, t, config)

        val segments = runs.mapIndexed { i, r ->
            val last = runs.getOrNull(i + 1)?.start ?: (n - 1)
            segment(fixes, t, sx, sy, speed, smoothAlt, r.kind, r.start, last, map, source, config)
        }
        return SkiDaySplit(fixes, segments, source, dayNotes, raw.size, config)
    }

    private fun kindOf(label: Int) = when (label) {
        DESCENT -> SegmentKind.DESCENT
        LIFT -> SegmentKind.LIFT
        else -> SegmentKind.REST
    }

    /** Per-fix labels with hysteresis on both ground speed and vertical speed. */
    private fun label(speed: DoubleArray, vz: DoubleArray, c: SkiDayConfig): IntArray {
        fun fresh(w: Double) = when {
            w.isNaN() -> UNKNOWN
            w <= -c.descentEnterMps -> DESCENT
            w >= c.liftEnterMps -> LIFT
            else -> UNKNOWN
        }
        val out = IntArray(speed.size)
        var state = if (speed[0] < c.restExitMps) REST else fresh(vz[0])
        for (i in speed.indices) {
            val v = speed[i]
            val w = vz[i]
            state = when {
                state == REST -> if (v > c.restExitMps) fresh(w) else REST
                v < c.restEnterMps -> REST
                state == DESCENT -> when {
                    w.isNaN() -> UNKNOWN
                    w >= c.liftEnterMps -> LIFT
                    w > -c.descentStayMps -> UNKNOWN
                    else -> DESCENT
                }
                state == LIFT -> when {
                    w.isNaN() -> UNKNOWN
                    w <= -c.descentEnterMps -> DESCENT
                    w < c.liftStayMps -> UNKNOWN
                    else -> LIFT
                }
                else -> fresh(w)
            }
            out[i] = state
        }
        return out
    }

    private fun runsOf(labels: IntArray): MutableList<Run> {
        val runs = ArrayList<Run>()
        for (i in labels.indices) if (i == 0 || labels[i] != labels[i - 1]) runs.add(Run(labels[i], i))
        return runs
    }

    private fun merged(runs: List<Run>): MutableList<Run> {
        val out = ArrayList<Run>()
        for (r in runs) if (out.isEmpty() || out.last().kind != r.kind) out.add(Run(r.kind, r.start))
        return out
    }

    private fun duration(runs: List<Run>, i: Int, t: DoubleArray): Double =
        t[runs.getOrNull(i + 1)?.start ?: (t.size - 1)] - t[runs[i].start]

    private fun minFor(kind: Int, c: SkiDayConfig) = when (kind) {
        DESCENT -> c.minDescentS
        LIFT -> c.minLiftS
        else -> c.minRestS
    }

    /** Repeatedly gives the shortest too-short run to its neighbours until every run is long enough (or one is left). */
    private fun absorbShort(start: MutableList<Run>, t: DoubleArray, c: SkiDayConfig): MutableList<Run> {
        var runs = start
        while (runs.size > 1) {
            var pick = -1
            var pickDuration = Double.MAX_VALUE
            for (i in runs.indices) {
                val d = duration(runs, i, t)
                if (d < minFor(runs[i].kind, c) && d < pickDuration) { pick = i; pickDuration = d }
            }
            if (pick < 0) break
            val prev = runs.getOrNull(pick - 1)
            val next = runs.getOrNull(pick + 1)
            runs[pick].kind = when {
                prev == null -> next!!.kind
                next == null -> prev.kind
                prev.kind == next.kind -> prev.kind
                duration(runs, pick - 1, t) >= duration(runs, pick + 1, t) -> prev.kind
                else -> next.kind
            }
            runs = merged(runs)
        }
        return runs
    }

    /** A short rest between two descents (or two lifts) is a stop inside one run (or a lift that halted). */
    private fun bridgeRests(runs: MutableList<Run>, t: DoubleArray, c: SkiDayConfig): MutableList<Run> {
        for (i in 1 until runs.size - 1) {
            if (runs[i].kind == REST && runs[i - 1].kind == runs[i + 1].kind && runs[i - 1].kind != REST &&
                duration(runs, i, t) < c.bridgeRestS) runs[i].kind = runs[i - 1].kind
        }
        return merged(runs)
    }

    private fun segment(fixes: List<GpsFix>, t: DoubleArray, sx: DoubleArray, sy: DoubleArray, speed: DoubleArray,
                        smoothAlt: DoubleArray?, label: Int, first: Int, last: Int, map: ResortMap?,
                        source: AltitudeSource, c: SkiDayConfig): SkiDaySegment {
        var distance = 0.0
        var movingS = 0.0
        var stoppedS = 0.0
        var gapS = 0.0
        for (i in first + 1..last) {
            val dt = t[i] - t[i - 1]
            val step = LocalFrame.dist(sx[i - 1], sy[i - 1], sx[i], sy[i])
            when {
                dt * 1000 > RunTrack.MAX_GAP_MS -> { gapS += dt; distance += step; movingS += dt }
                speed[i] >= c.restEnterMps -> { distance += step; movingS += dt }
                else -> stoppedS += dt
            }
        }
        val kind = kindOf(label)
        val notes = ArrayList<String>()
        notes.add(when (source) {
            AltitudeSource.BAROMETER -> NOTE_BARO_ALTITUDE
            AltitudeSource.GPS -> NOTE_GPS_ALTITUDE
            AltitudeSource.NONE -> NOTE_NO_ALTITUDE
        })
        var confirmed: Boolean? = null
        if (map == null) notes.add(NOTE_NO_MAP)
        else if (kind != SegmentKind.REST) {
            val wayKind = if (kind == SegmentKind.LIFT) WayKind.LIFT else WayKind.PISTE
            val fraction = PisteMatcher.fractionOnWays(fixes.subList(first, last + 1), wayKind, map, c)
            confirmed = fraction >= c.mapConfirmFraction
            val what = if (wayKind == WayKind.LIFT) "a mapped lift line" else "a mapped piste"
            notes.add(if (confirmed) "on $what (${(fraction * 100).toInt()}% of fixes)"
                else "not confirmed: only ${(fraction * 100).toInt()}% of fixes on $what")
        }
        if (gapS > 0) notes.add("includes ${gapS.toInt()} s without signal, counted as a straight line")
        if (kind != SegmentKind.REST && stoppedS >= c.noteStoppedS) notes.add("includes ${stoppedS.toInt()} s stopped")
        return SkiDaySegment(
            kind, fixes[first].timeMs, fixes[last].timeMs, first, last, distance,
            smoothAlt?.let { it[last] - it[first] },
            if (movingS > 0) distance / movingS else null,
            topSpeed(fixes, t, sx, sy, first, last, c), confirmed, notes)
    }

    /** The highest speed held for [SkiDayConfig.topSpeedHoldS]; reported speeds are preferred over positions. */
    private fun topSpeed(fixes: List<GpsFix>, t: DoubleArray, sx: DoubleArray, sy: DoubleArray, first: Int, last: Int,
                         c: SkiDayConfig): Double? {
        val chord = chordSpeed(t, sx, sy, 1.0, first, last)
        val v = DoubleArray(last - first + 1) { k ->
            val reported = fixes[first + k].speedMps
            if (reported != null && reported.isFinite() && reported >= 0) reported else chord[first + k]
        }
        var best: Double? = null
        for (i in first..last) {
            var low = v[i - first]
            var j = i
            var held = true
            while (t[j] - t[i] < c.topSpeedHoldS) {
                if (j + 1 > last || (t[j + 1] - t[j]) * 1000 > RunTrack.MAX_GAP_MS) { held = false; break }
                j++
                low = min(low, v[j - first])
            }
            if (held && !low.isNaN() && (best == null || low > best)) best = low
        }
        return best
    }

    /** Altitude per fix (gaps filled by time interpolation) and where it came from. */
    private fun altitudes(fixes: List<GpsFix>, t: DoubleArray, baro: List<BaroSample>, c: SkiDayConfig,
                          notes: MutableList<String>): Pair<DoubleArray?, AltitudeSource> {
        val n = fixes.size
        val b = baro.filter { it.altM.isFinite() }.sortedBy { it.timeMs }
        if (b.size >= c.minAltitudeFixes) {
            val alt = DoubleArray(n) { Double.NaN }
            var k = 0
            var covered = 0
            for (i in 0 until n) {
                val tm = fixes[i].timeMs
                while (k + 1 < b.size && b[k + 1].timeMs <= tm) k++
                val lo = b[k]
                val hi = b.getOrNull(k + 1)
                val maxGapMs = c.baroMaxGapS * 1000
                when {
                    lo.timeMs == tm -> { alt[i] = lo.altM; covered++ }
                    lo.timeMs < tm && hi != null && tm - lo.timeMs <= maxGapMs && hi.timeMs - tm <= maxGapMs -> {
                        alt[i] = lo.altM + (hi.altM - lo.altM) * (tm - lo.timeMs) / (hi.timeMs - lo.timeMs).toDouble()
                        covered++
                    }
                }
            }
            if (covered >= c.minBaroCoverage * n) {
                notes.add(NOTE_BARO_ALTITUDE)
                return fillGaps(t, alt) to AltitudeSource.BAROMETER
            }
            notes.add(NOTE_BARO_SHORT)
        }
        val alt = DoubleArray(n) { i ->
            val f = fixes[i]
            val a = f.altM
            val va = f.verticalAccuracyM
            if (a != null && a.isFinite() && (va == null || (va.isFinite() && va <= c.maxVerticalAccuracyM))) a else Double.NaN
        }
        if (alt.count { !it.isNaN() } < c.minAltitudeFixes) {
            notes.add(NOTE_NO_ALTITUDE)
            return null to AltitudeSource.NONE
        }
        if (NOTE_BARO_SHORT !in notes) notes.add(NOTE_GPS_ALTITUDE)
        return fillGaps(t, alt) to AltitudeSource.GPS
    }

    /** Linear interpolation in time over NaN entries; the ends hold the nearest value. */
    private fun fillGaps(t: DoubleArray, v: DoubleArray): DoubleArray {
        val out = v.copyOf()
        var prev = -1
        for (i in out.indices) {
            if (out[i].isNaN()) continue
            if (prev < 0) for (j in 0 until i) out[j] = out[i]
            else for (j in prev + 1 until i) out[j] = out[prev] + (out[i] - out[prev]) * (t[j] - t[prev]) / (t[i] - t[prev])
            prev = i
        }
        if (prev >= 0) for (j in prev + 1 until out.size) out[j] = out[prev]
        return out
    }

    /** Mean of [v] over fixes within [half] seconds either side. */
    internal fun windowMean(t: DoubleArray, v: DoubleArray, half: Double): DoubleArray {
        val out = DoubleArray(t.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in t.indices) {
            while (hi < t.size && t[hi] <= t[i] + half) { sum += v[hi]; hi++ }
            while (t[lo] < t[i] - half) { sum -= v[lo]; lo++ }
            out[i] = sum / (hi - lo)
        }
        return out
    }

    /** Least-squares slope of [v] against time over fixes within [half] seconds either side (m/s for altitude). */
    internal fun windowSlope(t: DoubleArray, v: DoubleArray, half: Double): DoubleArray {
        val out = DoubleArray(t.size)
        var lo = 0
        var hi = 0
        for (i in t.indices) {
            while (hi < t.size && t[hi] <= t[i] + half) hi++
            while (t[lo] < t[i] - half) lo++
            var a = lo
            var b = hi - 1
            if (b - a < 2) { a = max(0, i - 1); b = min(t.size - 1, i + 1) }
            var mt = 0.0
            var mv = 0.0
            for (j in a..b) { mt += t[j]; mv += v[j] }
            val count = (b - a + 1).toDouble()
            mt /= count; mv /= count
            var stt = 0.0
            var stv = 0.0
            for (j in a..b) { stt += (t[j] - mt) * (t[j] - mt); stv += (t[j] - mt) * (v[j] - mv) }
            out[i] = if (stt > 1e-9) stv / stt else 0.0
        }
        return out
    }

    /** Ground speed from the chord between the fixes [half] seconds either side, within [first]..[last]. */
    private fun chordSpeed(t: DoubleArray, x: DoubleArray, y: DoubleArray, half: Double,
                           first: Int = 0, last: Int = t.size - 1): DoubleArray {
        val out = DoubleArray(t.size) { Double.NaN }
        var lo = first
        var hi = first
        for (i in first..last) {
            while (lo < i && t[lo] < t[i] - half) lo++
            if (hi < i) hi = i
            while (hi + 1 <= last && t[hi + 1] <= t[i] + half) hi++
            var a = lo
            var b = hi
            if (a == b) { a = max(first, i - 1); b = min(last, i + 1) }
            val dt = t[b] - t[a]
            out[i] = if (dt > 0) LocalFrame.dist(x[a], y[a], x[b], y[b]) / dt else 0.0
        }
        return out
    }
}
