package com.openski.android

import kotlin.math.*

data class TimelinePoint(val side: String, val timeMs: Double, val sample: SensorSample, val source: String,
    val captureId: String? = null, val recordIndex: Int? = null, val sensorTimeUs: Long? = null,
    val receivedAtMs: Long? = null)
data class StreamQuality(val side: String, val liveSamples: Int, val flashSamples: Int, val coverage: Double,
    val gaps: Int, val longestGapMs: Double, val dropped: Int)
data class TurnCandidate(val side: String, val startMs: Double, val endMs: Double, val positive: Boolean,
    val peakRadps: Float)

data class CandidateInterval(val startMs: Double, val endMs: Double, val positive: Boolean) {
    val durationMs: Double get() = endMs - startMs
}

data class TurnTiming(val intervals: List<CandidateInterval>, val medianMs: Double?,
    val cadencePerMinute: Double?, val variationPercent: Double?,
    val positiveMeanMs: Double?, val negativeMeanMs: Double?, val cycleMeanMs: Double?)
data class AnalysisResult(val raw: List<TimelinePoint>, val timeline: List<TimelinePoint>,
    val quality: List<StreamQuality>, val alignment: String)

object SessionAnalysis {
    /** Candidate onset timing, not measured edge changes or proof of carving. Never merge boots. */
    fun timing(points: List<TimelinePoint>, side: String, axis: Int = 2): TurnTiming {
        val stream = points.filter { it.side == side }.sortedBy { it.timeMs }
        val found = candidates(stream, side, axis)
        val gaps = stream.zipWithNext().filter { (a, b) -> b.timeMs - a.timeMs > 250 }
        val intervals = found.zipWithNext().mapNotNull { (a, b) ->
            val duration = b.startMs - a.startMs
            if (a.positive == b.positive || duration !in 500.0..20_000.0 ||
                b.startMs - a.endMs > 500 ||
                gaps.any { (before, after) -> before.timeMs < b.startMs && after.timeMs > a.startMs }) null
            else CandidateInterval(a.startMs, b.startMs, a.positive)
        }
        val durations = intervals.map { it.durationMs }.sorted()
        val mean = durations.takeIf { it.isNotEmpty() }?.average()
        val median = if (durations.isEmpty()) null else
            (durations[(durations.size - 1) / 2] + durations[durations.size / 2]) / 2
        fun directionMean(positive: Boolean) = intervals.filter { it.positive == positive }
            .map { it.durationMs }.takeIf { it.isNotEmpty() }?.average()
        val cycles = intervals.zipWithNext().mapNotNull { (a, b) ->
            if (a.endMs == b.startMs && a.positive != b.positive) a.durationMs + b.durationMs else null
        }
        return TurnTiming(intervals, median, mean?.let { 60_000 / it },
            if (mean != null && durations.size >= 3)
                sqrt(durations.sumOf { (it - mean).pow(2) } / durations.size) / mean * 100 else null,
            directionMean(true), directionMean(false), cycles.takeIf { it.isNotEmpty() }?.average())
    }

    private fun same(a: SensorSample, b: SensorSample) = a.timestampMs == b.timestampMs &&
        a.accelX == b.accelX && a.accelY == b.accelY && a.accelZ == b.accelZ &&
        a.gyroX == b.gyroX && a.gyroY == b.gyroY && a.gyroZ == b.gyroZ

    fun build(data: SessionData): AnalysisResult {
        val live = mutableListOf<TimelinePoint>()
        for (side in listOf("L", "R")) {
            val records = data.live.filter { it.side == side }
            val segments = mutableListOf<MutableList<Pair<StoredLive, Double>>>()
            var previous: StoredLive? = null
            var wrap = 0.0
            for (record in records) {
                val old = previous
                val regression = old != null && record.sample.timestampMs < old.sample.timestampMs
                val rollover = regression && old!!.sample.timestampMs > 4_200_000 && record.sample.timestampMs < 100_000
                if (rollover) wrap += 4_294_967.296 // Firmware divides a uint32 microsecond clock by 1000.
                if (segments.isEmpty() || (regression && !rollover) ||
                    (old != null && record.receivedAtMs - old.receivedAtMs > 30_000)) {
                    segments.add(mutableListOf()); wrap = 0.0
                }
                segments.last().add(record to (record.sample.timestampMs + wrap))
                previous = record
            }
            for (segment in segments) {
                // Smallest observed transport delay; independent boot clocks remain independent.
                val anchor = segment.minOf { (record, clock) -> record.receivedAtMs - clock }
                segment.forEach { (record, clock) -> live.add(TimelinePoint(side, anchor + clock, record.sample, "live", receivedAtMs = record.receivedAtMs)) }
            }
        }
        val flash = mutableListOf<TimelinePoint>()
        var approximate = false
        for (capture in data.captures) {
            val records = data.flash.filter { it.capture.id == capture.id }.sortedBy { it.index }
            if (records.isEmpty()) continue
            val clocks = mutableListOf<Double>()
            var previous = records.first().record.timestampUs
            var wrap = 0L
            for (stored in records) {
                val us = stored.record.timestampUs
                if (us < previous) wrap += 0x1_0000_0000L
                clocks.add((us + wrap) / 1000.0)
                previous = us
            }
            val sideLive = live.filter { it.side == capture.side }.groupBy { it.sample.timestampMs }
            val anchors = records.mapIndexedNotNull { i, record ->
                sideLive[record.record.sample.timestampMs]?.filter { same(it.sample, record.record.sample) }
                    ?.minByOrNull { abs(it.timeMs - capture.startedAtMs - (clocks[i] - clocks.first())) }
                    ?.let { it.timeMs - clocks[i] }
            }.sorted()
            val anchor = if (anchors.isNotEmpty()) anchors[anchors.size / 2]
                else { approximate = true; capture.startedAtMs - clocks.first() }
            records.forEachIndexed { i, record -> flash.add(TimelinePoint(capture.side, anchor + clocks[i],
                record.record.sample, "flash", capture.id, record.index, record.record.timestampUs)) }
        }
        fun adjust(p: TimelinePoint) = p.copy(timeMs = p.timeMs + if (p.side == "L") data.session.leftOffsetMs else data.session.rightOffsetMs)
        val raw = (live + flash).map(::adjust).sortedBy { it.timeMs }
        val spans = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
        for (capture in data.captures) {
            val points = flash.filter { it.captureId == capture.id }.sortedBy { it.timeMs }
            if (points.isEmpty()) continue
            var first = points.first().timeMs
            var last = first
            for (p in points.drop(1)) {
                if (p.timeMs - last > 30) { spans.getOrPut(capture.side) { mutableListOf() }.add(first to last); first = p.timeMs }
                last = p.timeMs
            }
            spans.getOrPut(capture.side) { mutableListOf() }.add(first to last)
        }
        // Graphs use full-rate flash in its covered intervals. Export always retains both raw sources.
        val effective = (live.filter { p -> spans[p.side].orEmpty().none { p.timeMs >= it.first - 5 && p.timeMs <= it.second + 5 } } + flash)
            .map(::adjust).sortedBy { it.timeMs }
        val end = (data.session.endedAtMs ?: System.currentTimeMillis()).toDouble()
        val duration = max(1.0, end - data.session.startedAtMs)
        val quality = listOf("L", "R").map { side ->
            val points = effective.filter { it.side == side && it.timeMs >= data.session.startedAtMs && it.timeMs <= end }
            var covered = 0.0
            var gaps = 0
            var longest = 0.0
            var cursor = data.session.startedAtMs.toDouble()
            for (point in points) {
                val step = if (point.source == "flash") 10.0 else 20.0
                val gap = point.timeMs - cursor
                if (gap > 100) { gaps++; longest = max(longest, gap) }
                val intervalEnd = min(end, point.timeMs + step)
                covered += max(0.0, intervalEnd - max(cursor, point.timeMs)) + if (gap in 0.0..50.0) gap else 0.0
                cursor = max(cursor, intervalEnd)
            }
            if (end - cursor > 100) { gaps++; longest = max(longest, end - cursor) }
            StreamQuality(side, live.count { it.side == side }, flash.count { it.side == side },
                (covered / duration).coerceIn(0.0, 1.0), gaps, longest,
                data.captures.filter { it.side == side }.sumOf { it.dropped })
        }
        return AnalysisResult(raw, effective, quality, if(data.session.origin=="synthetic")
            "Synthetic sensor clocks and sample timestamps; coverage describes generated input, not a measured Bluetooth connection."
            else if (approximate)
            "Some flash timing is estimated from the phone's start/recovery time. Adjust offsets against video."
            else "Boot clocks aligned to observed live samples; BLE timing is approximate. Adjust offsets against video.")
    }

    /** Experimental gyro lobes, not a validated ski-turn classifier. Axis/sign depend on mounting. */
    fun candidates(points: List<TimelinePoint>, side: String, axis: Int = 2, threshold: Float = 0.35f): List<TurnCandidate> {
        val stream = points.filter { it.side == side }.sortedBy { it.timeMs }
        val results = mutableListOf<TurnCandidate>()
        var start: Double? = null
        var sign = false
        var peak = 0f
        var filtered = 0f
        var previous: Double? = null
        var settledMs = 0.0
        fun finish(end: Double) {
            val began = start ?: return
            val length = end - began
            if (length in 500.0..20_000.0) results.add(TurnCandidate(side, began, end, sign, peak))
            start = null; peak = 0f
        }
        for (point in stream) {
            val dt = previous?.let { point.timeMs - it } ?: 20.0
            if (dt > 250 || dt < 0) { finish(previous ?: point.timeMs); filtered = 0f; settledMs = 0.0 }
            val value = when (axis) { 0 -> point.sample.gyroX; 1 -> point.sample.gyroY; else -> point.sample.gyroZ }
            filtered += (1 - exp(-dt.coerceIn(0.0, 250.0) / 200)).toFloat() * (value - filtered)
            settledMs += dt.coerceIn(0.0, 250.0)
            if (settledMs < 400) { previous = point.timeMs; continue }
            val positive = filtered > 0
            if (start != null && (abs(filtered) < threshold * 0.55f || positive != sign)) finish(point.timeMs)
            if (start == null && abs(filtered) >= threshold) { start = point.timeMs; sign = positive }
            if (start != null) peak = max(peak, abs(filtered))
            previous = point.timeMs
        }
        previous?.let(::finish)
        return results
    }
}
