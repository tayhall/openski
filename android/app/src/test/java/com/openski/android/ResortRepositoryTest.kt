package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResortRepositoryTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/overpass/$name")!!.bufferedReader().readText()

    /** In-memory cache that, like the SQLite store, replaces a resort as a whole or not at all. */
    private class MemoryCache : ResortCache {
        var resorts = mapOf<String, Pair<StoredResort, ResortData>>()
        var failNextSave = false
        var saves = 0
        override fun save(resort: StoredResort, data: ResortData) {
            val next = resorts + (resort.id to (resort to data))
            if (failNextSave) { failNextSave = false; throw IllegalStateException("disk full") }
            resorts = next
            saves++
        }
        override fun list() = resorts.values.map { it.first }.sortedBy { it.name }
        override fun resort(id: String) = resorts[id]?.first
        override fun ways(id: String) = resorts[id]?.second?.ways.orEmpty()
        override fun stations(id: String) = resorts[id]?.second?.stations.orEmpty()
        override fun delete(id: String) = (id in resorts).also { resorts = resorts - id }
    }

    private class ScriptedFetcher(vararg answers: FetchResult) : ResortFetcher {
        val queue = ArrayDeque(answers.toList())
        val queries = mutableListOf<String>()
        override fun fetch(query: String): FetchResult { queries += query; return queue.removeFirst() }
        override val source: String get() = "test fetcher"
    }

    private val request = ResortRequest("example", "Example Valley", LatLon(46.015, 7.015), 3_000)
    private var now = 1_000_000L

    private fun repo(fetcher: ResortFetcher, cache: ResortCache) = ResortRepository(fetcher, cache) { now }

    @Test fun successSavesEverythingWithAttribution() {
        val cache = MemoryCache()
        val fetcher = ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json")))
        val result = repo(fetcher, cache).download(request) as DownloadResult.Saved
        assertEquals(listOf(OverpassQuery.area(request.centre, 3_000)), fetcher.queries)
        assertEquals(6, result.resort.wayCount)
        assertEquals(now, result.resort.fetchedAtMs)
        assertEquals(3_000, result.resort.radiusM)
        assertTrue(result.resort.sourceNote.startsWith("test fetcher; OSM data as of 2026-10-19T08:15:42Z; 2 unusable elements skipped"))
        assertTrue(result.resort.sourceNote.endsWith(ResortRepository.ATTRIBUTION))
        assertEquals("(c) OpenStreetMap contributors (ODbL)", ResortRepository.ATTRIBUTION)
        assertEquals(6, cache.ways("example").size)
        assertEquals(3, cache.stations("example").size)
        assertEquals(listOf(result.resort), repo(fetcher, cache).list())
    }

    @Test fun radiusStoredIsTheOneQueried() {
        val cache = MemoryCache()
        val big = request.copy(radiusM = 100_000)
        val r = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache).download(big) as DownloadResult.Saved
        assertEquals(OverpassQuery.MAX_RADIUS_M, r.resort.radiusM)
        assertTrue("radius beyond the clamp is flagged", r.truncated)
        assertTrue(r.resort.sourceNote.contains("radius cut to 25 km"))
        val exact = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache)
            .download(request.copy(radiusM = OverpassQuery.MAX_RADIUS_M)) as DownloadResult.Saved
        assertFalse(exact.truncated)
    }

    @Test fun invalidCentreIsRefusedBeforeSending() {
        val fetcher = ScriptedFetcher()
        val repo = repo(fetcher, MemoryCache())
        for (bad in listOf(request.copy(centre = LatLon(86.0, 7.0)), request.copy(centre = LatLon(Double.NaN, 7.0)),
                request.copy(centre = LatLon(46.0, 181.0)), request.copy(radiusM = 0))) {
            assertTrue("$bad", repo.download(bad) is DownloadResult.InvalidRequest)
        }
        assertTrue(repo.searchNear("Example", LatLon(-89.0, 0.0)) is SearchResult.InvalidRequest)
        assertTrue(fetcher.queries.isEmpty())
    }

    @Test fun fetcherExceptionsBecomeTypedErrors() {
        val throwing = ResortFetcher { throw IllegalStateException("socket factory broke") }
        val r = repo(throwing, MemoryCache()).download(request) as DownloadResult.FetchFailed
        assertEquals(FetchError.Kind.FAILED, r.error.kind)
        assertEquals("socket factory broke", r.error.message)
        val s = repo(throwing, MemoryCache()).search("Example") as SearchResult.FetchFailed
        assertEquals(FetchError.Kind.FAILED, s.error.kind)
    }

    @Test fun guardBlocksDownloadsWithoutSending() {
        val cache = MemoryCache()
        var recording = true
        val fetcher = ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json")))
        val repo = ResortRepository(fetcher, cache, downloadBlocker = { if (recording) "a recording is active" else null }, clock = { now })
        val blocked = repo.download(request) as DownloadResult.Blocked
        assertEquals("a recording is active", blocked.reason)
        assertTrue(fetcher.queries.isEmpty())
        assertTrue(cache.list().isEmpty())
        recording = false
        assertTrue(repo.download(request) is DownloadResult.Saved)
    }

    @Test fun positionsAreCoarsenedBeforeTheyLeave() {
        assertEquals(LatLon(46.02, 7.01), ResortRepository.coarsen(LatLon(46.0234567, 7.0149999)))
        assertEquals(LatLon(-0.01, -120.0), ResortRepository.coarsen(LatLon(-0.0071, -119.9951)))
        val fix = LatLon(46.0234567, 7.0149999)
        val fetcher = ScriptedFetcher(FetchResult.Body(fixture("search.json")))
        repo(fetcher, MemoryCache()).searchNear("Example", fix, 10_000)
        assertEquals(OverpassQuery.search("Example", GeoBox.around(LatLon(46.02, 7.01), 10_000.0)), fetcher.queries.single())
        assertFalse(fetcher.queries.single().contains("46.023") || fetcher.queries.single().contains("7.0149"))
        val near = ResortRequest.nearPosition("here", "Here", fix)
        assertEquals(LatLon(46.02, 7.01), near.centre)
        val download = ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json")))
        repo(download, MemoryCache()).download(near)
        assertEquals(OverpassQuery.area(LatLon(46.02, 7.01), ResortRequest.DEFAULT_RADIUS_M), download.queries.single())
    }

    @Test fun searchFlagsACappedAnswer() {
        val elements = (1..OverpassQuery.SEARCH_LIMIT).joinToString(",") {
            """{"type":"node","id":$it,"lat":46.0,"lon":7.0,"tags":{"site":"piste","name":"Resort $it"}}"""
        }
        val full = repo(ScriptedFetcher(FetchResult.Body("""{"elements":[$elements]}""")), MemoryCache()).search("Resort") as SearchResult.Found
        assertTrue(full.maybeMore)
        val few = repo(ScriptedFetcher(FetchResult.Body(fixture("search.json"))), MemoryCache()).search("Example") as SearchResult.Found
        assertFalse(few.maybeMore)
    }

    private fun cachedRepo(vararg next: FetchResult): Triple<ResortRepository, MemoryCache, StoredResort> {
        val cache = MemoryCache()
        val first = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache).download(request) as DownloadResult.Saved
        now += 1_000
        return Triple(repo(ScriptedFetcher(*next), cache), cache, first.resort)
    }

    @Test fun httpErrorKeepsTheCache() {
        val (repo, cache, good) = cachedRepo(FetchResult.Failed(FetchError(FetchError.Kind.HTTP, "HTTP 429", 429)))
        val r = repo.download(request) as DownloadResult.FetchFailed
        assertEquals(429, r.error.httpCode)
        assertEquals(good, r.kept)
        assertEquals(good, cache.resort("example"))
        assertEquals(6, cache.ways("example").size)
    }

    @Test fun malformedJsonKeepsTheCache() {
        val (repo, cache, good) = cachedRepo(FetchResult.Body(fixture("resort_normal.json").take(900)))
        val r = repo.download(request) as DownloadResult.ParseFailed
        assertTrue(r.message.startsWith("Unreadable response"))
        assertEquals(good, r.kept)
        assertEquals(1, cache.saves)
    }

    @Test fun emptyResultDoesNotReplaceAGoodCache() {
        val (repo, cache, good) = cachedRepo(FetchResult.Body(fixture("resort_empty.json")))
        val r = repo.download(request) as DownloadResult.NoData
        assertEquals(good, r.kept)
        assertEquals(good, cache.resort("example"))
        assertEquals(6, cache.ways("example").size)
    }

    @Test fun emptyResultWithNoCacheSavesNothing() {
        val cache = MemoryCache()
        val r = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_empty.json"))), cache).download(request) as DownloadResult.NoData
        assertNull(r.kept)
        assertTrue(cache.list().isEmpty())
    }

    @Test fun partialResultIsNeverSaved() {
        val (repo, cache, good) = cachedRepo(FetchResult.Body(fixture("resort_partial.json")))
        val r = repo.download(request) as DownloadResult.Partial
        assertTrue(r.remark.contains("timed out"))
        assertEquals(good, cache.resort("example"))
        assertEquals(6, cache.ways("example").size)
    }

    @Test fun failedSaveLeavesTheOldCopy() {
        val (repo, cache, good) = cachedRepo(FetchResult.Body(fixture("resort_area.json")))
        cache.failNextSave = true
        val r = repo.download(request) as DownloadResult.StoreFailed
        assertEquals("disk full", r.message)
        assertEquals(good, r.kept)
        assertEquals(good, cache.resort("example"))
        assertEquals(6, cache.ways("example").size)
    }

    @Test fun refreshReplacesTheWholeMap() {
        val (repo, cache, _) = cachedRepo(FetchResult.Body(fixture("resort_area.json")))
        val r = repo.download(request) as DownloadResult.Saved
        assertEquals(setOf(200001L, 200002L, 200010L, 200012L), cache.ways("example").map { it.way.osmId }.toSet())
        assertTrue("old stations are gone with the old copy", cache.stations("example").isEmpty())
        assertEquals(now, r.resort.fetchedAtMs)
        assertEquals(1, cache.list().size)
    }

    @Test fun staleness() {
        val cache = MemoryCache()
        val repo = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache)
        assertTrue("missing counts as stale", repo.isStale("example"))
        val saved = (repo.download(request) as DownloadResult.Saved).resort
        val day = 24L * 60 * 60 * 1000
        assertEquals(150 * day, ResortRepository.STALE_AFTER_MS)
        assertFalse(repo.isStale("example", saved.fetchedAtMs + 150 * day))
        assertTrue(repo.isStale("example", saved.fetchedAtMs + 150 * day + 1))
        assertFalse("clock moved back", saved.isStale(saved.fetchedAtMs - 10 * day))
        assertEquals(42L, cache.age("example", saved.fetchedAtMs + 42))
        assertNull(cache.age("nowhere", 0))
    }

    @Test fun deleteRemovesOnlyThatResort() {
        val cache = MemoryCache()
        repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache).download(request)
        repo(ScriptedFetcher(FetchResult.Body(fixture("resort_area.json"))), cache).download(request.copy(id = "other", name = "Other"))
        val repo = repo(ScriptedFetcher(), cache)
        assertTrue(repo.delete("example"))
        assertFalse(repo.delete("example"))
        assertEquals(listOf("other"), repo.list().map { it.id })
        assertTrue(repo.ways("example").isEmpty())
        assertEquals(4, repo.ways("other").size)
        assertTrue(repo.stations("example").isEmpty())
    }

    @Test fun searchUsesOneQueryAndParses() {
        val fetcher = ScriptedFetcher(FetchResult.Body(fixture("search.json")))
        val r = repo(fetcher, MemoryCache()).search("Example Valley") as SearchResult.Found
        assertEquals(1, fetcher.queries.size)
        assertEquals(OverpassQuery.search("Example Valley"), fetcher.queries.single())
        assertEquals(3, r.candidates.size)
        val req = ResortRequest.forCandidate(r.candidates[1])
        assertEquals("osm-way-400002", req.id)
        assertEquals("Example Valley Ski Area", req.name)
        // Half the box: 0.03 degrees of longitude at 46.02 N is about 2318 m, plus the 1 km margin.
        assertEquals(3_318.0, req.radiusM.toDouble(), 5.0)
        assertEquals(ResortRequest.DEFAULT_RADIUS_M, ResortRequest.forCandidate(r.candidates[0]).radiusM)
    }

    @Test fun searchFailuresAreTyped() {
        val error = FetchError(FetchError.Kind.TIMEOUT, "Timed out")
        assertEquals(SearchResult.FetchFailed(error), repo(ScriptedFetcher(FetchResult.Failed(error)), MemoryCache()).search("Example"))
        assertTrue(repo(ScriptedFetcher(FetchResult.Body("<html/>")), MemoryCache()).search("Example") is SearchResult.ParseFailed)
        val none = ScriptedFetcher()
        assertTrue(repo(none, MemoryCache()).search(" ") is SearchResult.InvalidRequest)
        assertTrue("a bad name sends nothing", none.queries.isEmpty())
    }

    @Test fun savedResultCarriesTheParsedData() {
        val cache = MemoryCache()
        val r = repo(ScriptedFetcher(FetchResult.Body(fixture("resort_normal.json"))), cache).download(request) as DownloadResult.Saved
        assertSame(r.data, cache.resorts.getValue("example").second)
    }
}
