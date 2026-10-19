package com.openski.android

import kotlin.math.roundToLong

/** What to download: a resort id the caller chooses (stable across refreshes), a display name and the area. */
data class ResortRequest(val id: String, val name: String, val centre: LatLon, val radiusM: Int) {
    init { require(id.isNotBlank()) { "Resort id must not be blank" } }

    /** The radius the query actually uses. */
    val effectiveRadiusM: Int get() = radiusM.coerceIn(OverpassQuery.MIN_RADIUS_M, OverpassQuery.MAX_RADIUS_M)
    /** True when the asked-for radius was larger than [OverpassQuery.MAX_RADIUS_M], so the map covers less than asked. */
    val truncated: Boolean get() = radiusM > OverpassQuery.MAX_RADIUS_M

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

        /**
         * A request around the phone's own position. The centre is coarsened to [ResortRepository.LOCATION_GRID_DEG]
         * before it is used, so the exact fix never leaves the device.
         */
        fun nearPosition(id: String, name: String, fix: LatLon, radiusM: Int = DEFAULT_RADIUS_M): ResortRequest =
            ResortRequest(id, name, ResortRepository.coarsen(fix), radiusM)

        /** Radius used around a point when nothing better is known, in metres. */
        const val DEFAULT_RADIUS_M = 5_000
        /** Added around a resort's mapped box so lifts and pistes that leave it are still fetched, in metres. */
        const val EDGE_MARGIN_M = 1_000
    }
}

/** A cached resort map as stored. [truncated] means the radius asked for was cut to the 25 km maximum. */
data class StoredResort(
    val id: String,
    val name: String,
    val centre: LatLon,
    val radiusM: Int,
    val fetchedAtMs: Long,
    val wayCount: Int,
    val sourceNote: String,
    val truncated: Boolean = false,
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
    enum class Kind {
        HTTP, NETWORK, TIMEOUT, TOO_LARGE,
        /** Called on the main thread; nothing was sent. */
        MAIN_THREAD,
        /** The thread was interrupted (for example while waiting to retry); the interrupt flag is restored. */
        INTERRUPTED,
        /** Anything unexpected while building or sending the request. */
        FAILED,
    }
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
    /** Saved; [resort].truncated says the radius was cut to the maximum. */
    data class Saved(val resort: StoredResort, val data: ResortData) : DownloadResult() {
        override val kept: StoredResort? get() = resort
        val truncated: Boolean get() = resort.truncated
    }
    /** The request was refused before anything was sent (bad centre or radius). */
    data class InvalidRequest(val message: String, override val kept: StoredResort?) : DownloadResult()
    /** The download guard refused (for example while a recording is active); nothing was sent. */
    data class Blocked(val reason: String, override val kept: StoredResort?) : DownloadResult()
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
    /** [maybeMore] is true when the server returned as many elements as the query's limit, so others may exist. */
    data class Found(val candidates: List<ResortCandidate>, val maybeMore: Boolean = false) : SearchResult()
    data class InvalidRequest(val message: String) : SearchResult()
    data class FetchFailed(val error: FetchError) : SearchResult()
    data class ParseFailed(val message: String) : SearchResult()
}

/**
 * Resort maps: fetch once (on request, over Wi-Fi in practice), parse, store, and read back offline. Experimental.
 * Only a complete, non-empty, parsed answer replaces a cached map; every failure leaves the cache as it was.
 *
 * What leaves the device: the query text (a bounding box, or a typed name and an optional box) and the fetcher's
 * User-Agent. A box built from the phone's position uses a centre coarsened by [coarsen].
 *
 * [downloadBlocker] is asked before every download and returns a reason to refuse (for example "a recording is
 * active"), or null to go ahead. The save holds the session store's lock and SQLite's write lock for its one
 * transaction (after fetching and parsing, which hold no lock), so the UI should refuse downloads while recording.
 */
class ResortRepository(
    private val fetcher: ResortFetcher,
    private val cache: ResortCache,
    private val downloadBlocker: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun download(request: ResortRequest): DownloadResult {
        val kept = cache.resort(request.id)
        validate(request.centre, request.radiusM)?.let { return DownloadResult.InvalidRequest(it, kept) }
        downloadBlocker()?.let { return DownloadResult.Blocked(it, kept) }
        val fetched = try {
            fetcher.fetch(OverpassQuery.area(request.centre, request.radiusM))
        } catch (e: RuntimeException) {
            FetchResult.Failed(FetchError(FetchError.Kind.FAILED, e.message ?: e.javaClass.simpleName))
        }
        val text = when (fetched) {
            is FetchResult.Failed -> return DownloadResult.FetchFailed(fetched.error, kept)
            is FetchResult.Body -> fetched.text
        }
        val data = when (val p = OverpassParser.parseResort(text)) {
            is OverpassParse.Failed -> return DownloadResult.ParseFailed(p.message, kept)
            is OverpassParse.Parsed -> p.value
        }
        if (data.partial) return DownloadResult.Partial(data.remark!!, kept)
        if (data.isEmpty) return DownloadResult.NoData(kept)
        val resort = StoredResort(request.id, request.name, request.centre, request.effectiveRadiusM, clock(),
            data.ways.size, sourceNote(data, request.truncated), request.truncated)
        try {
            cache.save(resort, data)
        } catch (e: RuntimeException) {
            return DownloadResult.StoreFailed(e.message ?: e.javaClass.simpleName, cache.resort(request.id))
        }
        return DownloadResult.Saved(resort, data)
    }

    /** Resorts by name, optionally only inside [within]. */
    fun search(name: String, within: GeoBox? = null): SearchResult {
        val query = try { OverpassQuery.search(name, within) } catch (e: IllegalArgumentException) {
            return SearchResult.InvalidRequest(e.message ?: "Bad name")
        }
        val fetched = try { fetcher.fetch(query) } catch (e: RuntimeException) {
            FetchResult.Failed(FetchError(FetchError.Kind.FAILED, e.message ?: e.javaClass.simpleName))
        }
        val text = when (fetched) {
            is FetchResult.Failed -> return SearchResult.FetchFailed(fetched.error)
            is FetchResult.Body -> fetched.text
        }
        return when (val p = OverpassParser.parseSearch(text)) {
            is OverpassParse.Failed -> SearchResult.ParseFailed(p.message)
            is OverpassParse.Parsed -> SearchResult.Found(p.value.candidates, p.value.elementCount >= OverpassQuery.SEARCH_LIMIT)
        }
    }

    /** Resorts by name within [radiusM] of the phone's [position], which is coarsened before it is used. */
    fun searchNear(name: String, position: LatLon, radiusM: Int = SEARCH_NEAR_RADIUS_M): SearchResult {
        validate(position, radiusM)?.let { return SearchResult.InvalidRequest(it) }
        return search(name, GeoBox.around(coarsen(position), radiusM.toDouble()))
    }

    fun list(): List<StoredResort> = cache.list()
    fun ways(id: String): List<ResortWayInfo> = cache.ways(id)
    fun stations(id: String): List<ResortStation> = cache.stations(id)
    fun delete(id: String): Boolean = cache.delete(id)

    /** True when [id] is not cached or its copy is older than [STALE_AFTER_MS]. */
    fun isStale(id: String, nowMs: Long = clock()): Boolean = cache.resort(id)?.isStale(nowMs) ?: true

    private fun validate(centre: LatLon, radiusM: Int): String? = when {
        !centre.lat.isFinite() || !centre.lon.isFinite() -> "Position is not a number"
        centre.lat !in -GeoBox.MAX_CENTRE_LAT..GeoBox.MAX_CENTRE_LAT -> "Latitude ${centre.lat} is outside +-${GeoBox.MAX_CENTRE_LAT} degrees"
        centre.lon !in -180.0..180.0 -> "Longitude ${centre.lon} is outside +-180 degrees"
        radiusM <= 0 -> "Radius must be positive"
        else -> null
    }

    private fun sourceNote(data: ResortData, truncated: Boolean): String = buildString {
        append(fetcher.source)
        data.osmBase?.let { append("; OSM data as of ").append(it) }
        if (data.skipped > 0) append("; ").append(data.skipped).append(" unusable elements skipped")
        if (truncated) append("; radius cut to ").append(OverpassQuery.MAX_RADIUS_M / 1000).append(" km")
        append("; ").append(ATTRIBUTION)
    }

    companion object {
        /** About one season: a cached map older than this is offered for refresh. */
        const val STALE_AFTER_MS = 150L * 24 * 60 * 60 * 1000
        /** Must be shown wherever names or geometry from the map appear (screen and exports). */
        const val ATTRIBUTION = "(c) OpenStreetMap contributors (ODbL)"
        /** Positions sent to the map server are rounded to this grid: 0.01 degrees is about 1.1 km north-south. */
        const val LOCATION_GRID_DEG = 0.01
        /** Radius of a name search around the phone's position, in metres. */
        const val SEARCH_NEAR_RADIUS_M = 50_000

        /** [p] rounded to [LOCATION_GRID_DEG], so the phone's exact position is never sent. */
        fun coarsen(p: LatLon): LatLon = LatLon(round(p.lat), round(p.lon))

        private fun round(v: Double): Double = (v / LOCATION_GRID_DEG).roundToLong() / (1 / LOCATION_GRID_DEG)
    }
}
