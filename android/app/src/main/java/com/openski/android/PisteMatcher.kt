package com.openski.android

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One way a segment followed, in order of travel. Fractions count fixes. */
data class MatchedWay(
    val way: ResortWay,
    /** Fraction of the segment's fixes assigned to this way. */
    val coveredFraction: Double,
    /** Mean match quality (0 to 1) of those fixes: closeness to the line, halved when running against it. */
    val score: Double,
    /** First and last assigned fix, as indices into the fixes passed to [PisteMatcher.match]. */
    val firstIndex: Int, val lastIndex: Int,
)

enum class MatchStatus { MATCHED, UNMATCHED }

/**
 * The ways one segment followed. [score] is the summed quality of matched fixes over all fixes (0 to 1); below
 * [SkiDayConfig.minMatchScore] the status is UNMATCHED and [ways] is empty rather than a guess.
 */
data class WayMatch(val status: MatchStatus, val ways: List<MatchedWay>, val coveredFraction: Double, val score: Double) {
    companion object {
        val NONE = WayMatch(MatchStatus.UNMATCHED, emptyList(), 0.0, 0.0)
    }
}

/**
 * Names descents and lifts by snapping fixes to piste and lift lines. A Viterbi pass over per-fix candidates (each way
 * within tolerance, plus "unmatched") trades closeness against a cost for switching way, and charges fixes that run
 * against a way's direction (pistes downhill, lifts up). Pure; EXPERIMENTAL and unvalidated on real days.
 */
object PisteMatcher {
    private class Prepared(val way: ResortWay, val x: DoubleArray, val y: DoubleArray,
                           val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    private class Projection(val d: Double, val tx: Double, val ty: Double)

    private class Candidate(val way: Int, val cost: Double, val quality: Double, val wrongWay: Boolean)

    private fun prepare(map: ResortMap, kind: WayKind, frame: LocalFrame): List<Prepared> =
        map.ways.filter { w -> w.kind == kind && w.points.count { it.lat.isFinite() && it.lon.isFinite() } >= 2 }.map { w ->
            val pts = w.points.filter { it.lat.isFinite() && it.lon.isFinite() }
            val x = DoubleArray(pts.size) { frame.x(pts[it].lon) }
            val y = DoubleArray(pts.size) { frame.y(pts[it].lat) }
            Prepared(w, x, y, x.min(), x.max(), y.min(), y.max())
        }

    private fun project(p: Prepared, px: Double, py: Double): Projection {
        var best = Double.MAX_VALUE
        var tx = 0.0
        var ty = 0.0
        for (k in 0 until p.x.size - 1) {
            val ax = p.x[k]; val ay = p.y[k]
            val dx = p.x[k + 1] - ax; val dy = p.y[k + 1] - ay
            val len2 = dx * dx + dy * dy
            if (len2 <= 0) continue
            val u = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
            val d = LocalFrame.dist(px, py, ax + u * dx, ay + u * dy)
            if (d < best) { best = d; val len = sqrt(len2); tx = dx / len; ty = dy / len }
        }
        return Projection(best, tx, ty)
    }

    private fun tolerance(kind: WayKind, fix: GpsFix, c: SkiDayConfig) =
        (if (kind == WayKind.LIFT) c.liftBaseToleranceM else c.pisteBaseToleranceM) + c.accuracyFactor * fix.accuracyM

    /** Per fix: travel direction as a unit vector, or null when too slow to tell. */
    private fun directions(fixes: List<GpsFix>, x: DoubleArray, y: DoubleArray, c: SkiDayConfig): Array<DoubleArray?> {
        val t = DoubleArray(fixes.size) { (fixes[it].timeMs - fixes[0].timeMs) / 1000.0 }
        return Array(fixes.size) { i ->
            var a = i
            var b = i
            while (a > 0 && t[i] - t[a - 1] <= c.speedHalfWindowS) a--
            while (b < fixes.size - 1 && t[b + 1] - t[i] <= c.speedHalfWindowS) b++
            val dt = t[b] - t[a]
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            val d = sqrt(dx * dx + dy * dy)
            if (dt <= 0 || d / dt < c.directionMinSpeedMps) null else doubleArrayOf(dx / d, dy / d)
        }
    }

    private fun candidates(fixes: List<GpsFix>, kind: WayKind, ways: List<Prepared>, x: DoubleArray, y: DoubleArray,
                           c: SkiDayConfig): List<List<Candidate>> {
        val dir = directions(fixes, x, y, c)
        return fixes.indices.map { i ->
            val tol = tolerance(kind, fixes[i], c)
            val sigma = tol / 2
            val list = ArrayList<Candidate>()
            for ((w, p) in ways.withIndex()) {
                if (x[i] < p.minX - tol || x[i] > p.maxX + tol || y[i] < p.minY - tol || y[i] > p.maxY + tol) continue
                val pr = project(p, x[i], y[i])
                if (pr.d > tol) continue
                val z = pr.d / sigma
                var cost = 0.5 * z * z
                var quality = exp(-0.5 * z * z)
                val u = dir[i]
                val wrongWay = u != null && u[0] * pr.tx + u[1] * pr.ty < c.wrongWayCos
                if (wrongWay) {
                    cost += c.wrongWayCost
                    quality *= c.wrongWayScoreFactor
                }
                list.add(Candidate(w, cost, quality, wrongWay))
            }
            list
        }
    }

    /** Fraction of [fixes] lying within tolerance of a way of [kind] without running against it. */
    fun fractionOnWays(fixes: List<GpsFix>, kind: WayKind, map: ResortMap, c: SkiDayConfig = SkiDayConfig()): Double {
        if (fixes.isEmpty()) return 0.0
        val frame = LocalFrame(fixes[0].lat, fixes[0].lon)
        val ways = prepare(map, kind, frame)
        if (ways.isEmpty()) return 0.0
        val x = DoubleArray(fixes.size) { frame.x(fixes[it].lon) }
        val y = DoubleArray(fixes.size) { frame.y(fixes[it].lat) }
        val cands = candidates(fixes, kind, ways, x, y, c)
        return cands.count { list -> list.any { !it.wrongWay } }.toDouble() / fixes.size
    }

    /** Matches one segment's fixes against the ways of [kind]. */
    fun match(fixes: List<GpsFix>, kind: WayKind, map: ResortMap, c: SkiDayConfig = SkiDayConfig()): WayMatch {
        if (fixes.isEmpty()) return WayMatch.NONE
        val frame = LocalFrame(fixes[0].lat, fixes[0].lon)
        val ways = prepare(map, kind, frame)
        if (ways.isEmpty()) return WayMatch.NONE
        val x = DoubleArray(fixes.size) { frame.x(fixes[it].lon) }
        val y = DoubleArray(fixes.size) { frame.y(fixes[it].lat) }
        val cands = candidates(fixes, kind, ways, x, y, c)
        val n = fixes.size

        // Viterbi over states (candidate ways plus unmatched = -1).
        val states = Array(n) { i -> IntArray(cands[i].size + 1) { k -> if (k < cands[i].size) cands[i][k].way else -1 } }
        val cost = Array(n) { DoubleArray(states[it].size) }
        val back = Array(n) { IntArray(states[it].size) }
        for (i in 0 until n) {
            val emit = DoubleArray(states[i].size) { k -> if (k < cands[i].size) cands[i][k].cost else c.unmatchedCost }
            if (i == 0) { emit.copyInto(cost[0]); continue }
            val prev = cost[i - 1]
            var bestPrev = 0
            for (k in prev.indices) if (prev[k] < prev[bestPrev]) bestPrev = k
            for (k in states[i].indices) {
                var from = bestPrev
                var via = prev[bestPrev] + c.switchCost
                val same = states[i - 1].indexOf(states[i][k])
                if (same >= 0 && prev[same] <= via) { from = same; via = prev[same] }
                cost[i][k] = via + emit[k]
                back[i][k] = from
            }
        }
        val assigned = IntArray(n)
        val quality = DoubleArray(n)
        var k = cost[n - 1].indices.minByOrNull { cost[n - 1][it] }!!
        for (i in n - 1 downTo 0) {
            assigned[i] = states[i][k]
            quality[i] = if (k < cands[i].size) cands[i][k].quality else 0.0
            if (i > 0) k = back[i][k]
        }

        // Consecutive runs of one way, in order; drop runs too small or too poor to name.
        val runs = ArrayList<IntArray>()   // [way, first, last]
        for (i in 0 until n) {
            if (assigned[i] < 0) continue
            val last = runs.lastOrNull()
            if (last != null && last[0] == assigned[i] && last[2] == i - 1) last[2] = i else runs.add(intArrayOf(assigned[i], i, i))
        }
        val kept = ArrayList<MatchedWay>()
        for (r in runs) {
            val count = r[2] - r[1] + 1
            val score = (r[1]..r[2]).sumOf { quality[it] } / count
            if (count.toDouble() / n < c.minWayFraction || score < c.minMatchScore) continue
            val previous = kept.lastOrNull()
            if (previous != null && previous.way.osmId == ways[r[0]].way.osmId) {
                // The same way again after an unmatched stretch: one entry.
                val total = previous.coveredFraction * n + count
                kept[kept.lastIndex] = previous.copy(coveredFraction = total / n,
                    score = (previous.score * previous.coveredFraction * n + score * count) / total, lastIndex = r[2])
            } else kept.add(MatchedWay(ways[r[0]].way, count.toDouble() / n, score, r[1], r[2]))
        }
        val covered = kept.sumOf { it.coveredFraction }
        val total = kept.sumOf { it.score * it.coveredFraction }
        return if (kept.isEmpty() || total < c.minMatchScore) WayMatch(MatchStatus.UNMATCHED, emptyList(), covered, total)
        else WayMatch(MatchStatus.MATCHED, kept, covered, total)
    }

    /** Matches every DESCENT against pistes and every LIFT against lift lines; null for REST. */
    fun matchAll(split: SkiDaySplit, map: ResortMap): List<WayMatch?> = split.segments.map { s ->
        when (s.kind) {
            SegmentKind.REST -> null
            SegmentKind.DESCENT -> match(split.fixes.subList(s.firstIndex, s.lastIndex + 1), WayKind.PISTE, map, split.config)
            SegmentKind.LIFT -> match(split.fixes.subList(s.firstIndex, s.lastIndex + 1), WayKind.LIFT, map, split.config)
        }
    }
}
