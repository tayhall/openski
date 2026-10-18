package com.openski.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Session export of the protocol clock sync and the raw magnetometer (documented in docs/clock-sync-design.md and
 * docs/ski-analysis.md). Raw pairs and raw counts are written as stored; fits, residuals and flags are derived by
 * replaying the stored pairs through [ClockSync], so they can be recomputed offline with other settings.
 */
object ClockSyncExport {
    val CLOCK_HEADER = "side,epoch,token,send_ns,recv_ns,sensor_us,rtt_ns,accepted,residual_ns,clock_uncertain\n"
    val MAG_HEADER = "side,sensor_ms,phone_receipt_ms,session_elapsed_ms,raw_x,raw_y,raw_z,gauss_x,gauss_y,gauss_z,flags,near_range,missed_samples,calibration\n"
    private val SIDES = listOf("L", "R")

    /** Wall and elapsed-realtime clocks at recording start, from the `clock_ref` session event; null for old sessions. */
    data class ClockReference(val wallMs: Long, val elapsedNs: Long) {
        fun wallToElapsedNs(wallMs: Long) = (wallMs - this.wallMs) * 1_000_000L + elapsedNs
        fun elapsedToWallMs(ns: Long) = this.wallMs + Math.floorDiv(ns - elapsedNs, 1_000_000L)
    }

    fun reference(events: List<Pair<Long, String>>): ClockReference? = events.firstNotNullOfOrNull { (_, message) ->
        Regex("""^clock_ref wall_ms=(-?\d+) elapsed_ns=(-?\d+)$""").find(message)?.let {
            ClockReference(it.groupValues[1].toLong(), it.groupValues[2].toLong())
        }
    }

    /** Replays each boot's pairs together with live-stream gaps (when the clock reference allows converting them). */
    fun replay(data: SessionData): Map<String, ClockSync> {
        val reference = reference(data.events)
        return SIDES.associateWith { side ->
            val sync = ClockSync(side)
            val pairs = data.clockPairs.filter { it.side == side }
            // Merge pairs and live disturbances by the time they were observed, so a stretch can close at a later pair.
            val items = ArrayList<Pair<Long, () -> Unit>>()
            pairs.forEach { p -> items.add(p.recvNs to { sync.add(PingPair(side, p.token, p.sendNs, p.recvNs, p.sensorUs)); Unit }) }
            if (reference != null && pairs.isNotEmpty()) {
                val monitor = LiveDisturbanceMonitor()
                data.live.filter { it.side == side }.forEach { s ->
                    val ns = reference.wallToElapsedNs(s.receivedAtMs)
                    monitor.onSample(s.sample.sequence, ns)?.let { d -> items.add(d.endNs to { sync.markUncertain(d.startNs, d.endNs, d.reason) }) }
                }
            }
            items.sortedBy { it.first }.forEach { it.second() }
            sync
        }
    }

    fun clockCsv(data: SessionData, syncs: Map<String, ClockSync> = replay(data)): String = buildString {
        append(CLOCK_HEADER)
        val stored = data.clockPairs
        SIDES.forEach { side ->
            val sync = syncs.getValue(side)
            // Pairs that failed the sanity checks are not replayed into the fit but are still raw data: list them by their stored values.
            val accepted = sync.pairs.groupBy { Triple(it.pair.token, it.pair.tSendNs, it.pair.tRecvNs) }
            stored.filter { it.side == side }.forEach { p ->
                val r = accepted[Triple(p.token, p.sendNs, p.recvNs)]?.firstOrNull()
                val mid = p.sendNs + (p.recvNs - p.sendNs) / 2
                append("$side,${r?.epoch ?: ""},${p.token},${p.sendNs},${p.recvNs},${p.sensorUs},${p.rttNs},${p.accepted},${r?.residualNs ?: ""},${sync.isUncertain(mid)}\n")
            }
        }
    }

    fun fitJson(data: SessionData, syncs: Map<String, ClockSync> = replay(data)): JSONObject {
        val reference = reference(data.events)
        val root = JSONObject().put("format_version", 1)
            .put("note", "Protocol clock sync: phone_ns = anchor_phone_ns + (sensor_us - anchor_sensor_us) * 1000 * (1 + drift_ppm * 1e-6). " +
                "Phone times are elapsedRealtimeNanos; sensor times are the boot's esp_timer microseconds. Pairs are in clock-sync.csv; " +
                "this file is the fit recomputed from them. All thresholds are starting values, not validated on hardware.")
            .put("clock_reference", reference?.let { JSONObject().put("wall_ms", it.wallMs).put("elapsed_ns", it.elapsedNs) } ?: JSONObject.NULL)
        SIDES.forEach { side ->
            val sync = syncs.getValue(side)
            val obj = JSONObject().put("pairs", sync.pairs.size).put("accepted_pairs", sync.pairs.count { it.accepted })
            if (sync.pairs.none { it.accepted }) obj.put("available", false).put("reason", "no accepted time-sync pairs (gesture sync only, or not connected)")
            else {
                obj.put("available", true)
                obj.put("epochs", JSONArray().apply {
                    for (e in 0 until sync.epochCount) {
                        val epochPairs = sync.pairs.filter { it.accepted && it.epoch == e }.map { it.pair }
                        val fit = ClockFitter.fit(epochPairs) ?: continue
                        val timeline = sync.timeline(e)
                        put(JSONObject().put("epoch", e).put("pairs", epochPairs.size)
                            .put("anchor_sensor_us", fit.anchorSensorUs).put("anchor_phone_ns", fit.anchorPhoneNs)
                            .put("drift_ppm", fit.driftPpm).put("drift_fitted", fit.slopeSpanNs > 0)
                            .put("best_rtt_ns", fit.bestRttNs).put("median_rtt_ns", medianOf(epochPairs.map { it.rttNs.toDouble() }).toLong())
                            .put("residual_mad_ns", fit.residualMadNs)
                            .put("uncertainty_at_anchor_ns", timeline?.uncertaintyNs(fit.anchorSensorUs) ?: JSONObject.NULL)
                            .put("uncertainty_at_last_pair_ns", timeline?.uncertaintyNs(epochPairs.maxOf { it.sensorUs }) ?: JSONObject.NULL))
                    }
                })
                obj.put("clock_uncertain_stretches", JSONArray().apply {
                    sync.stretches.forEach { s ->
                        put(JSONObject().put("start_ns", s.startNs).put("end_ns", s.endNs ?: JSONObject.NULL).put("reason", s.reason)
                            .put("start_wall_ms", reference?.elapsedToWallMs(s.startNs) ?: JSONObject.NULL)
                            .put("end_wall_ms", s.endNs?.let { reference?.elapsedToWallMs(it) } ?: JSONObject.NULL))
                    }
                })
                obj.put("clock_uncertain_open_at_end", sync.stretches.any { it.endNs == null })
            }
            root.put(side, obj)
        }
        // Boot-to-boot: the right boot's sensor clock minus the left's at the left's last pair, through the phone clock.
        val left = syncs.getValue("L").timeline(); val right = syncs.getValue("R").timeline()
        val lastLeft = syncs.getValue("L").pairs.lastOrNull { it.accepted }?.pair
        root.put("boot_to_boot", if (left == null || right == null || lastLeft == null) JSONObject().put("available", false)
            else BootAlignment.map(left, right, lastLeft.sensorUs).let {
                JSONObject().put("available", true).put("at_left_sensor_us", lastLeft.sensorUs)
                    .put("right_sensor_us", it.value).put("right_minus_left_us", it.value - lastLeft.sensorUs)
                    .put("uncertainty_ns", it.uncertaintyNs)
            })
        return root
    }

    fun magnetometerCsv(data: SessionData): String = buildString {
        append(MAG_HEADER)
        val gauss = MagnetometerFrame.COUNTS_PER_GAUSS
        data.mag.forEach { m ->
            append("${m.side},${m.sensorMs},${m.receiptMs},${m.receiptMs - data.session.startedAtMs},${m.x},${m.y},${m.z},")
            append("${m.x / gauss},${m.y / gauss},${m.z / gauss},${m.flags},${m.flags and 1 != 0},${m.flags and 2 != 0},uncalibrated\n")
        }
    }
}
