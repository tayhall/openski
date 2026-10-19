package com.openski.android

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Settings of whole-day tracking ([DayTrackingService]). Starting values, chosen from typical skiing, lift and queue
 * speeds; none has been tuned on a real day. The battery cost of any of them is unmeasured.
 */
object DayTrackingConfig {
    /** Location interval while moving (skiing, riding a lift): one fix a second, as the run GPS uses. */
    const val MOVING_INTERVAL_MS = 1_000L
    /** Location interval after a long still spell (lunch, a long queue): saves some battery; the splitter copes with it. */
    const val STATIONARY_INTERVAL_MS = 5_000L
    /** Ground speed (m/s) below which a fix counts as still. Queues shuffle at about 0.3 m/s. */
    const val STILL_MPS = 0.7
    /** Ground speed (m/s) at or above which a still phone counts as moving again (hysteresis against GPS jitter). */
    const val MOVE_MPS = 1.5
    /** Still for this long, within [STILL_RADIUS_M] of where it started, before the slower interval is used. */
    const val STILL_AFTER_MS = 120_000L
    /** Leaving this radius (m) around the point where stillness began counts as moving, whatever the speed says. */
    const val STILL_RADIUS_M = 30.0
    /** Fixes less accurate than this (m) are stored but do not steer the interval. Same limit as [RunTrack.MAX_ACCURACY_M]. */
    const val CADENCE_MAX_ACCURACY_M = 25f
    /** A chord speed is only taken between fixes at most this far apart (ms). */
    const val CADENCE_MAX_CHORD_MS = 30_000L
    /** Without a reported speed, the chord is taken over at least this long when the fixes allow (damps jitter). */
    const val CADENCE_MIN_CHORD_MS = 5_000L

    /** Fixes are buffered and written together once this many are waiting... */
    const val BATCH_ROWS = 30
    /** ...or once the oldest has waited this long (ms). At most this much is lost if the process dies. */
    const val BATCH_AGE_MS = 30_000L

    /** Barometer sampling period requested from the platform (microseconds). */
    const val BARO_PERIOD_US = 1_000_000
    /** A fix takes the mean of the pressure readings from the last this many ms; none that recent means no barometer value. */
    const val BARO_MAX_AGE_MS = 5_000L
    /** Standard atmosphere sea-level pressure (hPa). Only altitude differences are used, so the offset does not matter. */
    const val SEA_LEVEL_HPA = 1013.25
    /** Readings outside this range (hPa) are not a plausible air pressure on a mountain and are ignored. */
    const val MIN_PLAUSIBLE_HPA = 300.0
    const val MAX_PLAUSIBLE_HPA = 1100.0
}

/** Pressure to altitude with the standard atmosphere formula (the same one as the platform's SensorManager.getAltitude). */
object Barometer {
    /** Altitude in metres for [hPa], or null for a reading that is not a plausible air pressure. */
    fun altitudeM(hPa: Double, seaLevelHpa: Double = DayTrackingConfig.SEA_LEVEL_HPA): Double? {
        if (!hPa.isFinite() || hPa < DayTrackingConfig.MIN_PLAUSIBLE_HPA || hPa > DayTrackingConfig.MAX_PLAUSIBLE_HPA) return null
        if (!seaLevelHpa.isFinite() || seaLevelHpa <= 0) return null
        return 44_330.0 * (1.0 - (hPa / seaLevelHpa).pow(1.0 / 5.255))
    }
}

/**
 * Recent pressure readings, so each fix can take the mean of the last few seconds. Times are the phone's
 * elapsed-realtime clock in ms (the GPS fix time is satellite time and is not compared with it).
 */
class BaroWindow(private val maxAgeMs: Long = DayTrackingConfig.BARO_MAX_AGE_MS) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()

    fun add(hPa: Double, elapsedMs: Long) {
        if (Barometer.altitudeM(hPa) == null) return
        times.addLast(elapsedMs); values.addLast(hPa)
        while (times.size > 1 && elapsedMs - times.first() > maxAgeMs) { times.removeFirst(); values.removeFirst() }
    }

    /** Altitude (m) from the mean pressure of readings within [maxAgeMs] before [elapsedMs]; null when there are none. */
    fun altitudeAt(elapsedMs: Long): Double? {
        var sum = 0.0; var n = 0
        for (i in times.indices) {
            val age = elapsedMs - times[i]
            if (age in 0..maxAgeMs) { sum += values[i]; n++ }
        }
        return if (n == 0) null else Barometer.altitudeM(sum / n)
    }
}

/**
 * Decides the location interval: [DayTrackingConfig.MOVING_INTERVAL_MS] normally, [DayTrackingConfig.STATIONARY_INTERVAL_MS]
 * after [DayTrackingConfig.STILL_AFTER_MS] of stillness in one place, and back to moving as soon as a fix is fast
 * or outside the still radius. Only fixes with usable accuracy steer it; times are the fixes' own times.
 */
class CadencePlanner {
    var intervalMs: Long = DayTrackingConfig.MOVING_INTERVAL_MS
        private set
    val stationary: Boolean get() = intervalMs == DayTrackingConfig.STATIONARY_INTERVAL_MS
    private var previous: GpsFix? = null
    private var anchor: GpsFix? = null
    private var stillSinceMs: Long? = null

    /** Feeds one fix and returns the interval to use from now on. */
    fun onFix(fix: GpsFix): Long {
        if (!(fix.accuracyM <= DayTrackingConfig.CADENCE_MAX_ACCURACY_M) || !fix.lat.isFinite() || !fix.lon.isFinite()) return intervalMs
        val last = previous
        if (last != null && fix.timeMs <= last.timeMs) return intervalMs
        previous = fix
        val speed = speedOf(fix, last) ?: return intervalMs
        val a = anchor
        val awayFromAnchor = a != null && distanceM(a, fix) > DayTrackingConfig.STILL_RADIUS_M
        if (stationary) {
            if (speed >= DayTrackingConfig.MOVE_MPS || awayFromAnchor) {
                intervalMs = DayTrackingConfig.MOVING_INTERVAL_MS
                stillSinceMs = null; anchor = null
            }
            return intervalMs
        }
        if (speed >= DayTrackingConfig.STILL_MPS || awayFromAnchor) {
            stillSinceMs = null; anchor = null
            if (speed < DayTrackingConfig.STILL_MPS) { stillSinceMs = fix.timeMs; anchor = fix }   // still again, somewhere new
            return intervalMs
        }
        val since = stillSinceMs
        if (since == null) { stillSinceMs = fix.timeMs; anchor = fix }
        else if (fix.timeMs - since >= DayTrackingConfig.STILL_AFTER_MS) intervalMs = DayTrackingConfig.STATIONARY_INTERVAL_MS
        return intervalMs
    }

    /** Recent usable fixes, so a chord speed can span a few seconds (1 s chords are mostly GPS jitter). */
    private val recent = ArrayDeque<GpsFix>()

    private fun speedOf(fix: GpsFix, last: GpsFix?): Double? {
        recent.addLast(fix)
        while (recent.size > 1 && fix.timeMs - recent.first().timeMs > DayTrackingConfig.CADENCE_MAX_CHORD_MS) recent.removeFirst()
        // A reported speed wins; a negative one (never sent by Android, but seen in files) reads as zero.
        fix.speedMps?.takeIf { it.isFinite() }?.let { return it.coerceAtLeast(0.0) }
        if (last == null) return null
        val base = recent.lastOrNull { fix.timeMs - it.timeMs >= DayTrackingConfig.CADENCE_MIN_CHORD_MS } ?: last
        val dt = fix.timeMs - base.timeMs
        if (dt !in 1..DayTrackingConfig.CADENCE_MAX_CHORD_MS) return null
        return distanceM(base, fix) / (dt / 1000.0)
    }

    companion object {
        /** Flat-earth distance in metres; plenty for points seconds apart. */
        fun distanceM(a: GpsFix, b: GpsFix): Double {
            val k = LocalFrame.EARTH_M * Math.PI / 180.0
            val dy = (b.lat - a.lat) * k
            val dx = (b.lon - a.lon) * k * cos(Math.toRadians((a.lat + b.lat) / 2))
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/**
 * Buffers fixes so they are written in batches: [add] returns a batch to write once [maxRows] are waiting or the
 * oldest has waited [maxAgeMs] (phone elapsed time). A batch whose write failed goes back with [requeue] and is
 * written with the next one, oldest first.
 */
class FixBatcher(private val maxRows: Int = DayTrackingConfig.BATCH_ROWS, private val maxAgeMs: Long = DayTrackingConfig.BATCH_AGE_MS) {
    init { require(maxRows > 0 && maxAgeMs >= 0) }
    private val rows = ArrayList<DayFix>()
    private var firstAtMs: Long? = null
    val pending: Int get() = rows.size

    fun add(row: DayFix, elapsedMs: Long): List<DayFix>? {
        if (rows.isEmpty()) firstAtMs = elapsedMs
        rows.add(row)
        val first = firstAtMs ?: elapsedMs
        return if (rows.size >= maxRows || elapsedMs - first >= maxAgeMs) drain() else null
    }

    /** Everything waiting, oldest first; empties the buffer. */
    fun drain(): List<DayFix> {
        val out = ArrayList(rows)
        rows.clear(); firstAtMs = null
        return out
    }

    /** Puts a batch that could not be written back in front of anything newer. */
    fun requeue(batch: List<DayFix>, elapsedMs: Long) {
        if (batch.isEmpty()) return
        rows.addAll(0, batch)
        if (firstAtMs == null) firstAtMs = elapsedMs
    }
}
