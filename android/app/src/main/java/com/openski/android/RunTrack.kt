package com.openski.android

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One phone location reading. Altitude, speed and vertical accuracy are null when the phone did not report them. */
data class GpsFix(
    val timeMs: Long, val lat: Double, val lon: Double, val altM: Double?, val speedMps: Double?,
    val accuracyM: Float, val verticalAccuracyM: Float?,
)

data class TrackSummary(
    val distanceM: Double, val topSpeedMps: Double?, val avgSpeedMps: Double?, val movingSeconds: Int,
    val verticalDropM: Double?, val usableFixes: Int, val totalFixes: Int,
)

/** What a card shows: a headline, label/value rows (empty when there is nothing to show) and a closing note. */
data class TrackReport(val headline: String, val rows: List<Pair<String, String>>, val note: String)

/** Phone GPS readings turned into run numbers. Dry-ski boot data and technique claims live elsewhere. */
object RunTrack {
    const val MAX_ACCURACY_M = 25f
    const val MAX_JUMP_MPS = 40.0
    const val MAX_GAP_MS = 10_000L
    const val MOVING_MPS = 0.5
    const val TOP_SPEED_HOLD_MS = 2_000L
    const val MIN_ALTITUDE_FIXES = 10
    const val NOTE = "Phone GPS readings, not a measure of technique."
    private const val CONSECUTIVE_MS = 3_000L
    private const val SMOOTH_WIDTH = 5
    private const val EARTH_M = 6_371_000.0

    /** A reading whose phone reported no accuracy is given a hopeless one so the filter drops it. */
    fun fixFrom(timeMs: Long, lat: Double, lon: Double, altM: Double?, speedMps: Float?, accuracyM: Float?,
                verticalAccuracyM: Float?, nowMs: Long) = GpsFix(
        if (timeMs > 0) timeMs else nowMs, lat, lon, altM, speedMps?.toDouble(), accuracyM ?: 999f, verticalAccuracyM)

    private fun distanceM(a: GpsFix, b: GpsFix): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(b.lon - a.lon) / 2).pow(2)
        return 2 * EARTH_M * asin(min(1.0, sqrt(h)))
    }

    /** Readings in time order, without poor accuracy, repeated or backwards times, or impossible jumps. */
    fun usable(fixes: List<GpsFix>): List<GpsFix> {
        val kept = ArrayList<GpsFix>()
        for (fix in fixes.sortedBy { it.timeMs }) {
            if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.accuracyM > MAX_ACCURACY_M) continue
            val previous = kept.lastOrNull()
            if (previous != null) {
                val dtMs = fix.timeMs - previous.timeMs
                if (dtMs <= 0) continue
                if (dtMs <= MAX_GAP_MS && distanceM(previous, fix) / (dtMs / 1000.0) > MAX_JUMP_MPS) continue
            }
            kept.add(fix)
        }
        return kept
    }

    private fun speeds(kept: List<GpsFix>): DoubleArray = DoubleArray(kept.size) { i ->
        val reported = kept[i].speedMps
        when {
            reported != null && reported >= 0 -> reported
            i == 0 -> 0.0
            else -> {
                val dtMs = kept[i].timeMs - kept[i - 1].timeMs
                if (dtMs in 1..MAX_GAP_MS) distanceM(kept[i - 1], kept[i]) / (dtMs / 1000.0) else 0.0
            }
        }
    }

    fun summarize(fixes: List<GpsFix>): TrackSummary {
        val kept = usable(fixes)
        val speed = speeds(kept)
        var distance = 0.0
        var movingMs = 0L
        for (i in 1 until kept.size) {
            val dtMs = kept[i].timeMs - kept[i - 1].timeMs
            if (dtMs > MAX_GAP_MS || speed[i] < MOVING_MPS) continue
            distance += distanceM(kept[i - 1], kept[i])
            movingMs += dtMs
        }
        return TrackSummary(
            distance, topSpeed(kept, speed),
            if (movingMs > 0) distance / (movingMs / 1000.0) else null,
            (movingMs / 1000.0).roundToInt(), verticalDrop(kept), kept.size, fixes.size)
    }

    /** The highest speed that stayed at or above that value for [TOP_SPEED_HOLD_MS], so one noisy fix cannot set it. */
    private fun topSpeed(kept: List<GpsFix>, speed: DoubleArray): Double? {
        var best: Double? = null
        for (i in kept.indices) {
            var low = speed[i]
            var j = i
            var broken = false
            while (kept[j].timeMs - kept[i].timeMs < TOP_SPEED_HOLD_MS) {
                if (j + 1 >= kept.size || kept[j + 1].timeMs - kept[j].timeMs > CONSECUTIVE_MS) { broken = true; break }
                j++
                low = min(low, speed[j])
            }
            if (!broken && (best == null || low > best)) best = low
        }
        return best
    }

    private fun verticalDrop(kept: List<GpsFix>): Double? {
        val altitudes = kept.filter { it.altM != null && it.verticalAccuracyM != null }.map { it.altM!! }
        if (altitudes.size < MIN_ALTITUDE_FIXES) return null
        val smooth = altitudes.indices.map { i ->
            altitudes.subList(max(0, i - SMOOTH_WIDTH / 2), min(altitudes.size, i + SMOOTH_WIDTH / 2 + 1)).average()
        }
        return max(0.0, smooth.first() - smooth.min())
    }

    private fun distanceText(m: Double) = if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.2f km", m / 1000)
    private fun speedText(mps: Double?) = if (mps == null) "not available" else "${(mps * 3.6).roundToInt()} km/h"

    fun report(demo: Boolean, gpsOn: Boolean, summary: TrackSummary?): TrackReport = when {
        demo -> TrackReport("No GPS in demo runs", emptyList(), NOTE)
        !gpsOn -> TrackReport("GPS was off for this run", emptyList(), NOTE)
        summary == null || summary.usableFixes == 0 -> TrackReport("No GPS fix during this run", emptyList(), NOTE)
        else -> TrackReport("On the hill", listOf(
            "Distance" to distanceText(summary.distanceM),
            "Top speed" to speedText(summary.topSpeedMps),
            "Average speed" to speedText(summary.avgSpeedMps),
            "Vertical drop" to (summary.verticalDropM?.let { "${it.roundToInt()} m" } ?: "not available"),
            "Fixes" to "${summary.usableFixes} of ${summary.totalFixes} usable",
        ), NOTE)
    }
}
