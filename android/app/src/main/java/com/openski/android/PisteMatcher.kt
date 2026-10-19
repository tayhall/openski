package com.openski.android

import kotlin.math.exp
import kotlin.math.floor
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
 * The ways of one [ResortMap] projected once into a flat frame centred on the map, with a grid of their line
 * segments so a fix only measures the lines near it. Built lazily per map ([ResortMap.index]) and reused by every
 * split and match on that map.
 */
class ResortIndex(ways: List<ResortWay>) {
    class Prepared(val way: ResortWay, val x: DoubleArray, val y: DoubleArray)

    /** Distance from a point to a way, and the way's unit direction at the nearest point. */
    class Projection(val d: Double, val tx: Double, val ty: Double)

    val frame: LocalFrame
    private val prepared = HashMap<WayKind, List<Prepared>>()
    private val grids = HashMap<WayKind, HashMap<Long, MutableList<Long>>>()

    init {
        val valid = ways.map { w -> w to w.points.filter { it.lat.isFinite() && it.lon.isFinite() } }.filter { it.second.size >= 2 }
        frame = LocalFrame.around(valid.flatMap { it.second })
        for (kind in WayKind.values()) {
            val list = valid.filter { it.first.kind == kind }.map { (w, pts) ->
                Prepared(w, DoubleArray(pts.size) { frame.x(pts[it].lon) }, DoubleArray(pts.size) { frame.y(pts[it].lat) })
            }
            val grid = HashMap<Long, MutableList<Long>>()
            for ((wi, p) in list.withIndex()) for (k in 0 until p.x.size - 1) {
                val cx0 = cell(min(p.x[k], p.x[k + 1])); val cx1 = cell(max(p.x[k], p.x[k + 1]))
                val cy0 = cell(min(p.y[k], p.y[k + 1])); val cy1 = cell(max(p.y[k], p.y[k + 1]))
                for (cx in cx0..cx1) for (cy in cy0..cy1) grid.getOrPut(key(cx, cy)) { ArrayList(4) }.add(wi.toLong() shl 32 or k.toLong())
            }
            prepared[kind] = list
            grids[kind] = grid
        }
    }

    fun ways(kind: WayKind): List<Prepared> = prepared[kind] ?: emptyList()

    /** Every way of [kind] with a segment within [tol] metres of (px, py), with its nearest projection. */
    fun near(kind: WayKind, px: Double, py: Double, tol: Double): Map<Int, Projection> {
        val list = ways(kind)
        val grid = grids[kind] ?: return emptyMap()
        if (list.isEmpty() || !(tol >= 0) || !px.isFinite() || !py.isFinite()) return emptyMap()
        val out = HashMap<Int, Projection>()
        for (cx in cell(px - tol)..cell(px + tol)) for (cy in cell(py - tol)..cell(py + tol)) {
            val refs = grid[key(cx, cy)] ?: continue
            for (ref in refs) {
                val wi = (ref ushr 32).toInt()
                val k = (ref and 0xffffffffL).toInt()
                val p = list[wi]
                val ax = p.x[k]; val ay = p.y[k]
                val dx = p.x[k + 1] - ax; val dy = p.y[k + 1] - ay
                val len2 = dx * dx + dy * dy
                if (len2 <= 0) continue
                val u = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
                val d = LocalFrame.dist(px, py, ax + u * dx, ay + u * dy)
                if (d > tol) continue
                val best = out[wi]
                if (best == null || d < best.d) { val len = sqrt(len2); out[wi] = Projection(d, dx / len, dy / len) }
            }
        }
        return out
    }

    private fun cell(v: Double): Int = floor(v / CELL_M).toInt()
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    companion object {
        /** Grid cell size in metres: a few tolerances wide, so a query reads at most a handful of cells. */
        const val CELL_M = 100.0
    }
}

/**
 * Names descents and lifts by snapping fixes to piste and lift lines. A Viterbi pass over per-fix candidates (each way
 * within tolerance, plus "unmatched") trades closeness against a cost for switching way, and charges fixes that run
 * against a way's direction (pistes downhill, lifts up). Pure; EXPERIMENTAL and unvalidated on real days.
 */
object PisteMatcher {
    private class Candidate(val way: Int, val cost: Double, val quality: Double, val wrongWay: Boolean)

    /** Matching tolerance for one fix; null when its accuracy is unusable (not finite or negative). */
    private fun tolerance(kind: WayKind, fix: GpsFix, c: SkiDayConfig): Double? {
        val acc = fix.accuracyM
        if (!acc.isFinite() || acc < 0) return null
        return (if (kind == WayKind.LIFT) c.liftBaseToleranceM else c.pisteBaseToleranceM) + c.accuracyFactor * acc
    }

    /** Per fix: travel direction as a unit vector, or null when too slow to tell. */
    private fun directions(fixes: List<GpsFix>, x: DoubleArray, y: DoubleArray, c: SkiDayConfig): Array<DoubleArray?> {
        val t = DoubleArray(fixes.size) { (fixes[it].timeMs - fixes[0].timeMs) / 1000.0 }
        val out = arrayOfNulls<DoubleArray>(fixes.size)
        var a = 0
        var b = 0
        for (i in fixes.indices) {
            while (t[i] - t[a] > c.speedHalfWindowS) a++
            if (b < i) b = i
            while (b < fixes.size - 1 && t[b + 1] - t[i] <= c.speedHalfWindowS) b++
            val dt = t[b] - t[a]
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            val d = sqrt(dx * dx + dy * dy)
            out[i] = if (dt <= 0 || d / dt < c.directionMinSpeedMps) null else doubleArrayOf(dx / d, dy / d)
        }
        return out
    }

    private fun candidates(fixes: List<GpsFix>, kind: WayKind, index: ResortIndex, c: SkiDayConfig): List<List<Candidate>> {
        val x = DoubleArray(fixes.size) { index.frame.x(fixes[it].lon) }
        val y = DoubleArray(fixes.size) { index.frame.y(fixes[it].lat) }
        val dir = directions(fixes, x, y, c)
        return fixes.indices.map { i ->
            val tol = tolerance(kind, fixes[i], c) ?: return@map emptyList<Candidate>()
            // A zero tolerance (zero base and perfect accuracy) still needs a finite spread.
            val sigma = max(tol / 2, 0.5)
            index.near(kind, x[i], y[i], tol).map { (w, pr) ->
                val z = pr.d / sigma
                var cost = 0.5 * z * z
                var quality = exp(-0.5 * z * z)
                val u = dir[i]
                val wrongWay = u != null && u[0] * pr.tx + u[1] * pr.ty < c.wrongWayCos
                if (wrongWay) {
                    cost += c.wrongWayCost
                    quality *= c.wrongWayScoreFactor
                }
                Candidate(w, cost, quality, wrongWay)
            }.sortedBy { it.way }
        }
    }

    /** Fraction of [fixes] lying within tolerance of a way of [kind] without running against it. */
    fun fractionOnWays(fixes: List<GpsFix>, kind: WayKind, map: ResortMap, c: SkiDayConfig = SkiDayConfig()): Double {
        if (fixes.isEmpty() || map.index.ways(kind).isEmpty()) return 0.0
        val cands = candidates(fixes, kind, map.index, c)
        return cands.count { list -> list.any { !it.wrongWay } }.toDouble() / fixes.size
    }

    /** Matches one segment's fixes against the ways of [kind]. */
    fun match(fixes: List<GpsFix>, kind: WayKind, map: ResortMap, c: SkiDayConfig = SkiDayConfig()): WayMatch {
        if (fixes.isEmpty()) return WayMatch.NONE
        val ways = map.index.ways(kind)
        if (ways.isEmpty()) return WayMatch.NONE
        val cands = candidates(fixes, kind, map.index, c)
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
