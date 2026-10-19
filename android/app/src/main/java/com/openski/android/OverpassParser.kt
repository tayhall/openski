package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** `piste:difficulty` reduced to a small set. The raw value is always kept alongside; colours differ by country, so none are implied. */
object PisteDifficulty {
    const val EASY = "easy"
    const val INTERMEDIATE = "intermediate"
    const val ADVANCED = "advanced"
    const val EXPERT = "expert"
    const val FREERIDE = "freeride"
    /** A value was tagged but is not one of the known ones. */
    const val OTHER = "other"

    /** novice and easy -> easy; expert and extreme -> expert; freeride; anything else tagged -> other; untagged -> null. */
    fun normalise(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (value) {
            "novice", "easy" -> EASY
            "intermediate" -> INTERMEDIATE
            "advanced" -> ADVANCED
            "expert", "extreme" -> EXPERT
            "freeride" -> FREERIDE
            else -> OTHER
        }
    }
}

/**
 * A parsed way with the details [ResortWay] does not carry. [ResortWay.difficulty] keeps the raw `piste:difficulty`
 * (as ResortMap.kt defines it); [difficultyClass] is that value normalised by [PisteDifficulty]. [area] marks a closed
 * ring (an area piste outline) used as a line; [relationId] is set when the way came in as a member of a piste
 * relation whose tags it took. A way whose geometry has a gap (a null or unusable point) is split there into
 * [part] 0, 1, ... rather than bridged.
 */
data class ResortWayInfo(
    val way: ResortWay,
    val difficultyClass: String?,
    val operator: String?,
    val version: Int?,
    val area: Boolean,
    val relationId: Long? = null,
    val part: Int = 0,
)

/** An `aerialway=station` node, or a station mapped as a way (an outline), kept as the mean of its vertices. */
data class ResortStation(val osmId: Long, val name: String?, val point: LatLon, val operator: String?, val version: Int?,
                         val osmType: String = "node")

/**
 * Everything one resort query returned. [remark] is the server's remark (a timeout or memory error that cut the
 * answer short), so [partial] data must not replace a good cache. [skipped] counts elements that were unusable.
 */
data class ResortData(
    val ways: List<ResortWayInfo>,
    val stations: List<ResortStation>,
    val osmBase: String? = null,
    val remark: String? = null,
    val skipped: Int = 0,
) {
    val partial: Boolean get() = remark != null
    val isEmpty: Boolean get() = ways.isEmpty() && stations.isEmpty()
}

/** One resort a name search found. [box] is null for a node (a point only). */
data class ResortCandidate(val osmType: String, val osmId: Long, val name: String, val centre: LatLon, val box: GeoBox?) {
    val key: String get() = "$osmType/$osmId"
}

/** A name search answer: the usable candidates and how many elements the server returned (to spot a capped answer). */
data class ResortSearch(val candidates: List<ResortCandidate>, val elementCount: Int)

sealed class OverpassParse<out T> {
    data class Parsed<T>(val value: T) : OverpassParse<T>()
    data class Failed(val message: String) : OverpassParse<Nothing>()
}

/**
 * Overpass JSON to resort data. Tolerates missing tags, unnamed ways, null geometry points and unknown element
 * types; malformed or truncated JSON returns [OverpassParse.Failed] with a message. Never throws.
 *
 * Known limits: a relation member way that was not also returned on its own takes the relation's tags; member ways
 * are kept in the order and direction OSM stores them (not joined into rings, not turned to run downhill); `inner`
 * members of multipolygons (holes) are left out.
 */
object OverpassParser {
    fun parseResort(text: String): OverpassParse<ResortData> = guard {
        val root = root(text) ?: return@guard OverpassParse.Failed(notJson(text))
        val elements = root.optJSONArray("elements") ?: return@guard OverpassParse.Failed("No elements array in the response")
        val ways = LinkedHashMap<Long, List<ResortWayInfo>>()
        val stations = LinkedHashMap<String, ResortStation>()
        val relations = mutableListOf<JSONObject>()
        var skipped = 0
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i)
            if (element == null) { skipped++; continue }
            val ok = runCatching {
                when (element.optString("type")) {
                    "way" -> {
                        val id = element.getLong("id")
                        val tags = tags(element)
                        val segments = segments(element.optJSONArray("geometry"))
                        if (tags["aerialway"] == "station") {
                            stationFromWay(id, tags, segments, version(element))?.also { stations.putIfAbsent("way/$id", it) } != null
                        } else {
                            val parts = wayInfos(id, tags, segments, version(element), null)
                            if (parts.isNotEmpty()) ways.putIfAbsent(id, parts)
                            parts.isNotEmpty()
                        }
                    }
                    "node" -> station(element)?.also { stations.putIfAbsent("node/${it.osmId}", it) } != null
                    "relation" -> relations.add(element)
                    else -> false
                }
            }.getOrDefault(false)
            if (!ok) skipped++
        }
        // Relations after ways: a member already returned as a way keeps its own tags and fills only missing ones.
        for (relation in relations) {
            val ok = runCatching {
                val relTags = tags(relation)
                val relationId = relation.getLong("id")
                val multipolygon = relTags["type"] == "multipolygon"
                val members = relation.optJSONArray("members") ?: JSONArray()
                var used = 0
                for (m in 0 until members.length()) {
                    val member = members.optJSONObject(m) ?: continue
                    if (member.optString("type") != "way" || !member.has("ref")) continue
                    if (multipolygon && member.optString("role") == "inner") continue
                    val id = member.getLong("ref")
                    val existing = ways[id]
                    if (existing != null) {
                        ways[id] = existing.map { fill(it, relTags, relationId) }; used++; continue
                    }
                    val parts = wayInfos(id, relTags, segments(member.optJSONArray("geometry")), null, relationId, multipolygon)
                    if (parts.isEmpty()) continue
                    ways[id] = parts; used++
                }
                used > 0
            }.getOrDefault(false)
            if (!ok) skipped++
        }
        val remark = root.optString("remark", "").trim().takeIf { it.isNotEmpty() }
        val base = root.optJSONObject("osm3s")?.optString("timestamp_osm_base", "")?.takeIf { it.isNotEmpty() }
        OverpassParse.Parsed(ResortData(ways.values.flatten(), stations.values.toList(), base, remark, skipped))
    }

    fun parseSearch(text: String): OverpassParse<ResortSearch> = guard {
        val root = root(text) ?: return@guard OverpassParse.Failed(notJson(text))
        val elements = root.optJSONArray("elements") ?: return@guard OverpassParse.Failed("No elements array in the response")
        root.optString("remark", "").trim().takeIf { it.isNotEmpty() }?.let { return@guard OverpassParse.Failed("Server remark: $it") }
        val found = LinkedHashMap<String, ResortCandidate>()
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            runCatching {
                val type = e.optString("type").takeIf { it in setOf("node", "way", "relation") } ?: return@runCatching
                val name = text(tags(e)["name"]) ?: return@runCatching
                val bounds = e.optJSONObject("bounds")
                val box = bounds?.let { GeoBox(it.getDouble("minlat"), it.getDouble("minlon"), it.getDouble("maxlat"), it.getDouble("maxlon")) }
                val centre = box?.centre ?: e.optJSONObject("center")?.let { latLon(it) } ?: latLon(e) ?: return@runCatching
                val candidate = ResortCandidate(type, e.getLong("id"), name, centre, box)
                found.putIfAbsent(candidate.key, candidate)
            }
        }
        OverpassParse.Parsed(ResortSearch(found.values.toList(), elements.length()))
    }

    private fun <T> guard(block: () -> OverpassParse<T>): OverpassParse<T> = try { block() } catch (e: Exception) {
        OverpassParse.Failed("Unreadable response: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: StackOverflowError) {
        OverpassParse.Failed("Unreadable response: nested too deeply")
    }

    private fun root(text: String): JSONObject? {
        if (text.isBlank()) return null
        return JSONTokener(text).nextValue() as? JSONObject
    }

    private fun notJson(text: String): String = if (text.isBlank()) "Empty response" else
        "Response is not a JSON object (starts with \"${text.trim().take(40).replace('\n', ' ')}\")"

    /** One [ResortWayInfo] per geometry segment of two or more points; empty when the way is not a piste or lift. */
    private fun wayInfos(id: Long, tags: Map<String, String>, segments: List<List<LatLon>>, version: Int?, relationId: Long?,
                         multipolygon: Boolean = false): List<ResortWayInfo> {
        val aerialway = text(tags["aerialway"])
        val kind = when {
            aerialway != null && aerialway != "station" && aerialway != "pylon" -> WayKind.LIFT
            tags["piste:type"] == "downhill" -> WayKind.PISTE
            else -> return emptyList()
        }
        val piste = kind == WayKind.PISTE
        val raw = text(tags["piste:difficulty"])
        return segments.filter { it.size >= 2 }.mapIndexed { part, points ->
            val closed = points.size >= 4 && points.first() == points.last()
            val area = closed && (multipolygon || tags["area"] == "yes" || (piste && tags["area"] != "no"))
            val way = ResortWay(id, kind, if (piste) pisteName(tags) else text(tags["name"]), if (piste) pisteRef(tags) else text(tags["ref"]),
                if (piste) raw else null, if (piste) null else aerialway, points)
            ResortWayInfo(way, if (piste) PisteDifficulty.normalise(raw) else null, text(tags["operator"]), version, area, relationId, part)
        }
    }

    /** A piste relation fills only the tags a piste way lacks; it never changes a lift. */
    private fun fill(existing: ResortWayInfo, relTags: Map<String, String>, relationId: Long): ResortWayInfo {
        val w = existing.way
        if (w.kind != WayKind.PISTE) return existing
        val raw = w.difficulty ?: text(relTags["piste:difficulty"])
        return existing.copy(
            way = w.copy(name = w.name ?: pisteName(relTags), ref = w.ref ?: pisteRef(relTags), difficulty = raw),
            difficultyClass = existing.difficultyClass ?: PisteDifficulty.normalise(raw),
            operator = existing.operator ?: text(relTags["operator"]),
            relationId = existing.relationId ?: relationId)
    }

    private fun pisteName(tags: Map<String, String>) = text(tags["name"]) ?: text(tags["piste:name"])
    private fun pisteRef(tags: Map<String, String>) = text(tags["ref"]) ?: text(tags["piste:ref"])

    private fun station(e: JSONObject): ResortStation? {
        val tags = tags(e)
        if (tags["aerialway"] != "station") return null
        val point = latLon(e) ?: return null
        return ResortStation(e.getLong("id"), text(tags["name"]), point, text(tags["operator"]), version(e))
    }

    /** A station drawn as an outline: the mean of its distinct vertices (the closing point is not counted twice). */
    private fun stationFromWay(id: Long, tags: Map<String, String>, segments: List<List<LatLon>>, version: Int?): ResortStation? {
        val points = segments.flatten().let { p -> if (p.size >= 2 && p.first() == p.last()) p.dropLast(1) else p }
        if (points.isEmpty()) return null
        val centre = LatLon(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
        return ResortStation(id, text(tags["name"]), centre, text(tags["operator"]), version, "way")
    }

    private fun tags(e: JSONObject): Map<String, String> {
        val t = e.optJSONObject("tags") ?: return emptyMap()
        return t.keys().asSequence().mapNotNull { k -> (t.opt(k) as? String)?.let { k to it } }.toMap()
    }

    private fun version(e: JSONObject): Int? = if (e.has("version") && !e.isNull("version")) e.optInt("version", -1).takeIf { it >= 0 } else null

    /** The geometry split at every null or unusable point, so a gap is never bridged by a straight line. */
    private fun segments(geometry: JSONArray?): List<List<LatLon>> {
        geometry ?: return emptyList()
        val out = mutableListOf<List<LatLon>>()
        var current = mutableListOf<LatLon>()
        for (i in 0 until geometry.length()) {
            val p = geometry.optJSONObject(i)?.let(::latLon)
            if (p != null) { current.add(p); continue }
            if (current.isNotEmpty()) out.add(current)
            current = mutableListOf()
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun latLon(o: JSONObject): LatLon? {
        if (!o.has("lat") || !o.has("lon") || o.isNull("lat") || o.isNull("lon")) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
