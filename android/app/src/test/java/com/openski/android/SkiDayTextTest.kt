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
        val keptNote = " The cached copy from 2026-01-09 is kept."
        assertEquals("No connection to the map server. Are you offline? Cached maps still work.$keptNote",
            SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.NETWORK, "unreachable"), kept)))
        assertEquals("The map server is rate limiting requests. Wait a few minutes and try again.",
            SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.HTTP, "x", 429), null)))
        assertEquals("The map server is busy (timeout). Try again later.",
            SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.HTTP, "x", 504), null)))
        assertEquals("No pistes or lifts are mapped in that area. Nothing was saved.", SkiDayText.download(DownloadResult.NoData(null)))
        assertEquals("The map server cut its answer short (truncated: runtime error: timeout). Nothing was saved; try a smaller area or later.$keptNote",
            SkiDayText.download(DownloadResult.Partial("runtime error: timeout", kept)))
        assertEquals("The map server's answer could not be read (it may have been truncated): unexpected end.",
            SkiDayText.download(DownloadResult.ParseFailed("unexpected end", null)))
        assertEquals("Not downloading: a boot recording is in progress.", SkiDayText.download(DownloadResult.Blocked("a boot recording is in progress", null)))
        assertEquals("The map server did not answer in time. Try again later or a smaller area.",
            SkiDayText.download(DownloadResult.FetchFailed(FetchError(FetchError.Kind.TIMEOUT, "t"), null)))
        assertEquals("Saved 10 pistes and lifts.", SkiDayText.download(DownloadResult.Saved(kept, ResortData(emptyList(), emptyList()))))
        assertEquals("Saved 10 pistes and lifts. The area was cut to 25 km around its centre.",
            SkiDayText.download(DownloadResult.Saved(kept.copy(truncated = true), ResortData(emptyList(), emptyList()))))
        assertEquals("Saving the map on this phone failed: disk full.$keptNote", SkiDayText.download(DownloadResult.StoreFailed("disk full", kept)))
        assertEquals("Cannot ask for that area: Radius must be positive.", SkiDayText.download(DownloadResult.InvalidRequest("Radius must be positive", null)))
    }

    @Test fun searchResultsAreDescribed() {
        assertEquals("No ski area of that name is mapped.", SkiDayText.search(SearchResult.Found(emptyList())))
        val c = ResortCandidate("way", 1, "Peak", LatLon(46.0, 7.0), null)
        assertEquals("1 found.", SkiDayText.search(SearchResult.Found(listOf(c))))
        assertEquals("1 found; there may be more, so try a longer name.", SkiDayText.search(SearchResult.Found(listOf(c), maybeMore = true)))
        assertEquals("No connection to the map server. Are you offline? Cached maps still work.",
            SkiDayText.search(SearchResult.FetchFailed(FetchError(FetchError.Kind.NETWORK, "x"))))
    }

    @Test fun verticalsReadWithTheirSign() {
        assertEquals("412 m down", SkiDayText.verticalWord(-412.3))
        assertEquals("35 m up", SkiDayText.verticalWord(35.0))
        assertEquals("0 m", SkiDayText.verticalWord(-0.4))
        assertEquals("–", SkiDayText.verticalWord(Double.NaN))
        val noisy = ReportSegment(0, SkiDaySegment(SegmentKind.DESCENT, 0, 125_000, 0, 1, 900.0, 4.0, 7.2, 12.0, null, emptyList()), 1, null, null, null, null)
        assertEquals("2:05 · 4 m up · 900 m · top 43 km/h", SkiDayText.segmentLine(noisy))
    }

    @Test fun bootLinesNeverInventNumbers() {
        assertNull(SkiDayText.bootLine(null))
        val empty = BootRangeStats(1, 60_000, emptyMap(), emptyMap(), emptyMap(), 0, null, null, null, 0, null, null, listOf("no saved boot calibration, so no boot roll"))
        assertEquals("Boot session overlaps, no boot numbers: no saved boot calibration, so no boot roll", SkiDayText.bootLine(empty))
        val full = empty.copy(movements = mapOf("L" to 30, "R" to 28), meanRollCorrelation = 0.83, agreementPairs = 25, meanTorsoGyroRmsDps = 41.6)
        assertEquals("Boots: movements left 30, right 28 (up to 30/min) · boot agreement 0.83 · torso 42 °/s", SkiDayText.bootLine(full))
    }
}
