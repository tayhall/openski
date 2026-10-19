package com.openski.android

import kotlin.math.max

/** One segment of a day as the day screen and export show it. [number] counts segments of the same kind from 1. */
data class ReportSegment(
    val index: Int,
    val segment: SkiDaySegment,
    val number: Int,
    /** The map match for a descent or lift; null for a rest or without a map. */
    val match: WayMatch?,
    /** Matched way names in order of travel (for example "Blue 12 → Combe"); null when unmatched or without a map. */
    val wayLabel: String?,
    /** Normalised piste difficulty of the way covering most of the descent, or the lift type; null when unknown. */
    val grade: String?,
    /** Boot numbers for a descent when a sensor session overlaps it; null otherwise (and always for lifts and rests). */
    val boot: BootRangeStats?,
) {
    val kind: SegmentKind get() = segment.kind
    val title: String get() = when (kind) {
        SegmentKind.DESCENT -> "Run $number"
        SegmentKind.LIFT -> "Lift $number"
        SegmentKind.REST -> "Rest $number"
    }
    /** "Blue 12 · intermediate", "Gondola 3 · gondola", or null when there is nothing to name. */
    val subtitle: String? get() = listOfNotNull(wayLabel, grade).joinToString(" · ").ifEmpty { null }
}

/** Day totals. Vertical figures are null without usable altitude. Times are segment durations (signal gaps excluded). */
data class DayTotals(
    val descendedM: Double?, val climbedM: Double?,
    val runs: Int, val lifts: Int,
    /** Index into [SkiDayReport.segments] of the longest descent by distance; null without descents. */
    val longestRunIndex: Int?, val longestRunM: Double?,
    val skiingS: Double, val liftS: Double, val restS: Double,
    val distanceSkiedM: Double,
    /** Highest held speed of any descent; null when none was held long enough. */
    val topSpeedMps: Double?,
)

/**
 * A ski day analysed: the split, the map match per segment, named segments with boot numbers, and day totals. Pure
 * glue over [SkiDaySplitter], [PisteMatcher] and [DayBootStats]; it never invents numbers: anything not measured is
 * null. EXPERIMENTAL and unvalidated on a real day.
 */
data class SkiDayReport(
    val split: SkiDaySplit,
    /** Lines up with [SkiDaySplit.segments]; null when no map was given. */
    val matches: List<WayMatch?>?,
    val segments: List<ReportSegment>,
    val totals: DayTotals,
    /** True when a resort map was given (it shapes the split as well as the names). */
    val mapUsed: Boolean,
    /** "(c) OpenStreetMap contributors (ODbL)" whenever a map was used; null otherwise. */
    val attribution: String?,
) {
    companion object {
        /** From a day's stored fixes, with the barometer altitude stored alongside them. [bootStats] is asked once per descent. */
        fun build(fixes: List<DayFix>, map: ResortMap?, bootStats: ((Long, Long) -> BootRangeStats?)? = null,
                  config: SkiDayConfig = SkiDayConfig()): SkiDayReport =
            build(fixes.map { it.fix }, baroSeries(fixes), map, bootStats, config)

        /** The barometer readings stored with the fixes, at the fix times. */
        fun baroSeries(fixes: List<DayFix>): List<BaroSample> =
            fixes.mapNotNull { f -> f.baroAltM?.takeIf { it.isFinite() }?.let { BaroSample(f.fix.timeMs, it) } }.sortedBy { it.timeMs }

        fun build(fixes: List<GpsFix>, baro: List<BaroSample>, map: ResortMap?, bootStats: ((Long, Long) -> BootRangeStats?)? = null,
                  config: SkiDayConfig = SkiDayConfig()): SkiDayReport {
            val split = SkiDaySplitter.split(fixes, baro, map, config)
            val matches = map?.let { PisteMatcher.matchAll(split, it) }
            val counters = HashMap<SegmentKind, Int>()
            val segments = split.segments.mapIndexed { i, s ->
                val number = (counters[s.kind] ?: 0) + 1
                counters[s.kind] = number
                val m = matches?.getOrNull(i)?.takeIf { it.status == MatchStatus.MATCHED }
                ReportSegment(i, s, number, matches?.getOrNull(i), m?.let(::label), m?.let { grade(s.kind, it) },
                    if (s.kind == SegmentKind.DESCENT) bootStats?.invoke(s.startMs, s.endMs) else null)
            }
            return SkiDayReport(split, matches, segments, totals(split, segments), map != null,
                if (map != null) ResortRepository.ATTRIBUTION else null)
        }

        fun totals(split: SkiDaySplit, segments: List<ReportSegment>): DayTotals {
            val descents = segments.filter { it.kind == SegmentKind.DESCENT }
            val lifts = segments.filter { it.kind == SegmentKind.LIFT }
            val hasAltitude = split.altitudeSource != AltitudeSource.NONE
            val longest = descents.maxByOrNull { it.segment.distanceM }
            return DayTotals(
                if (hasAltitude) descents.sumOf { max(0.0, -(it.segment.verticalM ?: 0.0)) } else null,
                if (hasAltitude) lifts.sumOf { max(0.0, it.segment.verticalM ?: 0.0) } else null,
                descents.size, lifts.size, longest?.index, longest?.segment?.distanceM,
                descents.sumOf { it.segment.durationS }, lifts.sumOf { it.segment.durationS },
                segments.filter { it.kind == SegmentKind.REST }.sumOf { it.segment.durationS },
                descents.sumOf { it.segment.distanceM },
                descents.mapNotNull { it.segment.topSpeedMps?.takeIf { v -> v.isFinite() } }.maxOrNull())
        }

        /** The name to show for one way: its name, else its ref, else "unnamed piste" or "unnamed lift". */
        fun wayName(w: ResortWay): String {
            w.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            w.ref?.trim()?.takeIf { it.isNotEmpty() }?.let { return (if (w.kind == WayKind.LIFT) "Lift " else "Piste ") + it }
            return if (w.kind == WayKind.LIFT) "unnamed lift" else "unnamed piste"
        }

        /** Matched ways in order of travel, a repeated name shown once. */
        fun label(m: WayMatch): String? = m.ways.map { wayName(it.way) }.fold(ArrayList<String>()) { acc, n ->
            if (acc.lastOrNull() != n) acc.add(n); acc
        }.joinToString(" → ").ifEmpty { null }

        /** Difficulty class of the main piste (most covered), or the lift type in words. */
        fun grade(kind: SegmentKind, m: WayMatch): String? {
            val main = m.ways.maxByOrNull { it.coveredFraction }?.way ?: return null
            return when (kind) {
                SegmentKind.DESCENT -> PisteDifficulty.normalise(main.difficulty)
                SegmentKind.LIFT -> main.aerialway?.trim()?.takeIf { it.isNotEmpty() }?.replace('_', ' ')
                SegmentKind.REST -> null
            }
        }
    }
}
