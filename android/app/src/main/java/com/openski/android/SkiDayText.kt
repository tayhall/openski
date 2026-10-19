package com.openski.android

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Words and numbers for the ski day and resort screens. Pure, JVM-tested; nothing here measures anything. */
object SkiDayText {
    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "–"
        val s = seconds.roundToLong()
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun distance(m: Double?): String = when {
        m == null || !m.isFinite() -> "–"
        m < 1000 -> "${m.roundToInt()} m"
        else -> String.format(Locale.US, "%.1f km", m / 1000)
    }

    fun speed(mps: Double?): String = if (mps == null || !mps.isFinite()) "–" else "${(mps * 3.6).roundToInt()} km/h"

    fun vertical(m: Double?): String = if (m == null || !m.isFinite()) "–" else "${m.roundToInt()} m"

    /** "3 days ago", "today"; a time in the future (clock change) reads "just now". */
    fun age(ageMs: Long): String {
        val days = ageMs / 86_400_000L
        return when {
            ageMs < 60_000L -> "just now"
            ageMs < 3_600_000L -> "${ageMs / 60_000L} min ago"
            days < 1 -> "${ageMs / 3_600_000L} h ago"
            days == 1L -> "1 day ago"
            else -> "$days days ago"
        }
    }

    /** A signed vertical in words: "412 m down", "35 m up" (a noisy descent can end higher than it began), "0 m". */
    fun verticalWord(m: Double): String {
        if (!m.isFinite()) return "–"
        val r = m.roundToInt()
        return when { r < 0 -> "${-r} m down"; r > 0 -> "$r m up"; else -> "0 m" }
    }

    /** One line for a segment: duration, vertical, distance and top speed where known. */
    fun segmentLine(r: ReportSegment): String {
        val s = r.segment
        return when (r.kind) {
            SegmentKind.DESCENT -> listOfNotNull(duration(s.durationS), s.verticalM?.let(::verticalWord),
                distance(s.distanceM), s.topSpeedMps?.let { "top ${speed(it)}" }).joinToString(" · ")
            SegmentKind.LIFT -> listOfNotNull(duration(s.durationS), s.verticalM?.let(::verticalWord)).joinToString(" · ")
            SegmentKind.REST -> duration(s.durationS)
        }
    }

    /** Boot numbers for a descent in one line, or null when there is nothing to show. */
    fun bootLine(b: BootRangeStats?): String? {
        if (b == null) return null
        if (!b.hasNumbers) return "Boot session overlaps, no boot numbers" + (b.notes.firstOrNull()?.let { ": $it" } ?: "")
        val parts = ArrayList<String>()
        val counts = b.turnCounts
        if (counts.isNotEmpty()) {
            val perBoot = counts.entries.joinToString(", ") { (side, n) -> "${if (side == "L") "left" else "right"} $n" }
            val busiest = counts.values.maxOrNull() ?: 0
            parts.add("${b.turnSource} $perBoot" + (b.perMinute(busiest)?.let { String.format(Locale.US, " (up to %.0f/min)", it) } ?: ""))
        }
        b.meanRollCorrelation?.let { parts.add(String.format(Locale.US, "boot agreement %.2f", it)) }
        b.meanTorsoGyroRmsDps?.let { parts.add(String.format(Locale.US, "torso %.0f °/s", it)) }
        return "Boots: " + parts.joinToString(" · ")
    }

    /** What went wrong with a resort download, in words, and whether a cached copy was kept. */
    fun download(result: DownloadResult): String {
        val kept = result.kept?.let { " The cached copy from ${java.time.Instant.ofEpochMilli(it.fetchedAtMs).toString().take(10)} is kept." } ?: ""
        return when (result) {
            is DownloadResult.Saved -> "Saved ${result.resort.wayCount} pistes and lifts." +
                if (result.truncated) " The area was cut to ${OverpassQuery.MAX_RADIUS_M / 1000} km around its centre." else ""
            is DownloadResult.InvalidRequest -> "Cannot ask for that area: ${result.message}.$kept"
            is DownloadResult.Blocked -> "Not downloading: ${result.reason}.$kept"
            is DownloadResult.FetchFailed -> fetch(result.error) + kept
            is DownloadResult.ParseFailed -> "The map server's answer could not be read (it may have been truncated): ${result.message}.$kept"
            is DownloadResult.Partial -> "The map server cut its answer short (truncated: ${result.remark}). Nothing was saved; try a smaller area or later.$kept"
            is DownloadResult.NoData -> "No pistes or lifts are mapped in that area. Nothing was saved.$kept"
            is DownloadResult.StoreFailed -> "Saving the map on this phone failed: ${result.message}.$kept"
        }
    }

    fun search(result: SearchResult): String = when (result) {
        is SearchResult.Found -> when {
            result.candidates.isEmpty() -> "No ski area of that name is mapped."
            result.maybeMore -> "${result.candidates.size} found; there may be more, so try a longer name."
            else -> "${result.candidates.size} found."
        }
        is SearchResult.InvalidRequest -> "Cannot search for that: ${result.message}."
        is SearchResult.FetchFailed -> fetch(result.error)
        is SearchResult.ParseFailed -> "The map server's answer could not be read (it may have been truncated): ${result.message}."
    }

    fun fetch(e: FetchError): String = when (e.kind) {
        FetchError.Kind.NETWORK -> "No connection to the map server. Are you offline? Cached maps still work."
        FetchError.Kind.TIMEOUT -> "The map server did not answer in time. Try again later or a smaller area."
        FetchError.Kind.TOO_LARGE -> "The answer was too large for this phone. Try a smaller area."
        FetchError.Kind.HTTP -> when (e.httpCode) {
            429 -> "The map server is rate limiting requests. Wait a few minutes and try again."
            504 -> "The map server is busy (timeout). Try again later."
            else -> "The map server answered with an error (HTTP ${e.httpCode ?: "?"}): ${e.message}"
        }
        FetchError.Kind.MAIN_THREAD -> "Internal error: the download ran on the screen thread. Nothing was sent."
        FetchError.Kind.INTERRUPTED -> "The download was interrupted."
        FetchError.Kind.FAILED -> "The download failed: ${e.message}"
    }
}
