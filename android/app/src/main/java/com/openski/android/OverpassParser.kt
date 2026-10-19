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
 * A parsed way with the details [ResortWay] does not carry. [area] marks a closed outline (an area piste) used as a
 * line; [relationId] is set when the way came in as a member of a piste relation whose tags it took.
 */
data class ResortWayInfo(
    val way: ResortWay,
    val difficultyRaw: String?,
    val operator: String?,
    val version: Int?,
    val area: Boolean,
    val relationId: Long? = null,
)

/** An `aerialway=station` node. */
data class ResortStation(val osmId: Long, val name: String?, val point: LatLon, val operator: String?, val version: Int?)

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

sealed class OverpassParse<out T> {
    data class Parsed<T>(val value: T) : OverpassParse<T>()
    data class Failed(val message: String) : OverpassParse<Nothing>()
}

/**
 * Overpass JSON to resort data. Tolerates missing tags, unnamed ways, null geometry points and unknown element
 * types; malformed or truncated JSON returns [OverpassParse.Failed] with a message. Never throws.
 */
object OverpassParser {
    fun parseResort(text: String): OverpassParse<ResortData> = guard {
        val root = root(text) ?: return@guard OverpassParse.Failed(notJson(text))
        val elements = root.optJSONArray("elements") ?: return@guard OverpassParse.Failed("No elements array in the response")
        val ways = LinkedHashMap<Long, ResortWayInfo>()
        val stations = LinkedHashMap<Long, ResortStation>()
        val relations = mutableListOf<JSONObject>()
        var skipped = 0
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i)
            if (element == null) { skipped++; continue }
            val ok = runCatching {
                when (element.optString("type")) {
                    "way" -> wayInfo(element.getLong("id"), tags(element), points(element.optJSONArray("geometry")),
                        version(element), null)?.also { ways.putIfAbsent(it.way.osmId, it) } != null
                    "node" -> station(element)?.also { stations.putIfAbsent(it.osmId, it) } != null
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
                val multipolygon = relTags["type"] == "multipolygon"
                val members = relation.optJSONArray("members") ?: JSONArray()
                var used = 0
                for (m in 0 until members.length()) {
                    val member = members.optJSONObject(m) ?: continue
                    if (member.optString("type") != "way" || !member.has("ref")) continue
                    val id = member.getLong("ref")
                    val existing = ways[id]
                    if (existing != null) {
                        ways[id] = fill(existing, relTags, relation.getLong("id")); used++; continue
                    }
                    val info = wayInfo(id, relTags, points(member.optJSONArray("geometry")), null, relation.getLong("id"),
                        forceArea = multipolygon) ?: continue
                    ways[id] = info; used++
                }
                used > 0
            }.getOrDefault(false)
            if (!ok) skipped++
        }
        val remark = root.optString("remark", "").trim().takeIf { it.isNotEmpty() }
        val base = root.optJSONObject("osm3s")?.optString("timestamp_osm_base", "")?.takeIf { it.isNotEmpty() }
        OverpassParse.Parsed(ResortData(ways.values.toList(), stations.values.toList(), base, remark, skipped))
    }

    fun parseSearch(text: String): OverpassParse<List<ResortCandidate>> = guard {
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
        OverpassParse.Parsed(found.values.toList())
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

    private fun wayInfo(id: Long, tags: Map<String, String>, points: List<LatLon>, version: Int?, relationId: Long?,
                        forceArea: Boolean = false): ResortWayInfo? {
        if (points.size < 2) return null
        val aerialway = text(tags["aerialway"])
        val kind = when {
            aerialway != null && aerialway != "station" && aerialway != "pylon" -> WayKind.LIFT
            tags["piste:type"] == "downhill" -> WayKind.PISTE
            else -> return null
        }
        val closed = points.size >= 4 && points.first() == points.last()
        val area = forceArea || tags["area"] == "yes" || (kind == WayKind.PISTE && closed && tags["area"] != "no")
        val raw = text(tags["piste:difficulty"])
        val piste = kind == WayKind.PISTE
        val way = ResortWay(id, kind, if (piste) pisteName(tags) else text(tags["name"]), if (piste) pisteRef(tags) else text(tags["ref"]),
            if (piste) PisteDifficulty.normalise(raw) else null, if (piste) null else aerialway, points)
        return ResortWayInfo(way, if (piste) raw else null, text(tags["operator"]), version, area, relationId)
    }

    /** A piste relation fills only the tags a piste way lacks; it never changes a lift. */
    private fun fill(existing: ResortWayInfo, relTags: Map<String, String>, relationId: Long): ResortWayInfo {
        val w = existing.way
        if (w.kind != WayKind.PISTE) return existing
        val raw = existing.difficultyRaw ?: text(relTags["piste:difficulty"])
        return existing.copy(
            way = w.copy(name = w.name ?: pisteName(relTags), ref = w.ref ?: pisteRef(relTags),
                difficulty = w.difficulty ?: PisteDifficulty.normalise(raw)),
            difficultyRaw = raw, operator = existing.operator ?: text(relTags["operator"]),
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

    private fun tags(e: JSONObject): Map<String, String> {
        val t = e.optJSONObject("tags") ?: return emptyMap()
        return t.keys().asSequence().mapNotNull { k -> (t.opt(k) as? String)?.let { k to it } }.toMap()
    }

    private fun version(e: JSONObject): Int? = if (e.has("version") && !e.isNull("version")) e.optInt("version", -1).takeIf { it >= 0 } else null

    private fun points(geometry: JSONArray?): List<LatLon> {
        geometry ?: return emptyList()
        return (0 until geometry.length()).mapNotNull { i -> geometry.optJSONObject(i)?.let(::latLon) }
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
