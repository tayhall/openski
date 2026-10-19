package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiDayTextTest {
    @Test fun numbersReadPlainly() {
        assertEquals("4:05", SkiDayText.duration(245.0))
        assertEquals("1:02:03", SkiDayText.duration(3723.0))
        assertEquals("–", SkiDayText.duration(Double.NaN))
        assertEquals("950 m", SkiDayText.distance(950.0))
        assertEquals("2.1 km", SkiDayText.distance(2100.0))
        assertEquals("–", SkiDayText.distance(null))
        assertEquals("54 km/h", SkiDayText.speed(15.0))
        assertEquals("–", SkiDayText.vertical(Double.POSITIVE_INFINITY))
        assertEquals("just now", SkiDayText.age(-5_000))
        assertEquals("5 min ago", SkiDayText.age(300_000))
        assertEquals("3 h ago", SkiDayText.age(3 * 3_600_000L))
        assertEquals("1 day ago", SkiDayText.age(86_400_000L))
        assertEquals("200 days ago", SkiDayText.age(200 * 86_400_000L))
    }

    private val kept = StoredResort("r", "Peak", LatLon(46.0, 7.0), 5000, 1_768_000_000_000L, 10, "")

    @Test fun downloadErrorsSayWhatHappenedAndWhatWasKept() {
        val offline = SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.NETWORK, "unreachable"), kept))
        assertTrue(offline, offline.contains("offline") && offline.contains("cached copy"))
        assertTrue(SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.HTTP, "x", 429), null)).contains("rate limiting"))
        assertTrue(SkiDayText.download(DownloadResult.NoData(null)).contains("No pistes or lifts"))
        assertTrue(SkiDayText.download(DownloadResult.Partial("runtime error: timeout", null)).contains("truncated"))
        assertTrue(SkiDayText.download(DownloadResult.ParseFailed("unexpected end", null)).contains("truncated"))
        assertTrue(SkiDayText.download(DownloadResult.Blocked("a boot recording is in progress", null)).contains("recording"))
        assertTrue(SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.TIMEOUT, "t"), null)).contains("did not answer"))
        assertTrue(SkiDayText.download(DownloadResult.Saved(kept, ResortData(emptyList(), emptyList()))).startsWith("Saved 10"))
        assertTrue(SkiDayText.download(DownloadResult.StoreFailed("disk full", kept)).contains("disk full"))
        assertTrue(SkiDayText.download(DownloadResult.InvalidRequest("Radius must be positive", null)).contains("Radius"))
    }

    @Test fun searchResultsAreDescribed() {
        assertTrue(SkiDayText.search(SearchResult.Found(emptyList())).startsWith("No ski area"))
        val c = ResortCandidate("way", 1, "Peak", LatLon(46.0, 7.0), null)
        assertEquals("1 found.", SkiDayText.search(SearchResult.Found(listOf(c))))
        assertTrue(SkiDayText.search(SearchResult.Found(listOf(c), maybeMore = true)).contains("may be more"))
        assertTrue(SkiDayText.search(SearchResult.FetchFailed(FetchError(FetchError.Kind.NETWORK, "x"))).contains("offline"))
    }

    @Test fun bootLinesNeverInventNumbers() {
        assertNull(SkiDayText.bootLine(null))
        val empty = BootRangeStats(1, 60_000, emptyMap(), emptyMap(), emptyMap(), 0, null, null, null, 0, null, null, listOf("no saved boot calibration, so no boot roll"))
        assertEquals("Boot session overlaps, no boot numbers: no saved boot calibration, so no boot roll", SkiDayText.bootLine(empty))
        val full = empty.copy(movements = mapOf("L" to 30, "R" to 28), meanRollCorrelation = 0.83, agreementPairs = 25, meanTorsoGyroRmsDps = 41.6)
        assertEquals("Boots: movements left 30, right 28 (up to 30/min) · boot agreement 0.83 · torso 42 °/s", SkiDayText.bootLine(full))
    }
}
