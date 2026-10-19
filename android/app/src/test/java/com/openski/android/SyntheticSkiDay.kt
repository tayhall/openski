package com.openski.android

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic ski day with known truth, for the splitter and matcher tests. Phases are integrated at 1 Hz in a local
 * metre frame around lat 46, lon 7, then GPS noise (correlated, as real GPS drifts rather than jumps) is added.
 */
object SyntheticSkiDay {
    const val LAT0 = 46.0
    const val LON0 = 7.0
    const val T0_MS = 1_768_035_600_000L   // 2026-01-10 09:00 UTC
    private const val M_PER_DEG_LAT = LocalFrame.EARTH_M * PI / 180.0
    private val M_PER_DEG_LON = M_PER_DEG_LAT * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / M_PER_DEG_LAT
    fun lon(x: Double) = LON0 + x / M_PER_DEG_LON

    /**
     * One phase: [seconds] long at [groundMps] along [headingDeg] (0 north, 90 east) with vertical speed [vzMps].
     * [turns] swings the heading by +-40 degrees with a 6 s period, like linked turns down a fall line.
     */
    data class Phase(val kind: SegmentKind, val seconds: Int, val groundMps: Double, val vzMps: Double,
                     val headingDeg: Double = 180.0, val turns: Boolean = false)

    /** Truth for one segment: kind, start and end in ms, and the vertical change and path length. */
    data class Truth(val kind: SegmentKind, val startMs: Long, val endMs: Long, val verticalM: Double, val pathM: Double)

    class Day(val fixes: List<GpsFix>, val baro: List<BaroSample>, val truth: List<Truth>,
              /** Noise-free centre line (fall line for descents, cable for lifts) of each truth segment. */
              val lines: List<List<LatLon>>)

    /** Builds a day from groups of phases; each group is one truth segment. */
    fun build(groups: List<List<Phase>>, seed: Long = 7, gpsAltNoiseM: Double = 5.0, horizNoiseM: Double = 3.0,
              baroNoiseM: Double = 0.3, startAltM: Double = 2400.0, withAltitude: Boolean = true,
              reportSpeed: Boolean = false): Day {
        val rnd = Random(seed)
        var x = 0.0; var y = 0.0; var z = startAltM; var t = 0
        val fixes = ArrayList<GpsFix>()
        val baro = ArrayList<BaroSample>()
        val truth = ArrayList<Truth>()
        val lines = ArrayList<List<LatLon>>()
        val rhoH = 0.9; val innovH = horizNoiseM * sqrt(1 - rhoH * rhoH)
        val rhoZ = 0.95; val innovZ = gpsAltNoiseM * sqrt(1 - rhoZ * rhoZ)
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        // Recent true positions: their mean over one turn period is the centre line used for map lines.
        val recentX = ArrayDeque<Double>(); val recentY = ArrayDeque<Double>()
        fun centre() = LatLon(lat(recentY.average()), lon(recentX.average()))
        fun emit(speed: Double) {
            nx = rhoH * nx + innovH * rnd.nextGaussian()
            ny = rhoH * ny + innovH * rnd.nextGaussian()
            nz = rhoZ * nz + innovZ * rnd.nextGaussian()
            val ms = T0_MS + t * 1000L
            fixes.add(GpsFix(ms, lat(y + ny), lon(x + nx),
                if (withAltitude) z + nz + gpsAltNoiseM * 0.2 * rnd.nextGaussian() else null,
                if (reportSpeed) speed + 0.2 * rnd.nextGaussian() else null, 5f, 8f))
            baro.add(BaroSample(ms, z - 1700.0 + baroNoiseM * rnd.nextGaussian()))
        }
        emit(0.0)
        for (group in groups) {
            val startMs = T0_MS + t * 1000L
            val startZ = z
            var path = 0.0
            recentX.clear(); recentY.clear(); recentX.add(x); recentY.add(y)
            val line = arrayListOf(LatLon(lat(y), lon(x)))
            for (p in group) {
                for (s in 0 until p.seconds) {
                    val swing = if (p.turns) 40.0 * sin(2 * PI * t / 6.0) else 0.0
                    val h = Math.toRadians(p.headingDeg + swing)
                    x += p.groundMps * sin(h); y += p.groundMps * cos(h)
                    recentX.addLast(x); recentY.addLast(y)
                    if (recentX.size > 6) { recentX.removeFirst(); recentY.removeFirst() }
                    z += p.vzMps
                    path += p.groundMps
                    t++
                    emit(p.groundMps)
                    if (t % 10 == 0) line.add(centre())
                }
            }
            line.add(LatLon(lat(y), lon(x)))
            truth.add(Truth(group.first().kind, startMs, T0_MS + t * 1000L, z - startZ, path))
            lines.add(line)
        }
        return Day(fixes, baro, truth, lines)
    }

    private fun rest(s: Int, shuffle: Double = 0.0) = Phase(SegmentKind.REST, s, shuffle, 0.0, 0.0)

    /**
     * Three descents, two lifts and rests: a flat traverse in descent 1, a queue before lift 1, a lift that stops for
     * a minute, a 30 s stop mid-descent 2, a pole push uphill and a flat run-out in descent 3.
     */
    val FULL_DAY: List<List<Phase>> = listOf(
        listOf(rest(120)),
        listOf(Phase(SegmentKind.DESCENT, 130, 10.0, -2.0, turns = true),
            Phase(SegmentKind.DESCENT, 40, 6.0, 0.0, headingDeg = 120.0),
            Phase(SegmentKind.DESCENT, 130, 10.0, -2.0, turns = true)),
        listOf(rest(150, shuffle = 0.3)),
        listOf(Phase(SegmentKind.LIFT, 270, 2.5, 1.2, headingDeg = 10.0),
            Phase(SegmentKind.LIFT, 60, 0.0, 0.0, headingDeg = 10.0),
            Phase(SegmentKind.LIFT, 270, 2.5, 1.2, headingDeg = 10.0)),
        listOf(rest(120)),
        listOf(Phase(SegmentKind.DESCENT, 150, 10.0, -2.0, headingDeg = 200.0, turns = true),
            Phase(SegmentKind.DESCENT, 30, 0.0, 0.0, headingDeg = 200.0),
            Phase(SegmentKind.DESCENT, 150, 10.0, -2.0, headingDeg = 200.0, turns = true)),
        listOf(rest(120)),
        listOf(Phase(SegmentKind.LIFT, 400, 3.0, 1.5, headingDeg = 20.0)),
        listOf(rest(100)),
        listOf(Phase(SegmentKind.DESCENT, 100, 9.0, -1.8, headingDeg = 170.0, turns = true),
            Phase(SegmentKind.DESCENT, 15, 2.0, 0.6, headingDeg = 170.0),
            Phase(SegmentKind.DESCENT, 100, 9.0, -1.8, headingDeg = 170.0, turns = true),
            Phase(SegmentKind.DESCENT, 30, 5.0, 0.0, headingDeg = 170.0)),
        listOf(rest(120)),
    )
}
