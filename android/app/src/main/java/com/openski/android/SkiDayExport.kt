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

    const val BOOT_NOTE = DayBootStats.NOTE

    /**
     * The whole `ski-day.json` for a stored day: [summary] plus the day's title, source, notes and tags, each segment's
     * run number, name, grade, run tags and boot numbers (descents with overlapping sensor data only), the day totals,
     * the resort name and the attribution whenever a resort map was used, and the barometric altitudes stored with the
     * fixes. Run tags that no longer fall inside a descent are listed under `unattached_run_tags`, never dropped.
     */
    fun dayDocument(day: SkiDay, report: SkiDayReport, runTags: List<RunTag> = emptyList(), resortName: String? = null,
                    baro: List<BaroSample> = emptyList()): Map<String, Any?> {
        val doc = LinkedHashMap(summary(report.split, report.matches))
        val attached = HashSet<Long>()
        @Suppress("UNCHECKED_CAST")
        val segments = (doc["segments"] as List<Map<String, Any?>>).mapIndexed { i, base ->
            val r = report.segments[i]
            val tag = if (r.kind == SegmentKind.DESCENT) DayRules.tagFor(r.segment.startMs, r.segment.endMs, runTags) else null
            tag?.let { attached.add(it.segmentStartMs) }
            LinkedHashMap(base).apply {
                put("title", r.title); put("number", r.number); put("name", r.wayLabel); put("grade", r.grade)
                put("run_tags", tag?.let { runTagMap(it) })
                put("boot", r.boot?.let(::bootMap))
            }
        }
        doc["segments"] = segments
        doc["day"] = linkedMapOf("id" to day.id, "title" to day.title, "source" to day.source,
            "started_at_ms" to day.startedAtMs, "ended_at_ms" to day.endedAtMs,
            "started_utc" to Instant.ofEpochMilli(day.startedAtMs).toString(),
            "ended_utc" to day.endedAtMs?.let { Instant.ofEpochMilli(it).toString() },
            "notes" to day.notes, "tags" to day.tags.toMap(), "created_at_ms" to day.createdAtMs)
        doc["unattached_run_tags"] = runTags.filter { it.segmentStartMs !in attached }.map(::runTagMap)
        val t = report.totals
        doc["totals"] = linkedMapOf("vertical_descended_m" to t.descendedM, "vertical_climbed_m" to t.climbedM,
            "runs" to t.runs, "lifts" to t.lifts, "longest_run_index" to t.longestRunIndex, "longest_run_m" to t.longestRunM,
            "skiing_s" to t.skiingS, "lift_s" to t.liftS, "rest_s" to t.restS, "distance_skied_m" to t.distanceSkiedM,
            "top_speed_mps" to t.topSpeedMps)
        doc["resort"] = if (report.mapUsed || day.resortId != null) linkedMapOf("id" to day.resortId, "name" to resortName,
            "map_used" to report.mapUsed) else null
        doc["attribution"] = report.attribution
        doc["boot_note"] = BOOT_NOTE
        doc["barometer"] = linkedMapOf("note" to "Barometric altitude in metres from the phone's pressure sensor with the " +
            "standard atmosphere formula (sea level 1013.25 hPa); only differences are meaningful.",
            "samples" to baro.map { listOf(it.timeMs, it.altM) })
        return doc
    }

    fun dayJson(day: SkiDay, report: SkiDayReport, runTags: List<RunTag> = emptyList(), resortName: String? = null,
                baro: List<BaroSample> = emptyList()): JSONObject = json(dayDocument(day, report, runTags, resortName, baro)) as JSONObject

    private fun runTagMap(t: RunTag): Map<String, Any?> = linkedMapOf("segment_start_ms" to t.segmentStartMs,
        "tags" to t.tags.toMap(), "note" to t.note)

    fun bootMap(b: BootRangeStats): Map<String, Any?> = linkedMapOf(
        "sessions" to b.sessions, "overlap_ms" to b.overlapMs,
        "movements" to b.movements, "movements_per_min" to b.movements.mapValues { b.perMinute(it.value) },
        "heading_turn_candidates" to b.headingTurns, "heading_turn_candidates_per_min" to b.headingTurns.mapValues { b.perMinute(it.value) },
        "half_turns" to b.halfTurns, "half_turns_per_min" to b.halfTurns.mapValues { b.perMinute(it.value) },
        "agreement_pairs" to b.agreementPairs, "mean_derivative_similarity" to b.meanDerivativeSimilarity,
        "mean_roll_correlation" to b.meanRollCorrelation, "mean_wedge_fraction" to b.meanWedgeFraction,
        "torso_turns" to b.torsoTurns, "mean_torso_gyro_rms_dps" to b.meanTorsoGyroRmsDps,
        "mean_torso_horizontal_accel_sd_mps2" to b.meanTorsoHorizontalAccelSd, "notes" to b.notes)

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
