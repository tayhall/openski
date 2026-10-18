package com.openski.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * End-to-end dress rehearsal: a synthetic garden session with known truths goes through the real analysis and
 * [SessionExport], and the unzipped files are compared with the truths. Fault runs check that every defect degrades
 * as designed (refuse or blank, never crash, never silently wrong).
 *
 * Set OPENSKI_DRESS_REHEARSAL_DIR (environment variable or system property) to also write each ZIP there.
 */
class GardenDressRehearsalTest {
    private class Export(val garden: GardenSession, val files: Map<String, String>) {
        val truth get() = garden.truth
        fun json(name: String) = JSONObject(files.getValue(name))
        fun csv(name: String) = Csv.parse(files.getValue(name))
    }

    private class Csv(val header: List<String>, val rows: List<List<String>>) {
        fun col(name: String) = header.indexOf(name).also { require(it >= 0) { "no column $name in $header" } }
        fun doubles(name: String) = col(name).let { c -> rows.map { it[c].toDouble() } }
        fun strings(name: String) = col(name).let { c -> rows.map { it[c] } }
        fun where(name: String, value: String) = col(name).let { c -> Csv(header, rows.filter { it[c] == value }) }
        companion object {
            fun parse(text: String): Csv {
                val lines = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
                val header = lines.first().split(",")
                return Csv(header, lines.drop(1).map { it.split(",") })
            }
        }
    }

    private val expectedEntries = listOf("session.json", "samples.csv", "events.csv", "boot-roll.csv", "labels.csv", "sensor-health.csv",
        "clock-sync.csv", "clock-sync-fit.json", "gesture-sync.json", "magnetometer.csv", "phone-motion.csv", "detected-movements.csv",
        "heading-turns.csv", "turn-metrics.csv", "torso-metrics.csv", "analysis-summary.json", "session-health.json")

    private val outputDir: File? = (System.getProperty("OPENSKI_DRESS_REHEARSAL_DIR") ?: System.getenv("OPENSKI_DRESS_REHEARSAL_DIR"))
        ?.takeIf { it.isNotBlank() }?.let { File(it).also { dir -> dir.mkdirs() } }

    private fun run(name: String, p: GardenParams = GardenParams()): Export {
        val garden = SyntheticGardenSession.build(p)
        val out = ByteArrayOutputStream()
        SessionExport.write(garden.data, out)
        outputDir?.let { File(it, "$name.zip").writeBytes(out.toByteArray()) }
        val files = LinkedHashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            generateSequence { z.nextEntry }.forEach { files[it.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return Export(garden, files).also(::invariants)
    }

    /** What must hold for every export, clean or faulty: all files exist, JSON parses, CSV rows are rectangular, no NaN. */
    private fun invariants(e: Export) {
        for (name in expectedEntries) assertTrue("missing $name in ${e.files.keys}", e.files.containsKey(name))
        for ((name, text) in e.files) {
            if (name.endsWith(".json")) JSONObject(text)
            if (name.endsWith(".csv") && name != "events.csv") {
                val csv = Csv.parse(text)
                csv.rows.forEachIndexed { i, r -> assertEquals("$name row $i width", csv.header.size, r.size) }
            }
            assertFalse("$name contains NaN", Regex("""(^|[,\s:])NaN([,\s}]|$)""").containsMatchIn(text))
            assertFalse("$name contains Infinity", text.contains("Infinity"))
        }
    }

    private fun stream(e: Export, side: String, source: String): JSONObject {
        val boots = e.json("session-health.json").getJSONArray("boots")
        for (i in 0 until boots.length()) {
            val b = boots.getJSONObject(i)
            if (b.getString("side") != side) continue
            val s = b.getJSONArray("streams")
            for (j in 0 until s.length()) if (s.getJSONObject(j).getString("source") == source) return s.getJSONObject(j)
        }
        fail("no $side $source stream in health"); throw IllegalStateException()
    }

    private fun hasStream(e: Export, side: String, source: String) = runCatching { stream(e, side, source) }.isSuccess

    private fun bootCount(e: Export) = e.json("session-health.json").getJSONArray("boots").length()

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)

    // ---------------------------------------------------------------------------------------------------------------
    // Clean session
    // ---------------------------------------------------------------------------------------------------------------

    private val clean by lazy { run("clean") }

    @Test fun cleanExportHasEveryEntryAndConsistentSampleRows() {
        val e = clean
        val t = e.truth
        val samples = e.csv("samples.csv")
        assertEquals(SampleCsv.HEADER.trim().split(","), samples.header)
        val total = t.flashCount.values.sum() + t.liveCount.values.sum()
        assertEquals(total, samples.rows.size)
        for (side in listOf("L", "R")) {
            val s = samples.where("side", side)
            assertEquals(t.flashCount.getValue(side), s.where("source", "flash").rows.size)
            assertEquals(t.liveCount.getValue(side), s.where("source", "live").rows.size)
            val flashQuality = s.where("source", "flash").doubles("quality").map { it.toInt() }
            assertEquals(t.flashDroppedFlags.getValue(side), flashQuality.count { it and SensorSample.QUALITY_DROPPED_BEFORE != 0 })
            assertEquals(t.flashAnyRail.getValue(side), flashQuality.count { it and SensorSample.QUALITY_RAIL_MASK != 0 })
        }
        // 104 Hz flash and 52 Hz live, in the units of the boot's own clock.
        val t104 = samples.where("side", "L").where("source", "flash").doubles("sensor_time_us")
        val rate = (t104.size - 1) / ((t104.last() - t104.first()) / 1e6)
        assertEquals(104.0, rate, 0.5)
        val liveL = samples.where("side", "L").where("source", "live")
        assertTrue("sequence is blank-free for live", liveL.strings("sequence").all { it.isNotEmpty() })
        assertEquals(e.garden.data.captures.size, e.json("session.json").getJSONArray("captures").length())
    }

    @Test fun stompOffsetAndDriftMatchTheTruth() {
        val e = clean
        val g = e.json("gesture-sync.json")
        assertEquals("measured", g.getString("status"))
        val t = e.truth
        val offset = g.getDouble("offset_s")
        val ref = g.getDouble("ref_left_time_s")
        // The start stomp is found where the left clock says it was: sensor seconds at the true stomp time.
        assertEquals(t.sensorSec("L", t.stompStartSecTrue), ref, 0.02)
        assertEquals(t.offsetRightMinusLeft(ref), offset, 0.010)
        val end = g.getJSONObject("end")
        assertEquals(t.sensorSec("L", t.stompEndSecTrue), end.getDouble("left_time_s"), 0.02)
        assertEquals(t.offsetRightMinusLeft(end.getDouble("left_time_s")), end.getDouble("offset_s"), 0.010)
        assertEquals(t.relativeDriftPpm, g.getDouble("drift_ppm"), 25.0)
        assertTrue(g.getDouble("offset_sigma_s") > 0)
        // Ping comparison present and the two methods agree within the protocol's pass line (symmetric delays).
        assertTrue(g.getJSONObject("ping").getBoolean("available"))
        assertEquals(0.0, g.getDouble("stomp_minus_ping_ms"), 15.0)
    }

    @Test fun pingFitMatchesTheTruthWithinAFewMilliseconds() {
        val e = clean
        val t = e.truth
        val fit = e.json("clock-sync-fit.json")
        for (side in listOf("L", "R")) {
            val f = fit.getJSONObject(side)
            assertTrue(f.getBoolean("available"))
            assertEquals(1, f.getJSONArray("epochs").length())
            val epoch = f.getJSONArray("epochs").getJSONObject(0)
            assertTrue("drift fitted over 200 s", epoch.getBoolean("drift_fitted"))
            val anchorUs = epoch.getLong("anchor_sensor_us")
            val errorMs = (epoch.getLong("anchor_phone_ns") - t.truePhoneNsOfSensorUs(side, anchorUs)) / 1e6
            assertEquals("$side anchor error ms", 0.0, errorMs, 5.0)
            assertEquals("$side drift ppm", t.fitDriftPpm(side), epoch.getDouble("drift_ppm"), 30.0)
            assertFalse(f.getBoolean("clock_uncertain_open_at_end"))
        }
        val b2b = fit.getJSONObject("boot_to_boot")
        assertTrue(b2b.getBoolean("available"))
        val leftUs = b2b.getLong("at_left_sensor_us")
        val trueRightUs = (t.offsetRightMinusLeft(leftUs / 1e6) + leftUs / 1e6) * 1e6
        // This is the map at the last (single, one-a-minute) left ping, the worst place for a fit built from sparse pairs:
        // it can be 10 ms off while every pair is within 5 ms. The honest requirement is that the reported uncertainty
        // covers the error and is itself small.
        val b2bErrorMs = (b2b.getDouble("right_sensor_us") - trueRightUs) / 1000.0
        val b2bUncertaintyMs = b2b.getDouble("uncertainty_ns") / 1e6
        assertTrue("boot-to-boot error $b2bErrorMs ms must sit inside the reported $b2bUncertaintyMs ms", abs(b2bErrorMs) <= b2bUncertaintyMs)
        assertTrue("uncertainty $b2bUncertaintyMs ms should stay under 30 ms", b2bUncertaintyMs < 30.0)
        val pairs = e.csv("clock-sync.csv")
        assertEquals(t.pings.values.sumOf { it.size }, pairs.rows.size)
        assertTrue(pairs.doubles("rtt_ns").all { it > 0 })
        assertTrue(pairs.strings("accepted").all { it == "true" })
    }

    @Test fun pingBiasSwitchedOnShowsUpAgainstTheStomp() {
        val symmetric = clean.json("gesture-sync.json").getDouble("stomp_minus_ping_ms")
        val biased = run("fault-asymmetric-ping-bias", GardenParams(asymmetricPingBias = true, leftConnectionIntervalMs = 45.0))
        val g = biased.json("gesture-sync.json")
        assertEquals("measured", g.getString("status"))
        val diff = g.getDouble("stomp_minus_ping_ms")
        // Left rides a 45 ms interval and right a 15 ms one: the kept (lowest-RTT) pairs read the sensor up to half an
        // interval too late on each, so the boot-to-boot ping offset is wrong by roughly half the interval difference
        // and the stomp reads lower than the ping.
        assertTrue("bias should show: clean $symmetric ms, biased $diff ms", diff < 0.0 && diff < symmetric - 6.0)
        assertTrue("and stay below the interval itself: $diff", diff > -25.0)
        // The stomp result does not move with the ping bias.
        assertEquals(biased.truth.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.010)
    }

    @Test fun healthSeesTheInjectedDroppedSamplesGapsAndClipping() {
        val e = clean
        val t = e.truth
        val health = e.json("session-health.json")
        for (side in listOf("L", "R")) {
            val flash = stream(e, side, "flash")
            val live = stream(e, side, "live")
            assertEquals(t.flashCount.getValue(side), flash.getInt("samples"))
            assertEquals(t.flashDroppedFlags.getValue(side), flash.getInt("dropped_flag_count"))
            val boot = (0 until health.getJSONArray("boots").length()).map { health.getJSONArray("boots").getJSONObject(it) }.first { it.getString("side") == side }
            assertEquals(t.flashDroppedSamples.getValue(side), boot.getInt("capture_dropped_samples"))
            // One run of dropped records is one gap of (n + 1) intervals.
            assertEquals(t.flashDroppedFlags.getValue(side), flash.getInt("gap_count"))
            assertEquals(t.flashDroppedSamples.getValue(side) * 1000.0 / 104.0, flash.getDouble("gap_total_ms"), 6.0)
            assertEquals(104.0, flash.getDouble("rate_hz"), 0.6)
            assertEquals(100.0 * t.flashAccelRail.getValue(side) / t.flashCount.getValue(side), flash.getDouble("accel_near_rail_percent"), 0.001)
            assertEquals(100.0 * t.flashGyroRail.getValue(side) / t.flashCount.getValue(side), flash.getDouble("gyro_near_rail_percent"), 0.001)
            assertEquals(t.liveGapRuns.getValue(side), live.getInt("gap_count"))
            assertEquals(t.liveCount.getValue(side), live.getInt("samples"))
            assertEquals(52.0 * (1 - 0.005), live.getDouble("rate_hz"), 1.0)
            // Sensor clock against the phone: the boot's own ppm error with the opposite sign (the phone sees it as slope).
            val ppm = if (side == "L") t.p.leftSkewPpm else t.p.rightSkewPpm
            assertEquals("$side live clock slope", -ppm, live.getDouble("clock_slope_ppm"), 20.0)
            assertEquals("usable", flash.getString("verdict"))
            assertEquals("usable", live.getString("verdict"))
        }
        assertEquals("usable", health.getString("verdict"))
        val quality = e.json("analysis-summary.json").getJSONArray("quality")
        for (i in 0 until quality.length()) {
            val q = quality.getJSONObject(i)
            val side = q.getString("side")
            assertEquals(t.flashDroppedFlags.getValue(side), q.getInt("dropped_before_samples"))
            assertTrue(q.getDouble("rail_percent") < 0.5)
        }
    }

    @Test fun heavyClippingTurnsTheHealthVerdictPoor() {
        val e = run("fault-heavy-clipping", GardenParams(accelClipShare = 0.08, gyroClipShare = 0.04))
        val flash = stream(e, "L", "flash")
        assertEquals("poor", flash.getString("verdict"))
        assertTrue(flash.getJSONArray("reasons").toString(), flash.getJSONArray("reasons").toString().contains("full scale"))
        assertEquals("poor", e.json("session-health.json").getString("verdict"))
        val t = e.truth
        assertEquals(100.0 * t.flashAccelRail.getValue("L") / t.flashCount.getValue("L"), flash.getDouble("accel_near_rail_percent"), 0.001)
        assertTrue(flash.getDouble("accel_near_rail_percent") > 5.0)
    }

    private class Detected(val side: String, val direction: String, val startMs: Double, val peak: Double, val usable: Boolean, val durationMs: Double)

    private fun detected(e: Export, side: String): List<Detected> {
        val csv = e.csv("turn-metrics.csv").where("side", side)
        val c = csv
        return csv.rows.map { r ->
            Detected(side, r[c.col("direction")], r[c.col("start_ms")].toDouble(), r[c.col("peak_estimated_boot_roll_deg")].toDouble(),
                r[c.col("usable")] == "true", r[c.col("duration_ms")].toDouble())
        }.sortedBy { it.startMs }
    }

    @Test fun turnMetricsFindEveryInjectedHalfTurn() {
        val e = clean
        val t = e.truth
        assertEquals(20, t.turns.size)
        for (side in listOf("L", "R")) {
            val found = detected(e, side)
            assertEquals("$side half-turns found", t.turns.size, found.size)
            val lag = if (side == "R") t.p.rightLagSec else 0.0
            t.turns.zip(found).forEachIndexed { i, (truthTurn, d) ->
                assertEquals("$side turn $i direction", if (truthTurn.signedPeakDeg > 0) "LEFT" else "RIGHT", d.direction)
                // The detector fires once the filtered roll passes 8 degrees, so it starts after the turn does, never before it.
                val delayMs = d.startMs - t.wallMs(truthTurn.startSec + lag)
                assertTrue("$side turn $i start delay $delayMs ms", delayMs > -60 && delayMs < 900)
                assertEquals("$side turn $i peak", abs(truthTurn.signedPeakDeg), d.peak, 3.0)
                assertTrue("$side turn $i duration ${d.durationMs}", d.durationMs > 0.3 * truthTurn.durationSec * 1000 && d.durationMs < truthTurn.durationSec * 1000 + 300)
            }
            assertTrue("$side usable turns: ${found.count { it.usable }}", found.count { it.usable } >= 18)
        }
        val block = e.json("analysis-summary.json").getJSONObject("turn_metrics")
        assertEquals(20, block.getJSONObject("per_boot").getJSONObject("L").getInt("candidates"))
        assertEquals(20, block.getJSONObject("per_boot").getJSONObject("R").getInt("candidates"))
        assertEquals(20, e.json("analysis-summary.json").getJSONObject("L").getInt("completed_movements"))
    }

    @Test fun theLeftRightLagShowsInTheAgreementMetrics() {
        val e = clean
        val comparison = e.json("analysis-summary.json").getJSONObject("boot_comparison")
        assertEquals(20, comparison.getInt("movement_pairs"))
        // Right lags by 60 ms. The two boots are aligned to the phone with no drift correction, so the figure can be a
        // few milliseconds off (54 ppm of relative skew is about 10 ms over the run).
        assertEquals(60.0, comparison.getDouble("median_right_minus_left_onset_ms"), 25.0)
        val agreement = e.json("analysis-summary.json").getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        assertEquals(20, agreement.getInt("pairs"))
        assertTrue("pairs with data ${agreement.getInt("pairs_with_data")}", agreement.getInt("pairs_with_data") >= 18)
        val aligned = run("clean-no-lag", GardenParams(rightLagSec = 0.0))
        val alignedAgreement = aligned.json("analysis-summary.json").getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        val alignedOnset = aligned.json("analysis-summary.json").getJSONObject("boot_comparison").getDouble("median_right_minus_left_onset_ms")
        assertEquals("the lag moves the onset difference by the injected amount", 60.0, comparison.getDouble("median_right_minus_left_onset_ms") - alignedOnset, 15.0)
        assertTrue("derivative similarity falls with a lag: no lag ${alignedAgreement.getDouble("mean_derivative_similarity")}, lag ${agreement.getDouble("mean_derivative_similarity")}",
            alignedAgreement.getDouble("mean_derivative_similarity") > agreement.getDouble("mean_derivative_similarity"))
        assertTrue("both boots lean the same way at the same moment, so the wedge fraction is small",
            agreement.getDouble("mean_wedge_fraction") < 0.15)
    }

    /** The ping-only uncertainty of the no-end-stomp run (ping bound plus drift beyond the references), quoted in the docs. */
    private val NO_END_UNCERTAINTY_MS = 14.0..21.0

    private fun alignment(e: Export, block: String = "boot_comparison"): JSONObject {
        val s = e.json("analysis-summary.json")
        val b = if (block == "boot_comparison") s.getJSONObject(block) else s.getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        return b.getJSONObject("clock_alignment")
    }

    @Test fun alignmentRemovesAnInjectedRightTimelineBiasFromTheOnsetLag() {
        // Every right live notification arrives 40 ms later: the right boot's timeline is 40 ms late, the clocks are not.
        val e = run("fault-right-timeline-late", GardenParams(rightLiveExtraDelaySec = 0.040))
        val t = e.truth
        val cmp = e.json("analysis-summary.json").getJSONObject("boot_comparison")
        val a = cmp.getJSONObject("clock_alignment")
        assertEquals(a.getString("reason"), "stomp", a.getString("source"))
        assertTrue(a.getBoolean("applied"))
        assertFalse(a.getBoolean("disagreement"))
        // The figures docs/clock-sync-design.md quotes: the shift is the clean session's own (about -4 ms) minus the 40 ms.
        val cleanShift = alignment(clean).getDouble("shift_ms")
        assertEquals(-40.0, a.getDouble("shift_ms") - cleanShift, 2.0)
        assertEquals(-44.0, a.getDouble("shift_ms"), 3.0)
        assertTrue(a.toString(), a.getDouble("uncertainty_ms") in 12.0..25.0)
        val trueLag = t.p.rightLagSec * 1000
        val aligned = cmp.getDouble("median_right_minus_left_onset_ms")
        val unaligned = cmp.getDouble("unaligned_median_right_minus_left_onset_ms")
        assertEquals("unaligned carries the 40 ms bias", trueLag + 40.0, unaligned, 4.0)
        assertTrue("aligned $aligned ms is closer to the true $trueLag ms lag than unaligned $unaligned ms",
            abs(aligned - trueLag) < abs(unaligned - trueLag))
        assertEquals(trueLag, aligned, 3.0)
        // The same alignment object is in the turn-metric agreement block, with the per-pair timing flags.
        val ba = e.json("analysis-summary.json").getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        assertEquals(a.getDouble("shift_ms"), ba.getJSONObject("clock_alignment").getDouble("shift_ms"), 0.0)
        assertEquals(a.getDouble("uncertainty_ms"), ba.getDouble("timing_uncertainty_ms"), 0.0)
        assertFalse(ba.isNull("pairs_timing_limited"))
        val pair = ba.getJSONArray("per_pair").getJSONObject(0)
        assertTrue(pair.has("timing_limited") && pair.has("timing_sensitivity"))
        // Aligned agreement matches the clean session's (no timeline bias) within a little.
        val clean = clean.json("analysis-summary.json").getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        assertEquals(clean.getDouble("mean_derivative_similarity"), ba.getDouble("mean_derivative_similarity"), 0.02)
        // Raw rows and the existing boot-roll columns are untouched; the new last column carries the left-timeline time.
        val roll = e.csv("boot-roll.csv")
        assertEquals("aligned_to_left_time_ms", roll.header.last())
        val right = roll.where("side", "R")
        val shift = right.doubles("aligned_to_left_time_ms").zip(right.doubles("aligned_time_ms")).map { (x, y) -> x - y }
        // The shift is shift_ms at ref_time_ms and changes by drift_ppm across the session.
        val meanRight = right.doubles("aligned_time_ms").average()
        val expected = a.getDouble("shift_ms") - a.getDouble("drift_ppm") * 1e-6 * (meanRight - a.getDouble("ref_time_ms"))
        assertEquals(expected, shift.average(), 1.0)
        val left = roll.where("side", "L")
        assertEquals(left.doubles("aligned_time_ms"), left.doubles("aligned_to_left_time_ms"))
    }

    @Test fun theAlignmentSourceFollowsWhatTheSessionCanSupport() {
        val c = alignment(clean)
        assertEquals(c.getString("reason"), "stomp", c.getString("source"))
        assertTrue(c.getBoolean("applied"))
        assertEquals(c.getDouble("shift_ms"), alignment(clean, "agreement").getDouble("shift_ms"), 0.0)
        assertTrue(c.getString("ping_bias_note").contains("half a connection interval"))
        // The clean session has no injected timeline bias, so the shift is small (anchor rounding and drift only).
        assertEquals(-4.0, c.getDouble("shift_ms"), 2.0)
        assertEquals(c.toString(), 18.0, c.getDouble("uncertainty_ms"), 3.0)
        val noEnd = alignment(run("fault-no-end-stomp", GardenParams(leftEndStomp = false, rightEndStomp = false)))
        assertEquals(noEnd.getString("reason"), "ping", noEnd.getString("source"))
        assertTrue(noEnd.getString("reason").contains("no clear stomp"))
        assertTrue(noEnd.toString(), noEnd.getDouble("uncertainty_ms") in NO_END_UNCERTAINTY_MS)
        val noPings = alignment(run("fault-no-pings", GardenParams(includePings = false)))
        assertEquals("stomp", noPings.getString("source"))
        assertTrue(noPings.isNull("stomp_minus_ping_ms"))
        val reset = run("fault-clock-reset", GardenParams(leftClockResetAtSec = 100.0))
        val r = alignment(reset)
        assertEquals("none", r.getString("source"))
        assertFalse(r.getBoolean("applied"))
        assertTrue(r.isNull("shift_ms"))
        assertTrue(reset.json("analysis-summary.json").getJSONObject("boot_comparison").isNull("onset_lag_timing_limited"))
        assertTrue(reset.csv("boot-roll.csv").strings("aligned_to_left_time_ms").all { it.isEmpty() })
        val noRight = alignment(run("fault-no-right-boot", GardenParams(includeRightBoot = false)))
        assertEquals("none", noRight.getString("source"))
        // The generator's ping bias: each boot's kept pings read the sensor up to half its connection interval late, so the
        // boot-to-boot ping offset is off by at most half the interval difference, (45 - 15) / 2 = 15 ms. The left return
        // leg alone is one 45 ms interval, so the left ping uncertainty (half the best round trip) is at least 22.5 ms and
        // the tolerance is larger than any bias this generator can make: the rule must keep the stomp, which is the truth.
        val biasedRun = run("fault-asymmetric-ping-bias", GardenParams(asymmetricPingBias = true, leftConnectionIntervalMs = 45.0))
        val biased = alignment(biasedRun)
        assertEquals(biased.getString("reason"), "stomp", biased.getString("source"))
        assertFalse(biased.getBoolean("disagreement"))
        assertTrue(biased.toString(), biased.getDouble("disagreement_tolerance_ms") >= 22.5)
        assertTrue(biased.toString(), biased.getDouble("stomp_minus_ping_ms") < 0 && biased.getDouble("stomp_minus_ping_ms") > -15.0)
        // The bias does not move the applied shift: it matches the clean session's stomp shift.
        assertEquals(c.getDouble("shift_ms"), biased.getDouble("shift_ms"), 3.0)
    }

    @Test fun bootsUpForHoursWithDifferentWrapCountsStillAlignInThePingFrame() {
        // The left boot has wrapped its 32-bit microsecond clock 3 times before the recording, the right one 5 times, so
        // live and flash stamps sit whole wraps below the 64-bit ping clock, by a different number of wraps per boot.
        val wrap = 4294.967296
        val e = run("fault-long-uptime", GardenParams(leftClockStartSec = 3 * wrap + 500.0, rightClockStartSec = 5 * wrap + 1800.0))
        val t = e.truth
        val g = e.json("gesture-sync.json")
        assertEquals(g.optString("reason"), "measured", g.getString("status"))
        // The stomp is reported in the ping (absolute) frame, so it matches the truth and the pings.
        assertEquals(t.sensorSec("L", t.stompStartSecTrue), g.getDouble("ref_left_time_s"), 0.02)
        assertEquals(t.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.010)
        assertEquals(0.0, g.getDouble("stomp_minus_ping_ms"), 15.0)
        val a = alignment(e)
        assertEquals(a.getString("reason"), "stomp", a.getString("source"))
        assertFalse(a.getBoolean("disagreement"))
        assertEquals(0.0, a.getDouble("stomp_minus_ping_ms"), 15.0)
        assertEquals(0.0, a.getDouble("stomp_minus_ping_end_ms"), 15.0)
        // Same transport biases as the clean session, so the same small shift and the same onset lag.
        assertEquals(alignment(clean).getDouble("shift_ms"), a.getDouble("shift_ms"), 3.0)
        assertEquals(t.p.rightLagSec * 1000, e.json("analysis-summary.json").getJSONObject("boot_comparison").getDouble("median_right_minus_left_onset_ms"), 3.0)
    }

    @Test fun phoneRowsHaveTheRightCountAndScale() {
        val e = clean
        val t = e.truth
        val text = e.files.getValue("phone-motion.csv")
        assertTrue(text.contains("offset_ms="))
        val csv = e.csv("phone-motion.csv")
        assertEquals(PhoneMotionExport.COLUMNS.trim().split(","), csv.header)
        assertEquals(t.phoneRows, csv.rows.size)
        assertEquals(t.p.durationSec * 100, csv.rows.size.toDouble(), 3.0)
        val ns = csv.doubles("phone_ns")
        assertEquals(100.0, (ns.size - 1) / ((ns.last() - ns.first()) / 1e9), 0.5)
        assertEquals(t.phoneClockCheckOffsetMs, PhoneMotionExport.clockOffsetMs(e.garden.data.events)!!, 1e-3)   // event text has 3 decimals
        assertEquals(0.0, t.phoneClockCheckOffsetMs, 1.0)
        // The elapsed column places a row where the phone clock says it was, measured from the recording start.
        val elapsed = csv.doubles("session_elapsed_ms")
        assertEquals(10_000.0, elapsed[1000], 5.0)
        assertEquals(t.p.durationSec * 1000, elapsed.last(), 30.0)
        val accelNorm = csv.rows.indices.map { i -> sqrt(listOf("accel_x_mps2", "accel_y_mps2", "accel_z_mps2").sumOf { csv.doubles(it)[i].let { v -> v * v } }) }
        assertEquals(9.81, accelNorm.average(), 0.2)
        val gyro = csv.doubles("gyro_x_radps")
        assertTrue("gyro noise scale", gyro.map { abs(it) }.max() < 0.1)
        val q = csv.rows.indices.map { i -> sqrt(listOf("quat_w", "quat_x", "quat_y", "quat_z").sumOf { csv.doubles(it)[i].let { v -> v * v } }) }
        assertEquals(1.0, q.average(), 1e-3)
    }

    private fun torso(e: Export) = e.json("analysis-summary.json").getJSONObject("torso_metrics")

    @Test fun cleanTorsoMetricsAreComputedWithPingTimingAndNoInventedYaw() {
        val e = clean
        val tm = torso(e)
        assertEquals(tm.getString("reason"), "computed", tm.getString("status"))
        assertTrue(tm.getBoolean("experimental"))
        assertTrue(tm.getString("placement_note").contains("unverified"))
        val csv = e.csv("torso-metrics.csv")
        assertEquals(TorsoMetricsExport.HEADER, csv.header)
        assertEquals("one row per boot candidate", 40, csv.rows.size)
        assertTrue(csv.doubles("phone_coverage_fraction").all { it > 0.95 })
        // Each boot's live timeline sits about 18 to 20 ms after the true sample time (the generator's minimum transport
        // delay); the pings measure that bias, so it is removed and the uncertainty is the ping uncertainty.
        for (side in listOf("L", "R")) {
            val t = tm.getJSONObject("timing").getJSONObject(side)
            assertEquals("ping", t.getString("source"))
            assertEquals("$side bias", 19.0, t.getDouble("bias_removed_ms"), 6.0)
            assertTrue(t.toString(), t.getDouble("max_uncertainty_ms") < 25.0)
        }
        assertTrue(csv.strings("timing_source").all { it == "ping" })
        // The default phone track only sways about the vertical against a boot that does not turn: yaw figures stay null.
        val yaw = tm.getJSONObject("per_boot").getJSONObject("L").getJSONObject("yaw")
        assertEquals(0, yaw.getInt("turns"))
        assertTrue(yaw.isNull("sign_agreement_fraction"))
        assertTrue(csv.strings("torso_to_boot_heading_ratio").all { it.isEmpty() })
        assertTrue(tm.getJSONObject("quietness").getDouble("run_gyro_rms_dps") < 3.0)
    }

    @Test fun torsoMetricsMatchTheTorsoTruth() {
        val m = GardenTorso()
        val e = run("torso", GardenParams(torso = m, bootHeadingPerTurnDeg = 40.0))
        val tm = torso(e)
        assertEquals(tm.getString("reason"), "computed", tm.getString("status"))
        val csv = e.csv("torso-metrics.csv")
        assertEquals(40, csv.rows.size)
        assertEquals(40, tm.getInt("rows_with_metrics"))
        for (side in listOf("L", "R")) {
            val b = tm.getJSONObject("per_boot").getJSONObject(side)
            val lean = b.getJSONObject("lean_vs_roll")
            // The torso leans 0.4 of the left boot's roll; the right boot repeats it 60 ms later, so it sees the same ratio.
            assertEquals("$side lean ratio", m.leanGain, lean.getJSONObject("lean_to_roll_ratio").getDouble("mean"), 0.06)
            assertTrue("$side lean correlation ${lean.getDouble("correlation_across_turns")}", lean.getDouble("correlation_across_turns") > 0.9)
            val yaw = b.getJSONObject("yaw")
            assertEquals(20, yaw.getInt("turns"))
            assertEquals("$side heading ratio", m.yawGain, yaw.getJSONObject("torso_to_boot_heading_ratio").getDouble("mean"), 0.1)
            assertEquals(1.0, yaw.getDouble("sign_agreement_fraction"), 0.0)
            assertTrue(yaw.getJSONObject("yaw_rate_correlation").getDouble("mean") > 0.8)
            assertTrue(yaw.getDouble("heading_change_correlation_across_turns") > 0.8)
        }
        val rows = csv.where("side", "L")
        rows.doubles("torso_lean_peak_deg").zip(rows.doubles("boot_roll_peak_deg")).forEachIndexed { i, (lean, roll) ->
            assertEquals("turn $i lean", m.leanGain * roll, lean, 0.15 * m.leanGain * roll + 1.0)
        }
        // Signed heading: the torso turns the same way as the boot, by about half.
        rows.doubles("torso_heading_change_deg").zip(rows.doubles("boot_heading_change_deg")).forEach { (torso, boot) ->
            assertTrue("same sign: torso $torso, boot $boot", torso * boot > 0)
        }
        assertTrue("timing-limited rows ${tm.getInt("rows_timing_limited")}", tm.getInt("rows_timing_limited") <= 4)
        // Raw phone rows are exported as stored.
        assertEquals(e.truth.phoneRows, e.csv("phone-motion.csv").rows.size)
    }

    @Test fun noPhoneOrNoClockCheckRefusesTheTorsoMetricsWithAReason() {
        val e = run("fault-no-phone", GardenParams(includePhone = false))
        val tm = torso(e)
        assertEquals("refused", tm.getString("status"))
        assertTrue(tm.getString("reason"), tm.getString("reason").contains("no phone motion rows"))
        assertEquals(0, e.csv("torso-metrics.csv").rows.size)
        assertEquals(TorsoMetricsExport.HEADER, e.csv("torso-metrics.csv").header)
        val unchecked = run("fault-phone-clock-unchecked", GardenParams(phoneClockCheck = false))
        val u = torso(unchecked)
        assertEquals("refused", u.getString("status"))
        assertTrue(u.getString("reason"), u.getString("reason").contains("phone_clock_check"))
        assertEquals(0, unchecked.csv("torso-metrics.csv").rows.size)
        assertTrue(unchecked.csv("phone-motion.csv").rows.isNotEmpty())
    }

    @Test fun magnetometerRowsHaveTheRightCountAndScale() {
        val e = clean
        val t = e.truth
        val csv = e.csv("magnetometer.csv")
        assertEquals(t.magRows, csv.rows.size)
        assertEquals(t.p.durationSec * 10, csv.rows.size.toDouble(), 3.0)
        assertTrue("left boot only", csv.strings("side").all { it == "L" })
        assertEquals(MagnetometerFrame.COUNTS_PER_GAUSS, csv.doubles("raw_x")[0] / csv.doubles("gauss_x")[0], 1e-6)
        val gx = csv.doubles("gauss_x"); val gy = csv.doubles("gauss_y"); val gz = csv.doubles("gauss_z")
        val hard = t.hardIronGauss
        // The circle's centre is the hard-iron offset.
        assertEquals(hard.first, (gx.max() + gx.min()) / 2, 0.01)
        assertEquals(hard.second, (gy.max() + gy.min()) / 2, 0.01)
        assertEquals(hard.third + t.magVerticalGauss, gz.average(), 0.01)
        val horizontal = gx.indices.map { hypot(gx[it] - hard.first, gy[it] - hard.second) }
        assertEquals(t.magHorizontalGauss, horizontal.average(), 0.01)
        val ms = csv.doubles("sensor_ms")
        assertEquals(10.0, (ms.size - 1) / ((ms.last() - ms.first()) / 1000.0), 0.1)
        assertTrue(csv.strings("near_range").all { it == "false" })
        assertTrue(csv.strings("calibration").all { it == "uncalibrated" })
        assertEquals(0.05 * 1000 + 25, csv.doubles("session_elapsed_ms")[0], 12.0)
    }

    @Test fun eventsAndSessionJsonCarryWhatTheRecorderWrites() {
        val e = clean
        val events = e.files.getValue("events.csv")
        assertTrue(events.contains("clock_ref wall_ms=${e.truth.wallMs(0.0)} elapsed_ns=${e.truth.phoneNs(0.0)}"))
        assertTrue(events.contains("phone_clock_check offset_ms="))
        assertTrue(events.contains("L sensor flash recording started"))
        assertEquals(ClockSyncExport.reference(e.garden.data.events)!!.elapsedNs, e.truth.phoneNs(0.0))
        val meta = e.json("session.json")
        assertEquals(e.garden.data.session.id, meta.getString("session_id"))
        assertEquals(e.garden.data.captures.sumOf { it.expected }, (0 until meta.getJSONArray("captures").length()).sumOf { meta.getJSONArray("captures").getJSONObject(it).getInt("expected_samples") })
        assertEquals(e.truth.p.leftClockStartSec - e.truth.p.rightClockStartSec, -3.7, 1e-9)
        val health = e.csv("sensor-health.csv")
        assertEquals(2 * 36, health.rows.size)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fault runs
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun aMissingEndStompIsRefusedNotGuessed() {
        val e = run("fault-no-end-stomp", GardenParams(leftEndStomp = false, rightEndStomp = false))
        val g = e.json("gesture-sync.json")
        assertEquals("refused", g.getString("status"))
        assertEquals("no_transient", g.getString("reject"))
        assertFalse(g.has("offset_s"))
        assertFalse(g.has("drift_ppm"))
        // The ping side is untouched and still reported.
        assertTrue(g.getJSONObject("ping").getBoolean("available"))
        assertTrue(e.json("clock-sync-fit.json").getJSONObject("boot_to_boot").getBoolean("available"))
        assertEquals(20, detected(e, "L").size)
    }

    @Test fun aStompMissingOnOneBootIsRefused() {
        val e = run("fault-stomp-one-boot", GardenParams(rightEndStomp = false))
        val g = e.json("gesture-sync.json")
        assertEquals("refused", g.getString("status"))
        assertFalse(g.has("offset_s"))
    }

    @Test fun aBootClockResetMidSessionIsNotMixedUp() {
        val e = run("fault-clock-reset", GardenParams(leftClockResetAtSec = 100.0))
        val g = e.json("gesture-sync.json")
        assertEquals("clock_reset", g.getString("status"))
        assertTrue(g.getString("reason").contains("clock"))
        assertFalse(g.has("offset_s"))
        // The ping side sees a new epoch on the left boot only.
        val fit = e.json("clock-sync-fit.json")
        assertEquals(2, fit.getJSONObject("L").getJSONArray("epochs").length())
        assertEquals(1, fit.getJSONObject("R").getJSONArray("epochs").length())
        // The disconnect is a hole in the left coverage, visible in the session quality figures.
        val quality = e.json("session.json").getJSONArray("quality")
        val left = (0 until quality.length()).map { quality.getJSONObject(it) }.first { it.getString("side") == "L" }
        assertTrue("longest left gap ${left.getDouble("longest_gap_ms")}", left.getDouble("longest_gap_ms") >= 4000.0)
        // Turns before the reset are still found on the left; none is invented, and the right boot is complete.
        val l = detected(e, "L")
        assertTrue("left turns ${l.size}", l.size in 12..20)
        assertEquals(20, detected(e, "R").size)
        val resetWall = e.truth.wallMs(100.0).toDouble()
        assertTrue(l.none { it.startMs > resetWall && it.startMs < e.truth.wallMs(105.0) })
    }

    @Test fun aMissingRightBootLeavesTheLeftBootAnalysedAndTheRestBlank() {
        val e = run("fault-no-right-boot", GardenParams(includeRightBoot = false))
        val g = e.json("gesture-sync.json")
        assertEquals("missing_boot", g.getString("status"))
        assertEquals(1, bootCount(e))
        assertFalse(hasStream(e, "R", "flash"))
        assertEquals(0, detected(e, "R").size)
        assertEquals(20, detected(e, "L").size)
        val fit = e.json("clock-sync-fit.json")
        assertFalse(fit.getJSONObject("R").getBoolean("available"))
        assertFalse(fit.getJSONObject("boot_to_boot").getBoolean("available"))
        assertEquals(0, e.json("analysis-summary.json").getJSONObject("boot_comparison").getInt("movement_pairs"))
        val agreement = e.json("analysis-summary.json").getJSONObject("turn_metrics").getJSONObject("boot_agreement")
        assertEquals(0, agreement.getInt("pairs"))
        assertTrue(agreement.isNull("mean_derivative_similarity"))
        assertEquals(0, e.json("session.json").getJSONArray("quality").getJSONObject(1).getInt("flash_samples"))
    }

    @Test fun noPhoneLeavesAnHonestEmptyFile() {
        val e = run("fault-no-phone", GardenParams(includePhone = false))
        val text = e.files.getValue("phone-motion.csv")
        assertTrue(text.contains("no phone_clock_check event was recorded"))
        assertEquals(0, e.csv("phone-motion.csv").rows.size)
        assertEquals("measured", e.json("gesture-sync.json").getString("status"))
    }

    @Test fun noMagnetometerLeavesHeaderOnly() {
        val e = run("fault-no-magnetometer", GardenParams(includeMagnetometer = false))
        assertEquals(0, e.csv("magnetometer.csv").rows.size)
        assertEquals(ClockSyncExport.MAG_HEADER.trim().split(","), e.csv("magnetometer.csv").header)
        assertEquals(20, detected(e, "L").size)
    }

    @Test fun noPingsStillAlignsByStompAndSaysThePingsAreMissing() {
        val e = run("fault-no-pings", GardenParams(includePings = false))
        val g = e.json("gesture-sync.json")
        assertEquals("measured", g.getString("status"))
        assertFalse(g.getJSONObject("ping").getBoolean("available"))
        assertTrue(g.isNull("stomp_minus_ping_ms"))
        assertEquals(e.truth.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.010)
        val fit = e.json("clock-sync-fit.json")
        assertFalse(fit.getJSONObject("L").getBoolean("available"))
        assertEquals(0, e.csv("clock-sync.csv").rows.size)
    }

    @Test fun aFlashGapCoveredByLiveKeepsTheTurnsAndShowsInHealth() {
        val e = run("fault-flash-gap", GardenParams(flashDrops = listOf(GardenDrop("R", 69.0, 150))))
        val flash = stream(e, "R", "flash")
        assertEquals(1, flash.getInt("gap_count"))
        assertEquals(150 * 1000.0 / 104.0, flash.getDouble("longest_gap_ms"), 25.0)
        assertEquals(1, flash.getInt("dropped_flag_count"))
        assertEquals(150, e.json("session.json").getJSONArray("captures").getJSONObject(1).getInt("dropped_samples"))
        // Live fills the hole in the analysis timeline, so no turn is lost.
        assertEquals(20, detected(e, "R").size)
        assertEquals("measured", e.json("gesture-sync.json").getString("status"))
    }

    @Test fun aFlashGapWithTheLiveStreamAlsoLostBlanksTheAffectedTurnOnly() {
        val e = run("fault-flash-and-live-gap", GardenParams(
            flashDrops = listOf(GardenDrop("R", 69.0, 150)), liveBlackouts = listOf(GardenWindow("R", 68.9, 70.6))))
        val flash = stream(e, "R", "flash")
        assertEquals(1, flash.getInt("gap_count"))
        val live = stream(e, "R", "live")
        assertTrue("live gap seen", live.getInt("gap_count") >= 1)
        val q = e.json("session.json").getJSONArray("quality")
        val right = (0 until q.length()).map { q.getJSONObject(it) }.first { it.getString("side") == "R" }
        assertTrue("session quality gap ${right.getInt("gaps_over_100ms")}", right.getInt("gaps_over_100ms") >= 1)
        assertTrue(right.getDouble("longest_gap_ms") > 1000)
        // Never an invented turn, and no usable shape metric for a turn that crosses the hole.
        val found = detected(e, "R")
        val truth = e.truth
        assertTrue("right turns ${found.size}", found.size in 17..20)
        val inGap = found.filter { it.startMs > truth.wallMs(68.0) && it.startMs < truth.wallMs(71.5) }
        assertTrue(inGap.all { !it.usable })
        assertEquals(20, detected(e, "L").size)
    }

    @Test fun aLeftClockThatWrapsMidSessionStillAligns() {
        // 32-bit microseconds wrap at 4294.967 s: the left boot starts at 4210 s and wraps about 85 s in, the right 3.7 s later.
        val e = run("fault-clock-wraps", GardenParams(leftClockStartSec = 4210.0, rightClockStartSec = 4213.7))
        val t = e.truth
        val g = e.json("gesture-sync.json")
        assertEquals(g.optString("reason"), "measured", g.getString("status"))
        assertEquals(t.sensorSec("L", t.stompStartSecTrue), g.getDouble("ref_left_time_s"), 0.02)
        assertEquals(t.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.010)
        assertEquals(t.relativeDriftPpm, g.getDouble("drift_ppm"), 25.0)
        assertEquals(20, detected(e, "L").size)
        assertEquals(20, detected(e, "R").size)
        assertEquals("usable", stream(e, "L", "flash").getString("verdict"))
        assertEquals(t.liveGapRuns.getValue("L"), stream(e, "L", "live").getInt("gap_count"))
        // The ping fit uses the 64-bit clock, so the wrap must not move the two sync methods apart.
        assertTrue(e.json("clock-sync-fit.json").getJSONObject("boot_to_boot").getBoolean("available"))
        assertTrue("stomp minus ping ${g.optDouble("stomp_minus_ping_ms")}", Math.abs(g.getDouble("stomp_minus_ping_ms")) < 25.0)
    }

    @Test fun aLiveStreamThatStartsAfterTheWrapStillAlignsWithFlashBeforeIt() {
        // Both boots wrap about 85 s in; the live stream is lost until 95 s, so it starts after the wrap while flash
        // started before it. Live must be put on the same epoch as flash or the stomp windows are lost.
        val e = run("fault-live-starts-after-wrap", GardenParams(leftClockStartSec = 4210.0, rightClockStartSec = 4213.7,
            liveBlackouts = listOf(GardenWindow("L", 0.0, 95.0), GardenWindow("R", 0.0, 95.0))))
        val t = e.truth
        val g = e.json("gesture-sync.json")
        assertEquals(g.optString("reason"), "measured", g.getString("status"))
        assertEquals(t.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.010)
    }

    @Test fun aSessionWithoutFlashEitherAlignsToASampleOrTwoOrRefuses() {
        // The stomp rings at 18 Hz and the live stream is every second sample (52 Hz, no anti-alias filter), so the two
        // boots see differently aliased rings. The aligner may refuse; it must never return a wrong offset.
        val e = run("fault-live-only", GardenParams(includeLeftFlash = false, includeRightFlash = false))
        val g = e.json("gesture-sync.json")
        when (g.getString("status")) {
            "measured" -> assertEquals(e.truth.offsetRightMinusLeft(g.getDouble("ref_left_time_s")), g.getDouble("offset_s"), 0.040)
            "refused" -> assertTrue(g.getString("reject"), g.getString("reject") in listOf("low_correlation", "no_match", "no_transient", "ambiguous"))
            else -> fail("unexpected status ${g.getString("status")}")
        }
        assertFalse(hasStream(e, "L", "flash"))
        assertEquals(20, detected(e, "L").size)
        assertEquals(20, detected(e, "R").size)
    }
}
