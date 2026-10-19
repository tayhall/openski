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
    /** Mean ground speed (m/s) over [STILL_WINDOW_MS] below which the skier counts as still. Queues creep at about 0.3 m/s. */
    const val STILL_MPS = 0.7
    /** Window (ms) over which the speeds are averaged for the stillness decision, so single noisy fixes do not decide it. */
    const val STILL_WINDOW_MS = 30_000L
    /** A window needs at least this many speeds before it can count as still. */
    const val STILL_MIN_SAMPLES = 3
    /** Ground speed (m/s) of one fix at or above which a still phone counts as moving again (hysteresis against GPS jitter). */
    const val MOVE_MPS = 1.5
    /** Still (window mean under [STILL_MPS]) for this long before the slower interval is used. */
    const val STILL_AFTER_MS = 120_000L
    /**
     * Once slowed down, drifting this far (m) from where the slower interval began counts as moving, whatever the speed
     * says (a guard against a speed read low). Large enough that a long queue creeping at 0.3 m/s stays slowed for minutes.
     */
    const val STATIONARY_EXIT_RADIUS_M = 150.0

    /** Rows waiting to be written are capped here (about an hour at one fix a second); beyond it the oldest are dropped and counted. */
    const val MAX_BACKLOG_ROWS = 3_600
    /** After a failed write the next attempt waits this long, doubling per failure up to [MAX_RETRY_BACKOFF_MS]. */
    const val MIN_RETRY_BACKOFF_MS = 5_000L
    const val MAX_RETRY_BACKOFF_MS = 300_000L
    /** Attempts at the final write when a day is stopped, before the day is kept open with an error. */
    const val CLOSE_ATTEMPTS = 3
    /** An open phone day whose last fix is older than this is closed at that fix instead of being resumed. */
    const val STALE_OPEN_DAY_MS = 12 * 60 * 60 * 1000L
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
 * Decides the location interval: [DayTrackingConfig.MOVING_INTERVAL_MS] normally, and
 * [DayTrackingConfig.STATIONARY_INTERVAL_MS] once the mean speed over the last [DayTrackingConfig.STILL_WINDOW_MS] has
 * stayed under [DayTrackingConfig.STILL_MPS] for [DayTrackingConfig.STILL_AFTER_MS] (so a queue creeping forward slows
 * down too). Back to moving as soon as one fix reaches [DayTrackingConfig.MOVE_MPS] or the phone drifts
 * [DayTrackingConfig.STATIONARY_EXIT_RADIUS_M] from where it slowed down. Only fixes with usable accuracy steer it;
 * times are the fixes' own times.
 */
class CadencePlanner {
    var intervalMs: Long = DayTrackingConfig.MOVING_INTERVAL_MS
        private set
    val stationary: Boolean get() = intervalMs == DayTrackingConfig.STATIONARY_INTERVAL_MS
    private var previous: GpsFix? = null
    private var anchor: GpsFix? = null
    private var stillSinceMs: Long? = null
    private val speeds = ArrayDeque<Pair<Long, Double>>()

    /** Feeds one fix and returns the interval to use from now on. */
    fun onFix(fix: GpsFix): Long {
        if (!(fix.accuracyM <= DayTrackingConfig.CADENCE_MAX_ACCURACY_M) || !fix.lat.isFinite() || !fix.lon.isFinite()) return intervalMs
        val last = previous
        if (last != null && fix.timeMs <= last.timeMs) return intervalMs
        previous = fix
        val speed = speedOf(fix, last) ?: return intervalMs
        speeds.addLast(fix.timeMs to speed)
        while (fix.timeMs - speeds.first().first > DayTrackingConfig.STILL_WINDOW_MS) speeds.removeFirst()
        if (stationary) {
            val a = anchor
            if (speed >= DayTrackingConfig.MOVE_MPS || (a != null && distanceM(a, fix) > DayTrackingConfig.STATIONARY_EXIT_RADIUS_M)) {
                intervalMs = DayTrackingConfig.MOVING_INTERVAL_MS
                stillSinceMs = null; anchor = null
                speeds.clear(); speeds.addLast(fix.timeMs to speed)
            }
            return intervalMs
        }
        val still = speeds.size >= DayTrackingConfig.STILL_MIN_SAMPLES && speeds.sumOf { it.second } / speeds.size < DayTrackingConfig.STILL_MPS
        if (!still) { stillSinceMs = null; return intervalMs }
        val since = stillSinceMs ?: fix.timeMs.also { stillSinceMs = it }
        if (fix.timeMs - since >= DayTrackingConfig.STILL_AFTER_MS) {
            intervalMs = DayTrackingConfig.STATIONARY_INTERVAL_MS
            anchor = fix
        }
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
 * Buffers fixes on the main thread so they go to the writer in batches: [add] returns a batch once [maxRows] are
 * waiting or the oldest has waited [maxAgeMs] (phone elapsed time).
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
}

/**
 * Rows handed to the writer but not yet stored, owned by the writer thread. Writes go out in chunks of at most
 * [chunkRows] (one transaction each), so a failure never leaves a half-written chunk to be written twice. The backlog
 * is capped at [cap] rows (the oldest are dropped and counted, never silently), and after a failure the next attempt
 * waits a backoff that doubles up to [maxBackoffMs]. Pure; times are the phone's elapsed clock in ms.
 */
class FixBacklog(
    private val cap: Int = DayTrackingConfig.MAX_BACKLOG_ROWS,
    private val minBackoffMs: Long = DayTrackingConfig.MIN_RETRY_BACKOFF_MS,
    private val maxBackoffMs: Long = DayTrackingConfig.MAX_RETRY_BACKOFF_MS,
    private val chunkRows: Int = DayRules.FIX_BATCH_ROWS,
) {
    init { require(cap > 0 && chunkRows > 0 && minBackoffMs >= 0 && maxBackoffMs >= minBackoffMs) }
    private val rows = ArrayDeque<DayFix>()
    private var retryAtMs = Long.MIN_VALUE
    private var backoffMs = 0L
    val size: Int get() = rows.size
    /** Rows dropped because the backlog was full, since tracking started. */
    var dropped = 0
        private set
    /** Failures in a row; 0 after a successful write. */
    var failures = 0
        private set
    var lastError: String? = null
        private set

    fun add(batch: List<DayFix>) {
        rows.addAll(batch)
        while (rows.size > cap) { rows.removeFirst(); dropped++ }
    }

    /** True when a write may be tried at [nowMs] (nothing waiting, or the backoff has passed). */
    fun due(nowMs: Long): Boolean = rows.isNotEmpty() && nowMs >= retryAtMs

    /**
     * Writes everything waiting, chunk by chunk, with [write] (which throws on failure). Stops at the first failure,
     * keeps the unwritten rows and sets the backoff. Returns true when the backlog is empty afterwards.
     */
    fun flush(nowMs: Long, write: (List<DayFix>) -> Unit, ignoreBackoff: Boolean = false): Boolean {
        if (!ignoreBackoff && !due(nowMs)) return rows.isEmpty()
        while (rows.isNotEmpty()) {
            val chunk = rows.take(chunkRows)
            try { write(chunk) } catch (e: Exception) {
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                backoffMs = if (backoffMs == 0L) minBackoffMs else minOf(backoffMs * 2, maxBackoffMs)
                retryAtMs = nowMs + backoffMs
                return false
            }
            repeat(chunk.size) { rows.removeFirst() }
        }
        failures = 0; backoffMs = 0; retryAtMs = Long.MIN_VALUE; lastError = null
        return true
    }

    /** The wait before the next attempt, for the error message. */
    val backoff: Long get() = backoffMs
}

/** What happened when a day was stopped. */
sealed class CloseOutcome {
    object Closed : CloseOutcome()
    /** Fixes could not be stored (or the day could not be marked ended): the day stays open, nothing is lost silently. */
    data class KeptOpen(val reason: String, val unwritten: Int) : CloseOutcome()
}

/** Stopping a day: write what is waiting (with retries), and close the day only when that succeeded. Pure. */
object DayCloser {
    fun close(backlog: FixBacklog, write: (List<DayFix>) -> Unit, finish: () -> Unit,
              attempts: Int = DayTrackingConfig.CLOSE_ATTEMPTS, pause: (Int) -> Unit = {}): CloseOutcome {
        require(attempts > 0)
        for (attempt in 1..attempts) {
            if (backlog.flush(0, write, ignoreBackoff = true)) break
            if (attempt < attempts) pause(attempt)
        }
        if (backlog.size > 0) return CloseOutcome.KeptOpen("could not save the last ${backlog.size} fixes (${backlog.lastError}); " +
            "the day stays open and saving is retried", backlog.size)
        return try { finish(); CloseOutcome.Closed } catch (e: Exception) {
            CloseOutcome.KeptOpen("could not mark the day as ended (${e.message ?: e.javaClass.simpleName}); the day stays open", 0)
        }
    }
}

/** Freshness of a last-known position before it is used to download the area "around me". Pure. */
object PositionAge {
    /** A last-known position older than this is not used; a fresh fix is asked for instead. */
    const val MAX_AGE_MS = 5 * 60 * 1000L
    /** [ageMs] from the phone's elapsed clock; a negative age (clock oddity) is not trusted either. */
    fun fresh(ageMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean = ageMs in 0..maxAgeMs
}

/**
 * Whether resort downloads must wait for a recording. The sensor service is the authority when it is running; when it
 * is not, an open session in the database counts only if it shows activity within [STALE_RECORDING_MS], so a session
 * left open by a crash cannot block downloads for ever. Pure.
 */
object RecordingGuard {
    const val STALE_RECORDING_MS = 10 * 60 * 1000L
    fun blocks(serviceBound: Boolean, serviceRecording: Boolean, openSessionLastActivityMs: Long?, nowMs: Long): Boolean = when {
        serviceBound -> serviceRecording
        openSessionLastActivityMs == null -> false
        else -> nowMs - openSessionLastActivityMs <= STALE_RECORDING_MS
    }
}
