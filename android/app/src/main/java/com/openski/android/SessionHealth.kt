package com.openski.android

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-session health report: is this recording good enough to analyse?
 *
 * Computed read-only from the stored samples, one entry per boot and per source (live and flash are never merged).
 * Verdicts are screening aids with starting thresholds, not validated quality limits.
 */
enum class HealthVerdict(val label: String) {
    USABLE("usable"), MARGINAL("marginal"), POOR("poor");
    companion object { fun worst(a: HealthVerdict, b: HealthVerdict) = if (a.ordinal >= b.ordinal) a else b }
}

data class StreamHealth(
    val side: String, val source: String,
    val samples: Int,
    val nominalIntervalMs: Double,
    /** Samples per second over the span covered by continuous segments, gaps included. Null with fewer than two samples. */
    val rateHz: Double?,
    /** Median interval expressed as a rate. */
    val medianRateHz: Double?,
    /** Standard deviation of the intervals that are not gaps, in milliseconds. */
    val intervalSdMs: Double?,
    val gapCount: Int,
    /** Sum of (interval - nominal) over the gaps, in milliseconds. */
    val gapTotalMs: Double,
    val longestGapMs: Double,
    /** Gap time as a share of the continuous span, 0..100. */
    val gapPercent: Double,
    /** Samples flagged by the sensor as following dropped samples (flash records, or live quality bit 16). */
    val droppedFlagCount: Int,
    /** Live samples carrying the firmware's own timestamp-gap flag (quality bit 4). */
    val firmwareGapFlagCount: Int,
    val accelRailPercent: Double,
    val gyroRailPercent: Double,
    /** Sensor clock versus phone receipt time, parts per million (positive: the phone clock runs faster). Live only. */
    val clockSlopePpm: Double?,
    val clockSlopeSpanS: Double,
    val verdict: HealthVerdict,
    val reasons: List<String>,
)

data class BootHealth(val side: String, val streams: List<StreamHealth>, val captureDroppedSamples: Int,
    val verdict: HealthVerdict, val reasons: List<String>)

data class SessionHealthReport(val boots: List<BootHealth>, val verdict: HealthVerdict, val reasons: List<String>) {
    fun toJson(): String = SessionHealth.json(this)
}

object SessionHealth {
    // ---- Starting values. Not validated on snow; tune against real recordings. ----
    const val LIVE_NOMINAL_INTERVAL_MS = 20.0   // live notifications are throttled to 50 Hz
    const val FLASH_NOMINAL_INTERVAL_MS = 10.0  // flash recorder stores the full 100 Hz stream
    const val GAP_FACTOR = 1.5                   // interval over this x nominal is a gap
    const val MIN_SAMPLES = 100                  // fewer than this: poor (nothing to judge)
    const val RATE_SHORTFALL_MARGINAL = 0.05     // effective rate this fraction below nominal
    const val RATE_SHORTFALL_POOR = 0.20
    const val GAP_PERCENT_MARGINAL = 2.0         // gap time as % of continuous span
    const val GAP_PERCENT_POOR = 10.0
    const val DROPPED_PERCENT_MARGINAL = 0.5     // dropped-flag samples as % of samples
    const val DROPPED_PERCENT_POOR = 5.0
    const val RAIL_PERCENT_MARGINAL = 0.5        // near-rail samples (either channel) as % of samples
    const val RAIL_PERCENT_POOR = 5.0
    const val CLOCK_SLOPE_PPM_MARGINAL = 300.0   // |slope|; BLE jitter alone can show ~100 ppm over 10 minutes
    const val CLOCK_SLOPE_PPM_POOR = 2000.0
    const val CLOCK_SLOPE_MIN_SPAN_S = 60.0      // shorter spans are too noisy to report a slope
    /** A live receipt gap longer than this starts a new segment (same rule as [LiveSensorClock]). */
    private const val RECEIPT_GAP_RESET_MS = 30_000L
    const val TEMPERATURE_NOTE = "Not available: neither the live frame nor the flash record carries a temperature."

    fun build(data: SessionData): SessionHealthReport {
        val boots = listOf("L", "R").mapNotNull { side ->
            val streams = listOfNotNull(live(data, side), flash(data, side))
            if (streams.isEmpty()) null else {
                val dropped = data.captures.filter { it.side == side }.sumOf { it.dropped }
                // The best source stands for the boot: graphs prefer flash, and a clean flash copy rescues a gappy live one.
                val best = streams.minByOrNull { it.verdict.ordinal }!!
                BootHealth(side, streams, dropped, best.verdict,
                    if (best.verdict == HealthVerdict.USABLE) emptyList() else best.reasons.map { "${best.source}: $it" })
            }
        }
        if (boots.isEmpty()) return SessionHealthReport(emptyList(), HealthVerdict.POOR, listOf("no samples stored"))
        val verdict = boots.map { it.verdict }.reduce(HealthVerdict::worst)
        return SessionHealthReport(boots, verdict, boots.filter { it.verdict != HealthVerdict.USABLE }
            .flatMap { b -> b.reasons.map { "${b.side} $it" } })
    }

    private fun live(data: SessionData, side: String): StreamHealth? {
        val records = data.live.filter { it.side == side }
        if (records.isEmpty()) return null
        val clock = LiveSensorClock()
        val times = ArrayList<Double>(records.size)
        val segment = IntArray(records.size)
        var seg = 0
        var previous: StoredLive? = null
        records.forEachIndexed { i, r ->
            val old = previous
            if (old != null) {
                val backwards = r.sample.timestampMs < old.sample.timestampMs
                val rollover = backwards && old.sample.timestampMs > 4_200_000 && r.sample.timestampMs < 100_000
                if ((backwards && !rollover) || r.receivedAtMs - old.receivedAtMs > RECEIPT_GAP_RESET_MS) seg++
            }
            segment[i] = seg
            times.add(clock.timestamp(r.sample.timestampMs, r.receivedAtMs))
            previous = r
        }
        val slope = clockSlope(times, records.map { it.receivedAtMs.toDouble() }, segment)
        return stream(side, "live", LIVE_NOMINAL_INTERVAL_MS, records.map { it.sample.quality }, times, segment, slope)
    }

    private fun flash(data: SessionData, side: String): StreamHealth? {
        val times = ArrayList<Double>()
        val quality = ArrayList<Int>()
        val segment = ArrayList<Int>()
        var seg = 0
        for (capture in data.captures.filter { it.side == side }) {
            val records = data.flash.filter { it.capture.id == capture.id }.sortedBy { it.index }
            if (records.isEmpty()) continue
            var prev = records.first().record.timestampUs
            var wrap = 0L
            for (stored in records) {
                val us = stored.record.timestampUs
                if (us < prev) wrap += 0x1_0000_0000L
                prev = us
                times.add((us + wrap) / 1000.0); quality.add(stored.record.sample.quality); segment.add(seg)
            }
            seg++
        }
        if (times.isEmpty()) return null
        return stream(side, "flash", FLASH_NOMINAL_INTERVAL_MS, quality, times, segment.toIntArray(), null)
    }

    /** Span-weighted mean of per-segment least-squares slopes of phone receipt time against reconstructed sensor time. */
    private fun clockSlope(sensor: List<Double>, received: List<Double>, segment: IntArray): Pair<Double, Double>? {
        var weighted = 0.0
        var total = 0.0
        var start = 0
        while (start < sensor.size) {
            var end = start
            while (end + 1 < sensor.size && segment[end + 1] == segment[start]) end++
            val span = (sensor[end] - sensor[start]) / 1000.0
            if (end - start >= 20 && span >= CLOCK_SLOPE_MIN_SPAN_S) {
                val n = end - start + 1
                val mx = (start..end).sumOf { sensor[it] } / n
                val my = (start..end).sumOf { received[it] } / n
                var sxx = 0.0; var sxy = 0.0
                for (i in start..end) { sxx += (sensor[i] - mx) * (sensor[i] - mx); sxy += (sensor[i] - mx) * (received[i] - my) }
                if (sxx > 0) { weighted += (sxy / sxx - 1.0) * 1e6 * span; total += span }
            }
            start = end + 1
        }
        return if (total > 0) weighted / total to total else null
    }

    private fun stream(side: String, source: String, nominal: Double, quality: List<Int>, times: List<Double>,
        segment: IntArray, slope: Pair<Double, Double>?): StreamHealth {
        val gapThreshold = nominal * GAP_FACTOR
        val steady = ArrayList<Double>()
        val all = ArrayList<Double>()
        var gaps = 0; var gapTotal = 0.0; var longest = 0.0; var span = 0.0
        for (i in 1 until times.size) {
            if (segment[i] != segment[i - 1]) continue
            val dt = times[i] - times[i - 1]
            if (dt <= 0) continue
            all.add(dt); span += dt
            if (dt > gapThreshold) { gaps++; gapTotal += dt - nominal; longest = max(longest, dt) } else steady.add(dt)
        }
        val n = quality.size
        val sorted = all.sorted()
        val median = if (sorted.isEmpty()) null else (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val rate = if (span > 0) all.size * 1000.0 / span else null
        val sd = if (steady.size >= 2) { val m = steady.average(); sqrt(steady.sumOf { (it - m) * (it - m) } / steady.size) } else null
        val dropped = quality.count { it and SensorSample.QUALITY_DROPPED_BEFORE != 0 }
        val firmwareGap = quality.count { it and 4 != 0 }
        val accel = 100.0 * quality.count { it and SensorSample.QUALITY_ACCEL_RAIL != 0 } / n
        val gyro = 100.0 * quality.count { it and SensorSample.QUALITY_GYRO_RAIL != 0 } / n
        val rail = 100.0 * quality.count { it and SensorSample.QUALITY_RAIL_MASK != 0 } / n
        val gapPercent = if (span > 0) 100.0 * gapTotal / span else 0.0
        val reasons = mutableListOf<String>()
        var verdict = HealthVerdict.USABLE
        fun flag(level: HealthVerdict, text: String) { verdict = HealthVerdict.worst(verdict, level); reasons.add(text) }
        fun check(value: Double, marginal: Double, poor: Double, text: String) {
            if (value > poor) flag(HealthVerdict.POOR, text) else if (value > marginal) flag(HealthVerdict.MARGINAL, text)
        }
        if (n < MIN_SAMPLES) flag(HealthVerdict.POOR, "only $n samples")
        if (rate != null) {
            val shortfall = 1.0 - rate / (1000.0 / nominal)
            check(shortfall, RATE_SHORTFALL_MARGINAL, RATE_SHORTFALL_POOR, "sample rate ${"%.1f".format(rate)} Hz is below nominal")
        }
        check(gapPercent, GAP_PERCENT_MARGINAL, GAP_PERCENT_POOR, "${"%.1f".format(gapPercent)}% of the time is in gaps")
        check(100.0 * dropped / n, DROPPED_PERCENT_MARGINAL, DROPPED_PERCENT_POOR, "$dropped samples follow dropped samples")
        check(rail, RAIL_PERCENT_MARGINAL, RAIL_PERCENT_POOR, "${"%.1f".format(rail)}% of samples near full scale")
        if (slope != null) check(abs(slope.first), CLOCK_SLOPE_PPM_MARGINAL, CLOCK_SLOPE_PPM_POOR,
            "sensor clock differs from phone clock by ${"%.0f".format(slope.first)} ppm")
        return StreamHealth(side, source, n, nominal, rate, median?.let { 1000.0 / it }, sd, gaps, gapTotal, longest, gapPercent,
            dropped, firmwareGap, accel, gyro, slope?.first, slope?.second ?: 0.0, verdict, reasons)
    }

    // ---- JSON (hand-written so this file stays free of Android types) ----
    private fun num(v: Double?) = if (v == null || v.isNaN() || v.isInfinite()) "null" else "%.4f".format(java.util.Locale.ROOT, v)
    private fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun strings(l: List<String>) = l.joinToString(",", "[", "]") { str(it) }

    internal fun json(report: SessionHealthReport): String {
        val boots = report.boots.joinToString(",") { b ->
            val streams = b.streams.joinToString(",") { s ->
                """{"source":${str(s.source)},"samples":${s.samples},"nominal_interval_ms":${num(s.nominalIntervalMs)},""" +
                    """"rate_hz":${num(s.rateHz)},"median_rate_hz":${num(s.medianRateHz)},"interval_sd_ms":${num(s.intervalSdMs)},""" +
                    """"gap_count":${s.gapCount},"gap_total_ms":${num(s.gapTotalMs)},"longest_gap_ms":${num(s.longestGapMs)},"gap_percent":${num(s.gapPercent)},""" +
                    """"dropped_flag_count":${s.droppedFlagCount},"firmware_gap_flag_count":${s.firmwareGapFlagCount},""" +
                    """"accel_near_rail_percent":${num(s.accelRailPercent)},"gyro_near_rail_percent":${num(s.gyroRailPercent)},""" +
                    """"clock_slope_ppm":${num(s.clockSlopePpm)},"clock_slope_span_s":${num(s.clockSlopeSpanS)},""" +
                    """"verdict":${str(s.verdict.label)},"reasons":${strings(s.reasons)}}"""
            }
            """{"side":${str(b.side)},"capture_dropped_samples":${b.captureDroppedSamples},"verdict":${str(b.verdict.label)},""" +
                """"reasons":${strings(b.reasons)},"streams":[$streams]}"""
        }
        val t = """"thresholds_starting_values":{"gap_factor":$GAP_FACTOR,"min_samples":$MIN_SAMPLES,""" +
            """"rate_shortfall":[$RATE_SHORTFALL_MARGINAL,$RATE_SHORTFALL_POOR],"gap_percent":[$GAP_PERCENT_MARGINAL,$GAP_PERCENT_POOR],""" +
            """"dropped_percent":[$DROPPED_PERCENT_MARGINAL,$DROPPED_PERCENT_POOR],"rail_percent":[$RAIL_PERCENT_MARGINAL,$RAIL_PERCENT_POOR],""" +
            """"clock_slope_ppm":[$CLOCK_SLOPE_PPM_MARGINAL,$CLOCK_SLOPE_PPM_POOR]}"""
        return """{"format_version":1,"verdict":${str(report.verdict.label)},"reasons":${strings(report.reasons)},""" +
            """"temperature":${str(TEMPERATURE_NOTE)},"note":"Screening aid with starting thresholds; not validated. Raw data is unchanged.",$t,"boots":[$boots]}"""
    }

    /** Short lines for the Details tab. */
    fun summaryLines(report: SessionHealthReport): List<String> = buildList {
        add("Overall: ${report.verdict.label}" + if (report.reasons.isEmpty()) "" else " (${report.reasons.first()})")
        report.boots.forEach { b ->
            b.streams.forEach { s ->
                add("${b.side} ${s.source}: ${s.verdict.label} · ${s.rateHz?.let { "%.1f Hz".format(it) } ?: "n/a"} · ${s.gapCount} gaps · " +
                    "rail ${"%.1f".format(max(s.accelRailPercent, s.gyroRailPercent))}%" +
                    (s.clockSlopePpm?.let { " · clock ${"%.0f".format(it)} ppm" } ?: ""))
            }
        }
    }
}
