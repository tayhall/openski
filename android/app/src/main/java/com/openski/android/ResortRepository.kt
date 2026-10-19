package com.openski.android

/** What to download: a resort id the caller chooses (stable across refreshes), a display name and the area. */
data class ResortRequest(val id: String, val name: String, val centre: LatLon, val radiusM: Int) {
    init { require(id.isNotBlank()) { "Resort id must not be blank" } }

    /** The radius the query actually uses. */
    val effectiveRadiusM: Int get() = radiusM.coerceIn(OverpassQuery.MIN_RADIUS_M, OverpassQuery.MAX_RADIUS_M)

    companion object {
        /** A request for a search result: its centre, and a radius that covers its box (or [defaultRadiusM] for a point). */
        fun forCandidate(c: ResortCandidate, defaultRadiusM: Int = DEFAULT_RADIUS_M): ResortRequest {
            val radius = c.box?.let { b ->
                val halfLatM = (b.north - b.south) / 2 * Math.PI / 180 * GeoBox.EARTH_RADIUS_M
                val halfLonM = (b.east - b.west) / 2 * Math.PI / 180 * GeoBox.EARTH_RADIUS_M * Math.cos(c.centre.lat * Math.PI / 180)
                maxOf(halfLatM, halfLonM).toInt() + EDGE_MARGIN_M
            } ?: defaultRadiusM
            return ResortRequest("osm-${c.osmType}-${c.osmId}", c.name, c.centre, radius)
        }

        /** Radius used around a point when nothing better is known, in metres. */
        const val DEFAULT_RADIUS_M = 5_000
        /** Added around a resort's mapped box so lifts and pistes that leave it are still fetched, in metres. */
        const val EDGE_MARGIN_M = 1_000
    }
}

/** A cached resort map as stored. */
data class StoredResort(
    val id: String,
    val name: String,
    val centre: LatLon,
    val radiusM: Int,
    val fetchedAtMs: Long,
    val wayCount: Int,
    val sourceNote: String,
) {
    fun ageMs(nowMs: Long): Long = nowMs - fetchedAtMs
    /** Older than about one season ([ResortRepository.STALE_AFTER_MS]); a fetch time in the future (clock change) is not stale. */
    fun isStale(nowMs: Long): Boolean = ageMs(nowMs) > ResortRepository.STALE_AFTER_MS
}

/** Where resort maps are kept. [save] replaces a resort and all its ways and stations in one step, or changes nothing. */
interface ResortCache {
    fun save(resort: StoredResort, data: ResortData)
    fun list(): List<StoredResort>
    fun resort(id: String): StoredResort?
    fun ways(id: String): List<ResortWayInfo>
    fun stations(id: String): List<ResortStation>
    fun delete(id: String): Boolean
    /** Milliseconds since [id] was fetched, or null when it is not cached. */
    fun age(id: String, nowMs: Long): Long? = resort(id)?.ageMs(nowMs)
}

/** Why a fetch failed. [httpCode] is set for [Kind.HTTP]. */
data class FetchError(val kind: Kind, val message: String, val httpCode: Int? = null) {
    enum class Kind { HTTP, NETWORK, TIMEOUT, TOO_LARGE }
}

sealed class FetchResult {
    data class Body(val text: String) : FetchResult()
    data class Failed(val error: FetchError) : FetchResult()
}

/** Sends one Overpass query and returns the response text. Blocking: call it off the main thread. */
fun interface ResortFetcher {
    fun fetch(query: String): FetchResult
    /** A short description of where the data came from, stored in the resort's source note. */
    val source: String get() = "Overpass API"
}

sealed class DownloadResult {
    /** [kept] is the cached copy left untouched by a failure, if there was one. */
    abstract val kept: StoredResort?
    data class Saved(val resort: StoredResort, val data: ResortData) : DownloadResult() { override val kept: StoredResort? get() = resort }
    data class FetchFailed(val error: FetchError, override val kept: StoredResort?) : DownloadResult()
    data class ParseFailed(val message: String, override val kept: StoredResort?) : DownloadResult()
    /** The server cut the answer short (its remark is kept); partial data is never saved. */
    data class Partial(val remark: String, override val kept: StoredResort?) : DownloadResult()
    /** No pistes, lifts or stations in the area; an empty answer never replaces a cached map. */
    data class NoData(override val kept: StoredResort?) : DownloadResult()
    /** Writing the new copy failed; the save is one transaction, so the cached copy is unchanged. */
    data class StoreFailed(val message: String, override val kept: StoredResort?) : DownloadResult()
}

sealed class SearchResult {
    data class Found(val candidates: List<ResortCandidate>) : SearchResult()
    data class FetchFailed(val error: FetchError) : SearchResult()
    data class ParseFailed(val message: String) : SearchResult()
}

/**
 * Resort maps: fetch once (on request, over Wi-Fi in practice), parse, store, and read back offline. Experimental.
 * Only a complete, non-empty, parsed answer replaces a cached map; every failure leaves the cache as it was.
 */
class ResortRepository(
    private val fetcher: ResortFetcher,
    private val cache: ResortCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun download(request: ResortRequest): DownloadResult {
        val kept = cache.resort(request.id)
        val text = when (val r = fetcher.fetch(OverpassQuery.area(request.centre, request.radiusM))) {
            is FetchResult.Failed -> return DownloadResult.FetchFailed(r.error, kept)
            is FetchResult.Body -> r.text
        }
        val data = when (val p = OverpassParser.parseResort(text)) {
            is OverpassParse.Failed -> return DownloadResult.ParseFailed(p.message, kept)
            is OverpassParse.Parsed -> p.value
        }
        if (data.partial) return DownloadResult.Partial(data.remark!!, kept)
        if (data.isEmpty) return DownloadResult.NoData(kept)
        val resort = StoredResort(request.id, request.name, request.centre, request.effectiveRadiusM, clock(),
            data.ways.size, sourceNote(data))
        try {
            cache.save(resort, data)
        } catch (e: RuntimeException) {
            return DownloadResult.StoreFailed(e.message ?: e.javaClass.simpleName, cache.resort(request.id))
        }
        return DownloadResult.Saved(resort, data)
    }

    fun search(name: String, within: GeoBox? = null): SearchResult {
        val query = try { OverpassQuery.search(name, within) } catch (e: IllegalArgumentException) {
            return SearchResult.ParseFailed(e.message ?: "Bad name")
        }
        val text = when (val r = fetcher.fetch(query)) {
            is FetchResult.Failed -> return SearchResult.FetchFailed(r.error)
            is FetchResult.Body -> r.text
        }
        return when (val p = OverpassParser.parseSearch(text)) {
            is OverpassParse.Failed -> SearchResult.ParseFailed(p.message)
            is OverpassParse.Parsed -> SearchResult.Found(p.value)
        }
    }

    fun list(): List<StoredResort> = cache.list()
    fun ways(id: String): List<ResortWayInfo> = cache.ways(id)
    fun stations(id: String): List<ResortStation> = cache.stations(id)
    fun delete(id: String): Boolean = cache.delete(id)

    /** True when [id] is not cached or its copy is older than [STALE_AFTER_MS]. */
    fun isStale(id: String, nowMs: Long = clock()): Boolean = cache.resort(id)?.isStale(nowMs) ?: true

    private fun sourceNote(data: ResortData): String = buildString {
        append(fetcher.source)
        data.osmBase?.let { append("; OSM data as of ").append(it) }
        if (data.skipped > 0) append("; ").append(data.skipped).append(" unusable elements skipped")
        append("; ").append(ATTRIBUTION)
    }

    companion object {
        /** About one season: a cached map older than this is offered for refresh. */
        const val STALE_AFTER_MS = 150L * 24 * 60 * 60 * 1000
        /** Must be shown wherever names or geometry from the map appear (screen and exports). */
        const val ATTRIBUTION = "(c) OpenStreetMap contributors (ODbL)"
    }
}
