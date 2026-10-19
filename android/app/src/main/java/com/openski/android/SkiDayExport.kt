package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Builds the content of `ski-day.json`: the derived segments, the names they were matched to with scores, the
 * thresholds used and an OpenStreetMap attribution when any name or geometry came from the map. Pure, NaN-safe
 * (non-finite numbers become null). The raw fixes are exported separately and untouched (`ski-day.gpx`).
 */
object SkiDayExport {
    const val FORMAT = "openski_ski_day"
    const val VERSION = 1
    const val NOTE = "EXPERIMENTAL and unvalidated: descents, lifts and rests are derived from phone GPS (and the barometer " +
        "when present) with starting thresholds that have not been tuned on a real day on snow. Piste and lift names come " +
        "from snapping to cached OpenStreetMap lines and may be wrong; unmatched means no confident match. GPS numbers " +
        "are not a measure of technique. The raw fixes are kept untouched and the split can be recomputed from them."

    /** [matches] lines up with [SkiDaySplit.segments] (null entries for REST or when not attempted); null when no map. */
    fun summary(split: SkiDaySplit, matches: List<WayMatch?>? = null): Map<String, Any?> {
        var usedMap = false
        val segments = split.segments.mapIndexed { i, s ->
            val m = matches?.getOrNull(i)
            if (m != null && m.status == MatchStatus.MATCHED) usedMap = true
            linkedMapOf<String, Any?>(
                "index" to i, "kind" to s.kind.name.lowercase(),
                "start_ms" to s.startMs, "end_ms" to s.endMs,
                "start_utc" to Instant.ofEpochMilli(s.startMs).toString(), "end_utc" to Instant.ofEpochMilli(s.endMs).toString(),
                "duration_s" to s.durationS, "first_fix" to s.firstIndex, "last_fix" to s.lastIndex,
                "distance_m" to s.distanceM, "vertical_m" to s.verticalM,
                "mean_speed_mps" to s.meanSpeedMps, "top_speed_mps" to s.topSpeedMps,
                "map_confirmed" to s.mapConfirmed, "notes" to s.notes,
                "match" to matchMap(s, m),
            )
        }
        return linkedMapOf(
            "format" to FORMAT, "version" to VERSION, "experimental" to true, "note" to NOTE,
            "altitude_source" to split.altitudeSource.name.lowercase(),
            "notes" to split.notes,
            "fixes" to mapOf("total" to split.totalFixes, "usable" to split.fixes.size),
            "thresholds" to split.config.asMap(),
            "segments" to segments,
            "attribution" to if (usedMap) ResortMap.ATTRIBUTION else null,
        )
    }

    private fun matchMap(s: SkiDaySegment, m: WayMatch?): Map<String, Any?> = when {
        s.kind == SegmentKind.REST || m == null -> mapOf("status" to "not_attempted")
        else -> linkedMapOf(
            "status" to m.status.name.lowercase(), "score" to m.score, "covered_fraction" to m.coveredFraction,
            "ways" to m.ways.map { w ->
                linkedMapOf<String, Any?>(
                    "osm_id" to w.way.osmId, "kind" to w.way.kind.name.lowercase(), "name" to w.way.name, "ref" to w.way.ref,
                    "difficulty" to w.way.difficulty, "aerialway" to w.way.aerialway,
                    "covered_fraction" to w.coveredFraction, "score" to w.score)
            })
    }

    fun toJson(split: SkiDaySplit, matches: List<WayMatch?>? = null): JSONObject = json(summary(split, matches)) as JSONObject

    /** Maps, lists and numbers to org.json values; NaN and infinities become null. */
    fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), json(v)) } }
        is List<*> -> JSONArray().also { a -> value.forEach { a.put(json(it)) } }
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value.toDouble() else JSONObject.NULL
        else -> value
    }
}
