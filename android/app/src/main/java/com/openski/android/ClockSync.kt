package com.openski.android

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Protocol clock sync (docs/clock-sync-design.md). Pure Kotlin, no Android types.
 *
 * The phone sends recorder-control opcode 09 with a 16-bit token; the boot answers with its esp_timer time in
 * microseconds (64-bit, so no wrap in practice). The phone takes `tSend` just before the write and `tRecv` in the
 * notification callback (both SystemClock.elapsedRealtimeNanos). The sensor time is assumed to correspond to the
 * round-trip midpoint, so queueing asymmetry shows up as an offset error of up to RTT/2: that is why only the
 * lowest-RTT pairs of each burst are used for the fit.
 *
 * Mapping is `phone_ns = a + b * sensor_us`, held as an anchor point plus a drift in ppm so that the 1e15-scale
 * phone clock never has to be added to a tiny slope error in one double. Every threshold below is a starting value
 * to tune on hardware.
 */

/** Starting values for the fit and the drift monitor. */
object ClockSyncDefaults {
    const val MS = 1_000_000L
    const val S = 1_000_000_000L
    /** A pair with a round trip longer than this, or a negative one, is not accepted at all. */
    const val MAX_RTT_NS = 2 * S
    /** Pairs kept in the sliding window for the live fit. */
    const val WINDOW_PAIRS = 24
    /** Pairs closer in time than this belong to one burst; min-RTT filtering is applied inside each burst. */
    const val CLUSTER_GAP_NS = 5 * S
    /** Share of each burst (lowest RTT first) that is kept; at least one pair per burst is always kept. */
    const val KEEP_FRACTION = 0.25
    /** A kept pair whose RTT exceeds REJECT_FACTOR times the best RTT plus REJECT_MARGIN is dropped from the fit. */
    const val REJECT_FACTOR = 3.0
    const val REJECT_MARGIN_NS = 20 * MS
    /** Drift is fitted only from pairs at least this far apart in time. */
    const val MIN_SLOPE_SPAN_NS = 10 * S
    /** Drift is fitted only when the kept pairs span at least this long (starting value; shorter baselines read as zero drift). */
    const val MIN_DRIFT_FIT_SPAN_NS = 150 * S
    /** Crystal drift beyond this is treated as unreliable and the fit is clamped (a real crystal is tens of ppm). */
    const val MAX_DRIFT_PPM = 200.0
    /** Drift monitor: a trusted pair this far from the current fit fires an alarm. */
    const val RESIDUAL_ALARM_NS = 20 * MS
    /** Drift monitor: the fitted slope moving by more than this since the last accepted slope fires an alarm. */
    const val SLOPE_ALARM_PPM = 30.0
    /** The slope alarm is judged only when the fit's slope pairs span at least this long (shorter spans are noise). */
    const val SLOPE_ALARM_MIN_SPAN_NS = 120 * S
    /** Pairs needed in an epoch before the monitor judges a new pair. */
    const val MIN_PAIRS_FOR_MONITOR = 3
    /** A pair is trusted for monitoring when its RTT is within TRUST_FACTOR times the best RTT plus TRUST_MARGIN. */
    const val TRUST_FACTOR = 2.0
    const val TRUST_MARGIN_NS = 30 * MS
    /** Uncertainty growth per second of distance from the nearest good pair. */
    const val UNCERTAINTY_GROWTH_PPM = 30.0
    /** A sensor time that goes backwards by more than this means the boot restarted (new epoch). */
    const val SENSOR_BACKWARDS_SLACK_US = 1_000_000L
    /** Live stream: this many missing samples in a row, or this long a silence, counts as a gap or burst. */
    const val LIVE_GAP_SAMPLES = 5
    const val LIVE_SILENCE_NS = 500 * MS
    /** Live stream: a disturbance stays open for this long after the stream resumes. */
    const val LIVE_RECOVERY_NS = 200 * MS
    /** Ping cadence (docs/clock-sync-design.md). */
    const val BURST_PINGS = 8
    const val BURST_SPACING_MS = 100L
    const val PERIODIC_PING_MS = 60_000L
    /** An extra burst requested by the drift monitor is at most this often per boot. */
    const val MIN_EXTRA_BURST_INTERVAL_MS = 30_000L
}

data class ClockSyncConfig(
    val maxRttNs: Long = ClockSyncDefaults.MAX_RTT_NS,
    val windowPairs: Int = ClockSyncDefaults.WINDOW_PAIRS,
    val clusterGapNs: Long = ClockSyncDefaults.CLUSTER_GAP_NS,
    val keepFraction: Double = ClockSyncDefaults.KEEP_FRACTION,
    val rejectFactor: Double = ClockSyncDefaults.REJECT_FACTOR,
    val rejectMarginNs: Long = ClockSyncDefaults.REJECT_MARGIN_NS,
    val minSlopeSpanNs: Long = ClockSyncDefaults.MIN_SLOPE_SPAN_NS,
    val minDriftFitSpanNs: Long = ClockSyncDefaults.MIN_DRIFT_FIT_SPAN_NS,
    val maxDriftPpm: Double = ClockSyncDefaults.MAX_DRIFT_PPM,
    val residualAlarmNs: Long = ClockSyncDefaults.RESIDUAL_ALARM_NS,
    val slopeAlarmPpm: Double = ClockSyncDefaults.SLOPE_ALARM_PPM,
    val slopeAlarmMinSpanNs: Long = ClockSyncDefaults.SLOPE_ALARM_MIN_SPAN_NS,
    val minPairsForMonitor: Int = ClockSyncDefaults.MIN_PAIRS_FOR_MONITOR,
    val trustFactor: Double = ClockSyncDefaults.TRUST_FACTOR,
    val trustMarginNs: Long = ClockSyncDefaults.TRUST_MARGIN_NS,
    val uncertaintyGrowthPpm: Double = ClockSyncDefaults.UNCERTAINTY_GROWTH_PPM,
)

/** One ping/pong exchange. [sensorUs] is the boot's 64-bit esp_timer reading; phone times are elapsedRealtimeNanos. */
data class PingPair(val boot: String, val token: Int, val tSendNs: Long, val tRecvNs: Long, val sensorUs: Long) {
    val rttNs: Long get() = tRecvNs - tSendNs
    val midpointNs: Long get() = tSendNs + (tRecvNs - tSendNs) / 2
    /** Phone time minus sensor time (both in ns) at the midpoint. */
    val offsetNs: Long get() = midpointNs - sensorUs * 1000L
}

/** `phone_ns = anchorPhoneNs + (sensor_us - anchorSensorUs) * 1000 * (1 + driftPpm * 1e-6)`. */
data class ClockFit(val anchorSensorUs: Long, val anchorPhoneNs: Long, val driftPpm: Double,
    val pairsInWindow: Int, val pairsUsed: Int, val bestRttNs: Long, val residualMadNs: Long,
    /** Time between the first and last used pair; 0 when the drift could not be fitted. */
    val slopeSpanNs: Long, val lastPairNs: Long) {
    private val scale get() = 1000.0 * (1.0 + driftPpm * 1e-6)
    fun toPhoneNs(sensorUs: Long): Long = anchorPhoneNs + ((sensorUs - anchorSensorUs) * scale).roundToLong()
    fun toSensorUs(phoneNs: Long): Double = anchorSensorUs + (phoneNs - anchorPhoneNs) / scale
    /** Half the best round trip (the midpoint assumption) plus the scatter of the used pairs. */
    val baseUncertaintyNs: Long get() = bestRttNs / 2 + residualMadNs
}

internal fun medianOf(values: List<Double>): Double {
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}

object ClockFitter {
    fun isSane(pair: PingPair, config: ClockSyncConfig = ClockSyncConfig()) =
        pair.rttNs in 0..config.maxRttNs && pair.sensorUs >= 0 && pair.tSendNs > 0

    /** Lowest-RTT pairs of each burst, then a global RTT cut. The result is ordered by sensor time. */
    internal fun select(pairs: List<PingPair>, config: ClockSyncConfig): List<PingPair> {
        val byTime = pairs.sortedBy { it.tRecvNs }
        val clusters = mutableListOf<MutableList<PingPair>>()
        for (p in byTime) {
            val last = clusters.lastOrNull()
            if (last != null && p.tSendNs - last.last().tRecvNs <= config.clusterGapNs) last.add(p) else clusters.add(mutableListOf(p))
        }
        val kept = clusters.flatMap { c ->
            c.sortedBy { it.rttNs }.take(max(1, ceil(c.size * config.keepFraction).toInt()))
        }
        val best = kept.minOf { it.rttNs }
        val limit = best * config.rejectFactor + config.rejectMarginNs
        return kept.filter { it.rttNs <= limit }.sortedBy { it.sensorUs }
    }

    /** Robust fit over [window] (assumed to be one boot epoch); null when no pair is usable. */
    fun fit(window: List<PingPair>, config: ClockSyncConfig = ClockSyncConfig()): ClockFit? {
        val good = window.filter { isSane(it, config) }
        if (good.isEmpty()) return null
        val kept = select(good, config)
        val slopes = ArrayList<Double>()
        // Pairs a few tens of seconds apart carry several ms of round-trip jitter each, which is hundreds of ppm over
        // that span and would swing the slope to its clamp; fit drift only once the baseline is long enough.
        val baselineNs = (kept.last().sensorUs - kept.first().sensorUs) * 1000L
        if (baselineNs >= config.minDriftFitSpanNs) for (i in kept.indices) for (j in i + 1 until kept.size) {
            val spanNs = (kept[j].sensorUs - kept[i].sensorUs) * 1000L
            if (spanNs >= config.minSlopeSpanNs) slopes.add((kept[j].offsetNs - kept[i].offsetNs).toDouble() / spanNs)
        }
        val maxDrift = config.maxDriftPpm * 1e-6
        val drift = if (slopes.isEmpty()) 0.0 else medianOf(slopes).coerceIn(-maxDrift, maxDrift)
        val ref = kept[kept.size / 2]
        val adjusted = kept.map { it.offsetNs - drift * (it.sensorUs - ref.sensorUs) * 1000.0 }
        val offset = medianOf(adjusted).roundToLong()
        val fit = ClockFit(ref.sensorUs, ref.sensorUs * 1000L + offset, drift * 1e6, good.size, kept.size,
            kept.minOf { it.rttNs }, 0, if (slopes.isEmpty()) 0 else (kept.last().tSendNs - kept.first().tSendNs),
            kept.maxOf { it.tRecvNs })
        val mad = medianOf(kept.map { abs((it.midpointNs - fit.toPhoneNs(it.sensorUs)).toDouble()) }).roundToLong()
        return fit.copy(residualMadNs = mad)
    }
}

/** A stretch of phone time in which the boot's clock mapping should not be trusted. [endNs] is null while open. */
data class UncertainStretch(val startNs: Long, val endNs: Long?, val reason: String, internal val notBeforeNs: Long = startNs) {
    fun contains(phoneNs: Long) = phoneNs >= startNs && (endNs == null || phoneNs <= endNs)
}

enum class DriftReason(val label: String) {
    RESIDUAL("residual"), SLOPE("slope_change"), LIVE_GAP("live_gap"), LIVE_BURST("live_burst")
}

data class PairResult(val accepted: Boolean, val residualNs: Long?, val events: List<DriftReason>, val newEpoch: Boolean)

data class RecordedPair(val pair: PingPair, val accepted: Boolean, val epoch: Int, val residualNs: Long?)

/** Piecewise-linear map built from per-pair local fits; interpolates across disconnect gaps. */
class ClockTimeline internal constructor(private val anchors: List<Anchor>, private val config: ClockSyncConfig) {
    internal class Anchor(val sensorUs: Long, val phoneNs: Long, val driftPpm: Double, val baseUncertaintyNs: Long, val good: Boolean)

    val anchorCount get() = anchors.size
    val lastDriftPpm get() = anchors.last().driftPpm

    fun toPhoneNs(sensorUs: Long): Long {
        val i = anchors.indexOfFirst { it.sensorUs >= sensorUs }
        if (i == 0) return extrapolate(anchors[0], sensorUs)
        if (i < 0) return extrapolate(anchors.last(), sensorUs)
        val a = anchors[i - 1]; val b = anchors[i]
        if (b.sensorUs == sensorUs) return b.phoneNs
        return a.phoneNs + ((b.phoneNs - a.phoneNs).toDouble() * (sensorUs - a.sensorUs) / (b.sensorUs - a.sensorUs)).roundToLong()
    }

    private fun extrapolate(a: Anchor, sensorUs: Long) = a.phoneNs + ((sensorUs - a.sensorUs) * 1000.0 * (1.0 + a.driftPpm * 1e-6)).roundToLong()

    fun toSensorUs(phoneNs: Long): Double {
        val i = anchors.indexOfFirst { it.phoneNs >= phoneNs }
        if (i == 0) return anchors[0].sensorUs + (phoneNs - anchors[0].phoneNs) / (1000.0 * (1.0 + anchors[0].driftPpm * 1e-6))
        if (i < 0) return anchors.last().let { it.sensorUs + (phoneNs - it.phoneNs) / (1000.0 * (1.0 + it.driftPpm * 1e-6)) }
        val a = anchors[i - 1]; val b = anchors[i]
        if (b.phoneNs == a.phoneNs) return a.sensorUs.toDouble()
        return a.sensorUs + (b.sensorUs - a.sensorUs).toDouble() * (phoneNs - a.phoneNs) / (b.phoneNs - a.phoneNs)
    }

    /** Half the round trip plus scatter of the nearest good pair, plus growth with the distance to it. */
    fun uncertaintyNs(sensorUs: Long): Long {
        val phone = toPhoneNs(sensorUs)
        val good = anchors.filter { it.good }.ifEmpty { anchors }
        val nearest = good.minByOrNull { abs(it.phoneNs - phone) }!!
        return nearest.baseUncertaintyNs + (abs(nearest.phoneNs - phone) * config.uncertaintyGrowthPpm * 1e-6).roundToLong()
    }

    companion object {
        /** [accepted] pairs of one boot epoch; null when there are none. */
        fun build(accepted: List<PingPair>, config: ClockSyncConfig = ClockSyncConfig()): ClockTimeline? {
            val sorted = accepted.filter { ClockFitter.isSane(it, config) }.sortedBy { it.sensorUs }
            if (sorted.isEmpty()) return null
            val half = config.windowPairs / 2
            var floor = Long.MIN_VALUE
            val anchors = sorted.indices.map { i ->
                val fit = ClockFitter.fit(sorted.subList(max(0, i - half), min(sorted.size, i + half + 1)), config)!!
                val good = sorted[i].rttNs <= fit.bestRttNs * config.trustFactor + config.trustMarginNs
                val phone = max(fit.toPhoneNs(sorted[i].sensorUs), floor)   // keep the map monotonic
                floor = phone
                Anchor(sorted[i].sensorUs, phone, fit.driftPpm, fit.baseUncertaintyNs, good)
            }
            // Equal sensor times (a repeated ping) would make interpolation divide by zero.
            val unique = anchors.groupBy { it.sensorUs }.map { it.value.first() }.sortedBy { it.sensorUs }
            return ClockTimeline(unique, config)
        }
    }
}

/** A mapped time with its uncertainty. */
data class Aligned(val value: Double, val uncertaintyNs: Long)

object BootAlignment {
    /** Compose two boots' maps: sensor time of [from] to sensor time of [to] through the shared phone clock. Uncertainties add. */
    fun map(from: ClockTimeline, to: ClockTimeline, fromSensorUs: Long): Aligned {
        val phone = from.toPhoneNs(fromSensorUs)
        val target = to.toSensorUs(phone)
        return Aligned(target, from.uncertaintyNs(fromSensorUs) + to.uncertaintyNs(target.roundToLong()))
    }
}

/**
 * Incremental per-boot sync state: raw pairs, the live fit, the drift monitor and the `clock_uncertain`
 * stretches. Feed pairs in arrival order; the exporter replays stored pairs through the same code.
 */
class ClockSync(val boot: String, private val config: ClockSyncConfig = ClockSyncConfig()) {
    private val recorded = ArrayList<RecordedPair>()
    private val stretchList = ArrayList<UncertainStretch>()
    private var epoch = 0
    private var slopeBaselinePpm: Double? = null

    val pairs: List<RecordedPair> get() = recorded
    val stretches: List<UncertainStretch> get() = stretchList
    val epochCount get() = epoch + 1

    private fun epochPairs(e: Int) = recorded.filter { it.accepted && it.epoch == e }.map { it.pair }

    /** Live fit over the last [ClockSyncConfig.windowPairs] accepted pairs of the current epoch. */
    fun fit(): ClockFit? = ClockFitter.fit(epochPairs(epoch).takeLast(config.windowPairs), config)

    fun timeline(forEpoch: Int = epoch): ClockTimeline? = ClockTimeline.build(epochPairs(forEpoch), config)

    fun isUncertain(phoneNs: Long) = stretchList.any { it.contains(phoneNs) }

    private fun trusted(pair: PingPair, fit: ClockFit) = pair.rttNs <= fit.bestRttNs * config.trustFactor + config.trustMarginNs

    fun add(pair: PingPair): PairResult {
        if (!ClockFitter.isSane(pair, config)) {
            recorded.add(RecordedPair(pair, false, epoch, null))
            return PairResult(false, null, emptyList(), false)
        }
        var newEpoch = false
        val before = epochPairs(epoch)
        if (before.isNotEmpty() && pair.sensorUs < before.maxOf { it.sensorUs } - ClockSyncDefaults.SENSOR_BACKWARDS_SLACK_US) {
            epoch++; newEpoch = true; slopeBaselinePpm = null
        }
        val previous = if (newEpoch) null else fit()
        val enough = previous != null && epochPairs(epoch).size >= config.minPairsForMonitor
        val residual = previous?.let { pair.midpointNs - it.toPhoneNs(pair.sensorUs) }
        val events = ArrayList<DriftReason>()
        val isTrusted = previous != null && trusted(pair, previous)
        if (enough && isTrusted && abs(residual!!) > config.residualAlarmNs) events.add(DriftReason.RESIDUAL)
        recorded.add(RecordedPair(pair, true, epoch, residual))
        val now = fit()
        if (now != null && now.slopeSpanNs >= config.slopeAlarmMinSpanNs) {
            val baseline = slopeBaselinePpm
            if (baseline == null) slopeBaselinePpm = now.driftPpm
            else if (abs(now.driftPpm - baseline) > config.slopeAlarmPpm) { events.add(DriftReason.SLOPE); slopeBaselinePpm = now.driftPpm }
        }
        if (events.isNotEmpty()) {
            val lastGood = before.maxOfOrNull { it.tRecvNs } ?: pair.tSendNs
            openStretch(lastGood, events.first().label, pair.tRecvNs)
        } else if (isTrusted && abs(residual ?: 0) <= config.residualAlarmNs / 2) closeStretch(pair)
        return PairResult(true, residual, events, newEpoch)
    }

    /**
     * Records a live-stream disturbance (sequence gap, silence or the burst after it) between [startNs] and [endNs].
     * The stretch stays open until a good ping after [endNs] confirms the mapping again.
     */
    fun markUncertain(startNs: Long, endNs: Long, reason: DriftReason) = openStretch(startNs, reason.label, endNs)

    private fun openStretch(startNs: Long, reason: String, notBeforeNs: Long) {
        val open = stretchList.indexOfLast { it.endNs == null }
        if (open >= 0) {
            val s = stretchList[open]
            stretchList[open] = s.copy(startNs = min(s.startNs, startNs), notBeforeNs = max(s.notBeforeNs, notBeforeNs),
                reason = if (reason in s.reason.split("+")) s.reason else s.reason + "+" + reason)
        } else stretchList.add(UncertainStretch(startNs, null, reason, notBeforeNs))
    }

    private fun closeStretch(pair: PingPair) {
        val open = stretchList.indexOfLast { it.endNs == null }
        if (open >= 0 && pair.tRecvNs > stretchList[open].notBeforeNs)
            stretchList[open] = stretchList[open].copy(endNs = pair.midpointNs)
    }

    companion object {
        /** Rebuilds the sync state of one boot from stored raw pairs (ordered by receive time). */
        fun replay(boot: String, pairs: List<PingPair>, config: ClockSyncConfig = ClockSyncConfig()) =
            ClockSync(boot, config).also { sync -> pairs.sortedBy { it.tRecvNs }.forEach { sync.add(it) } }
    }
}

/** Schedule for a ping burst: boots interleaved round by round, never one boot's whole burst then the other's. */
object PingPlan {
    data class Slot(val delayMs: Long, val boot: String)
    fun burst(boots: List<String>, count: Int = ClockSyncDefaults.BURST_PINGS, spacingMs: Long = ClockSyncDefaults.BURST_SPACING_MS): List<Slot> {
        if (boots.isEmpty()) return emptyList()
        return (0 until count).flatMap { round ->
            boots.mapIndexed { k, boot -> Slot(round * spacingMs + k * spacingMs / boots.size, boot) }
        }.sortedBy { it.delayMs }
    }
}

/**
 * Watches the live stream for sequence gaps and silences (a stall followed by a burst of late packets).
 * [onSample] returns the disturbed interval when one just ended, in the caller's nanosecond clock.
 */
class LiveDisturbanceMonitor(private val gapSamples: Int = ClockSyncDefaults.LIVE_GAP_SAMPLES,
    private val silenceNs: Long = ClockSyncDefaults.LIVE_SILENCE_NS,
    private val recoveryNs: Long = ClockSyncDefaults.LIVE_RECOVERY_NS) {
    data class Disturbance(val startNs: Long, val endNs: Long, val reason: DriftReason)
    private var lastSeq = -1
    private var lastNs = 0L

    fun reset() { lastSeq = -1 }

    fun onSample(sequence: Int, recvNs: Long): Disturbance? {
        val prevSeq = lastSeq; val prevNs = lastNs
        lastSeq = sequence; lastNs = recvNs
        if (prevSeq < 0) return null
        val missing = ((sequence - prevSeq) and 0xffff) - 1
        // A delta in the upper half of the range is a reordered or repeated frame, not a gap.
        val gap = missing in gapSamples..0x7fff
        val silent = recvNs - prevNs >= silenceNs
        if (!gap && !silent) return null
        return Disturbance(prevNs, recvNs + recoveryNs, if (silent && !gap) DriftReason.LIVE_BURST else DriftReason.LIVE_GAP)
    }
}
