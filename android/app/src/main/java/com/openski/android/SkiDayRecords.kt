package com.openski.android

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where a day's fixes came from: the phone's own tracking or an imported GPX file. */
object DaySource {
    const val PHONE = "phone"
    const val GPX = "gpx"
    val ALL = listOf(PHONE, GPX)
}

/**
 * Condition tags for a day or one run. They describe the skier's view of the day and never alter raw data. Every value
 * is optional; [drill] is the title of a drill practised (free text). Values are trimmed and capped at [MAX_LENGTH].
 */
data class DayTags(val snow: String? = null, val visibility: String? = null, val crowd: String? = null, val drill: String? = null) {
    val isEmpty: Boolean get() = snow == null && visibility == null && crowd == null && drill == null

    /** The set tags as label/value pairs, in a fixed order. */
    fun entries(): List<Pair<String, String>> = listOfNotNull(snow?.let { "Snow" to it }, visibility?.let { "Visibility" to it },
        crowd?.let { "Crowd" to it }, drill?.let { "Drill" to it })

    fun toMap(): Map<String, Any?> = linkedMapOf("snow" to snow, "visibility" to visibility, "crowd" to crowd, "drill" to drill)

    fun toJson(): String = JSONObject().apply { toMap().forEach { (k, v) -> if (v != null) put(k, v) } }.toString()

    companion object {
        /** Offered choices; stored text is free, so a value outside these lists still round-trips. */
        val SNOW = listOf("powder", "fresh", "packed", "groomed", "hard", "icy", "slush", "crud")
        val VISIBILITY = listOf("good", "flat light", "fog", "snowing")
        val CROWD = listOf("quiet", "normal", "busy", "queues")
        const val MAX_LENGTH = 60

        /** Lenient: unknown keys are ignored, blank or non-text values are dropped, and unreadable text gives no tags. */
        fun fromJson(text: String?): DayTags {
            if (text.isNullOrBlank()) return DayTags()
            val o = try { JSONObject(text) } catch (_: Exception) { return DayTags() }
            fun value(key: String): String? = (o.opt(key) as? String)?.let(::clean)
            return DayTags(value("snow"), value("visibility"), value("crowd"), value("drill"))
        }

        /** Trimmed, control characters removed, capped; null when nothing is left. */
        fun clean(value: String?): String? = value?.filter { !it.isISOControl() }?.trim()?.take(MAX_LENGTH)?.takeIf { it.isNotEmpty() }
    }
}

/** One recorded or imported day. [endedAtMs] is null while a phone day is still being tracked. */
data class SkiDay(
    val id: String, val title: String, val startedAtMs: Long, val endedAtMs: Long?, val source: String,
    val resortId: String?, val notes: String, val tags: DayTags, val createdAtMs: Long,
) {
    init { require(source in DaySource.ALL) { "Unknown day source $source" } }
    val open: Boolean get() = endedAtMs == null
}

/** One stored fix of a day, untouched, with the barometric altitude read alongside it (null without a barometer). */
data class DayFix(val fix: GpsFix, val baroAltM: Double? = null)

/** Tags and a note for one run, keyed by the start of the descent they were given to. */
data class RunTag(val segmentStartMs: Long, val tags: DayTags, val note: String) {
    val isEmpty: Boolean get() = tags.isEmpty && note.isBlank()
}

/** Pure rules behind [DayStore] and the day screens, JVM-tested. */
object DayRules {
    /** Fixes are written in transactions of at most this many rows, so no write holds the database for long. */
    const val FIX_BATCH_ROWS = 200
    /** Notes longer than this are cut. */
    const val MAX_NOTE_LENGTH = 2_000
    const val MAX_TITLE_LENGTH = 80

    fun <T> chunks(rows: List<T>, size: Int = FIX_BATCH_ROWS): List<List<T>> {
        require(size > 0)
        return rows.chunked(size)
    }

    /**
     * True when the row can be stored: finite latitude and longitude inside their ranges. Optional values that are not
     * finite are stored as null (SQLite has no NaN), which is the only change ever made to a fix.
     */
    fun storable(f: GpsFix): Boolean = f.lat.isFinite() && f.lon.isFinite() && f.lat in -90.0..90.0 && f.lon in -180.0..180.0

    /** The open phone day to resume: the latest one still open. GPX days are never open for tracking. */
    fun resumable(days: List<SkiDay>): SkiDay? = days.filter { it.open && it.source == DaySource.PHONE }.maxByOrNull { it.startedAtMs }

    /** Which open day to resume and which open days to close, and when each closes. */
    data class ResumePlan(val resume: SkiDay?, val close: List<Pair<String, Long>>)

    /**
     * The latest open phone day is resumed unless its last fix (or its start, without fixes) is more than [staleMs]
     * before [nowMs]; then, like every other open phone day, it is closed at its last fix (or start). A day left open
     * overnight is therefore never continued the next morning.
     */
    fun resumePlan(days: List<SkiDay>, lastFixMs: (SkiDay) -> Long?, nowMs: Long,
                   staleMs: Long = DayTrackingConfig.STALE_OPEN_DAY_MS): ResumePlan {
        val open = days.filter { it.open && it.source == DaySource.PHONE }.sortedByDescending { it.startedAtMs }
        fun end(d: SkiDay) = maxOf(lastFixMs(d) ?: d.startedAtMs, d.startedAtMs)
        val latest = open.firstOrNull()?.takeIf { nowMs - end(it) <= staleMs }
        return ResumePlan(latest, open.filter { it != latest }.map { it.id to end(it) })
    }

    fun cleanNote(note: String): String = note.filter { it == '\n' || !it.isISOControl() }.trim().take(MAX_NOTE_LENGTH)
    fun cleanTitle(title: String): String = title.filter { !it.isISOControl() }.trim().take(MAX_TITLE_LENGTH)

    fun defaultTitle(startMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "Ski day " + DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(startMs).atZone(zone))

    /**
     * The tag of the descent spanning [startMs, endMs]: a stored tag whose key lies inside it, the one closest to its start
     * when several do. Tags are keyed by the descent start at the time they were written; a later change of the split can
     * move the start a little, so the key only has to fall inside the descent.
     */
    fun tagFor(startMs: Long, endMs: Long, tags: List<RunTag>): RunTag? =
        tags.filter { it.segmentStartMs in startMs..endMs }.minByOrNull { it.segmentStartMs - startMs }

    /** A day built from an imported GPX file: it starts and ends at its first and last fix and is never open. */
    fun gpxDay(id: String, read: GpxRead, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SkiDay {
        require(read.fixes.isNotEmpty()) { "The file has no usable track points" }
        val start = read.fixes.minOf { it.timeMs }
        val end = read.fixes.maxOf { it.timeMs }
        val title = cleanTitle(read.name ?: "").ifEmpty { "Imported " + defaultTitle(start, zone).removePrefix("Ski ").replaceFirstChar { it.lowercase() } }
        return SkiDay(id, title, start, end, DaySource.GPX, null, "", DayTags(), nowMs)
    }
}
