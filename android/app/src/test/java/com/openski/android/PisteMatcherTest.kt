package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Naming by map-matching on a synthetic map. Not covered: real OpenStreetMap geometry (pistes mapped as areas,
 * inconsistent way directions, gaps at junctions, lifts sharing stations), real GPS multipath, and large resorts
 * (performance is linear in fixes times nearby ways, not measured on a real map).
 */
class PisteMatcherTest {
    private val rnd = Random(3)
    private fun lat(y: Double) = SyntheticSkiDay.lat(y)
    private fun lon(x: Double) = SyntheticSkiDay.lon(x)

    /** A straight way from (x0, y0) to (x1, y1) in metres, with a point every 20 m. */
    private fun way(id: Long, kind: WayKind, name: String, x0: Double, y0: Double, x1: Double, y1: Double,
                    difficulty: String? = null): ResortWay {
        val steps = (Math.hypot(x1 - x0, y1 - y0) / 20).toInt().coerceAtLeast(1)
        return ResortWay(id, kind, name, null, difficulty, if (kind == WayKind.LIFT) "chair_lift" else null,
            (0..steps).map { k -> LatLon(lat(y0 + (y1 - y0) * k / steps), lon(x0 + (x1 - x0) * k / steps)) })
    }

    /** Fixes at 1 Hz along a polyline of (x, y) metres at [speed] m/s with white noise of [noise] m. */
    private fun track(points: List<Pair<Double, Double>>, speed: Double = 8.0, noise: Double = 3.0, accuracy: Float = 5f): List<GpsFix> {
        val out = ArrayList<GpsFix>()
        var t = 0L
        for ((a, b) in points.zipWithNext()) {
            val len = Math.hypot(b.first - a.first, b.second - a.second)
            val n = (len / speed).toInt()
            for (k in 0 until n) {
                val x = a.first + (b.first - a.first) * k / n + noise * rnd.nextGaussian()
                val y = a.second + (b.second - a.second) * k / n + noise * rnd.nextGaussian()
                out.add(GpsFix(SyntheticSkiDay.T0_MS + t * 1000, lat(y), lon(x), null, null, accuracy, null))
                t++
            }
        }
        return out
    }

    // Piste A runs south (downhill) along x = 0; B runs south 25 m east of it; C continues east from A's bottom.
    private val a = way(1, WayKind.PISTE, "A", 0.0, 0.0, 0.0, -1200.0, "easy")
    private val b = way(2, WayKind.PISTE, "B", 25.0, 0.0, 25.0, -1200.0, "intermediate")
    private val c = way(3, WayKind.PISTE, "C", 0.0, -1200.0, 800.0, -1200.0, "advanced")
    private val lift = way(4, WayKind.LIFT, "Chair", 0.0, -1200.0, 0.0, 0.0)
    private val map = ResortMap(listOf(a, b, c, lift))

    @Test fun aDescentDownOnePisteIsNamedWithAHighScore() {
        val m = PisteMatcher.match(track(listOf(0.0 to 0.0, 0.0 to -1200.0)), WayKind.PISTE, map)
        assertEquals(MatchStatus.MATCHED, m.status)
        assertEquals(listOf("A"), m.ways.map { it.way.name })
        assertTrue("score ${m.score}", m.score > 0.8)
        assertTrue(m.coveredFraction > 0.95)
    }

    @Test fun theNearerOfTwoParallelPistesWins() {
        val m = PisteMatcher.match(track(listOf(22.0 to 0.0, 22.0 to -1200.0)), WayKind.PISTE, map)
        assertEquals(listOf("B"), m.ways.map { it.way.name })
    }

    @Test fun directionDecidesBetweenTwoPistesEquallyClose() {
        // B2 is drawn uphill (north), so a skier going south half-way between A and B2 belongs to A.
        val b2 = way(5, WayKind.PISTE, "B2", 25.0, -1200.0, 25.0, 0.0)
        val m = PisteMatcher.match(track(listOf(12.5 to 0.0, 12.5 to -1200.0)), WayKind.PISTE, ResortMap(listOf(a, b2)))
        assertEquals(listOf("A"), m.ways.map { it.way.name })
    }

    @Test fun aDescentOverTwoPistesListsThemInOrderWithFractions() {
        val m = PisteMatcher.match(track(listOf(0.0 to 0.0, 0.0 to -1200.0, 800.0 to -1200.0)), WayKind.PISTE, map)
        assertEquals(listOf("A", "C"), m.ways.map { it.way.name })
        assertEquals(0.6, m.ways[0].coveredFraction, 0.05)
        assertEquals(0.4, m.ways[1].coveredFraction, 0.05)
        assertTrue(m.ways[0].lastIndex < m.ways[1].firstIndex)
    }

    @Test fun aBriefDriftTowardsTheNeighbourDoesNotAddIt() {
        val fixes = track(listOf(0.0 to 0.0, 0.0 to -1200.0)).mapIndexed { i, f ->
            if (i in 60..64) f.copy(lon = lon(24.0)) else f   // five fixes on B's line
        }
        val m = PisteMatcher.match(fixes, WayKind.PISTE, map)
        assertEquals(listOf("A"), m.ways.map { it.way.name })
    }

    @Test fun farFromEveryWayIsUnmatchedNotGuessed() {
        val m = PisteMatcher.match(track(listOf(400.0 to 0.0, 400.0 to -1200.0)), WayKind.PISTE, map)
        assertEquals(MatchStatus.UNMATCHED, m.status)
        assertTrue(m.ways.isEmpty())
        assertEquals(0.0, m.score, 1e-9)
    }

    @Test fun aTrackOnlyPartlyOnAPisteIsUnmatchedBelowTheScore() {
        // 300 m on A, then 900 m off into the trees.
        val m = PisteMatcher.match(track(listOf(0.0 to 0.0, 0.0 to -300.0, 300.0 to -1200.0)), WayKind.PISTE, map)
        assertEquals(MatchStatus.UNMATCHED, m.status)
    }

    @Test fun poorAccuracyWidensTheTolerance() {
        // 30 m off A: with 5 m accuracy (tolerance 37.5 m) each fix scores about 0.28, below 0.5; with 20 m accuracy
        // (tolerance 60 m) about 0.61.
        val tight = PisteMatcher.match(track(listOf(-30.0 to 0.0, -30.0 to -1200.0), noise = 1.0), WayKind.PISTE, ResortMap(listOf(a)))
        val loose = PisteMatcher.match(track(listOf(-30.0 to 0.0, -30.0 to -1200.0), noise = 1.0, accuracy = 20f), WayKind.PISTE,
            ResortMap(listOf(a)))
        assertEquals(MatchStatus.UNMATCHED, tight.status)
        assertEquals(listOf("A"), loose.ways.map { it.way.name })
    }

    @Test fun liftsMatchOnlyLiftLines() {
        val up = track(listOf(0.0 to -1200.0, 0.0 to 0.0), speed = 2.5)
        val m = PisteMatcher.match(up, WayKind.LIFT, map)
        assertEquals(listOf("Chair"), m.ways.map { it.way.name })
        // The same line as a piste search finds A only against its direction, which scores below the threshold.
        assertEquals(MatchStatus.UNMATCHED, PisteMatcher.match(up, WayKind.PISTE, ResortMap(listOf(a))).status)
    }

    @Test fun emptyInputsAndDegenerateWaysAreSafe() {
        assertEquals(MatchStatus.UNMATCHED, PisteMatcher.match(emptyList(), WayKind.PISTE, map).status)
        val fixes = track(listOf(0.0 to 0.0, 0.0 to -200.0))
        assertEquals(MatchStatus.UNMATCHED, PisteMatcher.match(fixes, WayKind.PISTE, ResortMap(emptyList())).status)
        val degenerate = ResortWay(9, WayKind.PISTE, null, null, null, null, listOf(LatLon(lat(0.0), lon(0.0)), LatLon(Double.NaN, 7.0)))
        assertEquals(MatchStatus.UNMATCHED, PisteMatcher.match(fixes, WayKind.PISTE, ResortMap(listOf(degenerate))).status)
    }

    @Test fun matchAllNamesTheSyntheticDay() {
        val day = SyntheticSkiDay.build(SyntheticSkiDay.FULL_DAY)
        val map = SkiDaySplitterTest.mapFrom(day)
        val split = SkiDaySplitter.split(day.fixes, map = map)
        val matches = PisteMatcher.matchAll(split, map)
        assertEquals(split.segments.size, matches.size)
        split.segments.zip(matches).forEachIndexed { i, (s, m) ->
            if (s.kind == SegmentKind.REST) assertEquals(null, m)
            else {
                assertEquals("segment $i", MatchStatus.MATCHED, m!!.status)
                // The way built from truth segment i carries id 1000 + i and covers most of the segment.
                assertEquals("segment $i", 1000L + i, m.ways.maxByOrNull { it.coveredFraction }!!.way.osmId)
                assertTrue("segment $i covered ${m.coveredFraction}", m.coveredFraction > 0.8)
            }
        }
    }
}
