package com.openski.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TorsoMetricsTest {
    private val g = 9.80665
    private val wallStart = 1_800_000_000_000L
    private val elapsedStart = 5_000_000_000_000L
    private val ref = ClockSyncExport.ClockReference(wallStart, elapsedStart)

    private data class Turn(val start: Double, val dur: Double, val peak: Double, val headingDeg: Double)

    /** Synthetic boot and torso: lean = [leanGain] x boot roll, torso heading rate = [yawGain] x boot heading rate. */
    private inner class Scenario(
        val leanGain: Double = 0.4, val yawGain: Double = 0.5, val headingPerTurnDeg: Double = 40.0,
        val pocket: GardenQuat = GardenQuat.axis(Vector3(0.3, -0.8, 0.5), 2.1), val gameYaw: Double = 0.7,
        val gyroNoise: Double = 0.01, val accelNoise: Double = 0.05, val sway: Double = 0.0,
        val phoneShiftMs: Double = 0.0, val phoneFrom: Double = 0.0, val phoneTo: Double = 60.0,
        val bodyNoise: Boolean = false, val seed: Long = 7,
    ) {
        val duration = 60.0
        val turns: List<Turn> = run {
            val r = Random(3); var t = 5.0; var k = 0; val out = ArrayList<Turn>()
            while (t < duration - 5) {
                val d = 1.2 + 0.6 * r.nextDouble()
                val peak = (15.0 + 20.0 * r.nextDouble()) * if (k % 2 == 0) 1 else -1
                out.add(Turn(t, d, peak, headingPerTurnDeg * if (k % 2 == 0) 1 else -1))
                t += d + 0.3 + 0.3 * r.nextDouble(); k++
            }
            out
        }

        /** Roll (deg), roll rate (deg/s), heading rate (deg/s) of the boot at true time t (s). */
        fun boot(t: Double): Triple<Double, Double, Double> {
            for (tr in turns) {
                val u = t - tr.start
                if (u >= 0 && u < tr.dur) {
                    val x = PI * u / tr.dur
                    return Triple(tr.peak * sin(x) * sin(x), tr.peak * PI / tr.dur * sin(2 * x), 2 * tr.headingDeg / tr.dur * sin(x) * sin(x))
                }
            }
            return Triple(0.0, 0.0, 0.0)
        }

        fun rolls(side: String = "L"): List<BootRoll> = (0 until (duration * 100).toInt()).map { i ->
            val t = i * 0.01
            val (roll, rate, heading) = boot(t)
            BootRoll(side, wallStart + t * 1000, roll, 0.0, rollRateRadps = Math.toRadians(rate), headingRateRadps = Math.toRadians(heading))
        }

        fun phone(): List<StoredPhone> {
            val rng = Random(seed)
            val out = ArrayList<StoredPhone>()
            var heading = 0.0
            var i = (phoneFrom * 100).toInt()
            while (i < (phoneTo * 100).toInt()) {
                val t = i * 0.01
                val (roll, rate, hRate) = boot(t)
                heading += Math.toRadians(yawGain * hRate) * 0.01
                val lean = Math.toRadians(leanGain * roll)
                val leanRate = Math.toRadians(leanGain * rate)
                val torso = GardenQuat.axis(Vector3(0.0, 0.0, 1.0), gameYaw + heading) * GardenQuat.axis(Vector3(1.0, 0.0, 0.0), lean)
                val q = torso * pocket
                val forward = GardenQuat.axis(Vector3(0.0, 0.0, 1.0), gameYaw + heading).rotate(Vector3(1.0, 0.0, 0.0))
                var wWorld = Vector3(0.0, 0.0, Math.toRadians(yawGain * hRate)) + forward * leanRate
                var aWorld = Vector3(sway * sin(2 * PI * 0.7 * t), sway * cos(2 * PI * 0.9 * t), g)
                if (bodyNoise) {
                    // Noise fixed to the torso (the same physical motion whatever the pocket or the game-rotation yaw).
                    wWorld += torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * gyroNoise)
                    aWorld += torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * accelNoise)
                }
                var gyro = q.conj().rotate(wWorld)
                var acc = q.conj().rotate(aWorld)
                if (!bodyNoise) {
                    gyro += Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * gyroNoise
                    acc += Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * accelNoise
                }
                val ns = elapsedStart + ((t * 1000 + phoneShiftMs) * 1e6).toLong()
                out.add(StoredPhone(ns, gyro.x.toFloat(), gyro.y.toFloat(), gyro.z.toFloat(), acc.x.toFloat(), acc.y.toFloat(), acc.z.toFloat(),
                    q.w.toFloat(), q.x.toFloat(), q.y.toFloat(), q.z.toFloat()))
                i++
            }
            return out
        }

        /** Detector-like candidates: start 150 ms after the roll starts, end 100 ms after it returns. */
        fun candidates(side: String = "L") = turns.map { TorsoCandidate(side, if (it.peak > 0) "LEFT" else "RIGHT",
            wallStart + (it.start + 0.15) * 1000, wallStart + (it.start + it.dur + 0.1) * 1000, abs(it.peak)) }

        fun analyse(offsetMs: Double? = 0.0, reference: ClockSyncExport.ClockReference? = ref, rows: List<StoredPhone> = phone()) =
            TorsoMetricsExport.analyse(rows, offsetMs, reference, candidates(), mapOf("L" to rolls()),
                mapOf("L" to TorsoMetricsExport.SideTiming(null, null, reference, 0.0)), wallStart.toDouble(), wallStart + duration * 1000)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(key: String) = this[key] as Map<String, Any?>
    private fun Map<String, Any?>.d(key: String) = this[key] as Double?

    @Test fun upAndHorizontalComeFromTheGameRotationVector() {
        val q = GardenQuat.axis(Vector3(1.0, 2.0, -0.5), 1.3)
        val up = TorsoMetrics.upInPhone(q.w, q.x, q.y, q.z)!!
        val expected = q.conj().rotate(Vector3(0.0, 0.0, 1.0))
        assertEquals(expected.x, up.x, 1e-9); assertEquals(expected.y, up.y, 1e-9); assertEquals(expected.z, up.z, 1e-9)
        val a = Vector3(0.4, -1.1, 9.0)
        val world = q.rotate(a)
        val h = TorsoMetrics.horizontalWorld(q.w, q.x, q.y, q.z, a)!!
        assertEquals(world.x, h.first, 1e-9); assertEquals(world.y, h.second, 1e-9)
        assertNull(TorsoMetrics.upInPhone(0.0, 0.0, 0.0, 0.0))
    }

    @Test fun aQuietTorsoReadsLowerThanAMovingOne() {
        val quiet = Scenario(leanGain = 0.1, yawGain = 0.1, gyroNoise = 0.005, accelNoise = 0.03).analyse()
        val moving = Scenario(leanGain = 0.6, yawGain = 0.8, gyroNoise = 0.15, accelNoise = 0.6, sway = 1.5).analyse()
        assertFalse(quiet.refused); assertFalse(moving.refused)
        val qq = quiet.block.m("quietness"); val mq = moving.block.m("quietness")
        assertTrue("${qq.d("run_gyro_rms_dps")} vs ${mq.d("run_gyro_rms_dps")}", qq.d("run_gyro_rms_dps")!! * 2 < mq.d("run_gyro_rms_dps")!!)
        assertTrue(qq.d("run_horizontal_accel_sd_mps2")!! * 3 < mq.d("run_horizontal_accel_sd_mps2")!!)
        assertTrue(quiet.rows.map { it.gyroRmsDps!! }.average() < moving.rows.map { it.gyroRmsDps!! }.average())
        assertTrue(quiet.rows.map { it.horizontalAccelSd!! }.average() < moving.rows.map { it.horizontalAccelSd!! }.average())
    }

    @Test fun torsoLeanTracksBootRollAsAMagnitude() {
        val s = Scenario(leanGain = 0.4)
        val r = s.analyse()
        assertFalse(r.reason, r.refused)
        assertEquals(s.turns.size, r.rows.size)
        r.rows.forEach { row ->
            assertNotNull(row.leanPeakDeg)
            assertEquals("lean ratio at ${row.startMs}", 0.4, row.leanPeakDeg!! / row.bootRollPeakDeg, 0.06)
        }
        val lean = r.block.m("per_boot").m("L").m("lean_vs_roll")
        assertEquals(0.4, lean.m("lean_to_roll_ratio").d("mean")!!, 0.04)
        assertTrue("correlation ${lean.d("correlation_across_turns")}", lean.d("correlation_across_turns")!! > 0.95)
        // Left and right leans give the same unsigned magnitude: no sign is read from the unknown pocket frame.
        val left = r.rows.filter { it.direction == "LEFT" }.map { it.leanPeakDeg!! / it.bootRollPeakDeg }.average()
        val right = r.rows.filter { it.direction == "RIGHT" }.map { it.leanPeakDeg!! / it.bootRollPeakDeg }.average()
        assertEquals(left, right, 0.05)
        assertTrue(r.rows.all { it.leanPeakDeg!! >= 0 })
    }

    @Test fun torsoYawAgreesWithBootHeadingInSignAndRatio() {
        val s = Scenario(yawGain = 0.5, headingPerTurnDeg = 40.0)
        val r = s.analyse()
        r.rows.forEach { row ->
            assertEquals("ratio at ${row.startMs}", 0.5, row.headingRatio!!, 0.08)
            assertTrue("rate correlation ${row.yawRateCorrelation}", row.yawRateCorrelation!! > 0.9)
        }
        val yaw = r.block.m("per_boot").m("L").m("yaw")
        assertEquals(1.0, yaw.d("sign_agreement_fraction")!!, 0.0)
        assertTrue(yaw.d("heading_change_correlation_across_turns")!! > 0.95)
        // A torso that counter-rotates disagrees in sign every time.
        val counter = Scenario(yawGain = -0.5).analyse().block.m("per_boot").m("L").m("yaw")
        assertEquals(0.0, counter.d("sign_agreement_fraction")!!, 0.0)
        // No boot heading change: the ratio, correlation and sign agreement are not meaningful and stay null.
        val still = Scenario(headingPerTurnDeg = 0.0).analyse()
        assertTrue(still.rows.all { it.headingRatio == null && it.yawRateCorrelation == null })
        assertNull(still.block.m("per_boot").m("L").m("yaw").d("sign_agreement_fraction"))
    }

    @Test fun theFrameIndependentMetricsDoNotDependOnHowThePhoneSitsInThePocket() {
        val a = Scenario(bodyNoise = true).analyse()
        val b = Scenario(bodyNoise = true, pocket = GardenQuat.axis(Vector3(-0.9, 0.1, 0.4), -2.6), gameYaw = -2.2).analyse()
        val c = Scenario(bodyNoise = true, pocket = GardenQuat.IDENTITY, gameYaw = 0.0).analyse()
        for (other in listOf(b, c)) {
            assertEquals(a.rows.size, other.rows.size)
            a.rows.zip(other.rows).forEach { (x, y) ->
                assertEquals(x.gyroRmsDps!!, y.gyroRmsDps!!, 0.01)
                assertEquals(x.horizontalAccelSd!!, y.horizontalAccelSd!!, 0.002)
                assertEquals(x.leanPeakDeg!!, y.leanPeakDeg!!, 0.05)
                assertEquals(x.torsoHeadingChangeDeg!!, y.torsoHeadingChangeDeg!!, 0.05)
                assertEquals(x.yawRateCorrelation!!, y.yawRateCorrelation!!, 0.002)
            }
        }
    }

    @Test fun aHundredMillisecondTimingErrorBarelyMovesTheWindowedFigures() {
        val base = Scenario().analyse()
        for (shift in listOf(-100.0, 100.0)) {
            val moved = Scenario(phoneShiftMs = shift).analyse()
            assertEquals(base.rows.size, moved.rows.size)
            fun mean(r: TorsoMetricsExport.Result, f: (TorsoTurnRow) -> Double?) = r.rows.mapNotNull(f).average()
            assertEquals(mean(base) { it.leanPeakDeg!! / it.bootRollPeakDeg }, mean(moved) { it.leanPeakDeg!! / it.bootRollPeakDeg }, 0.03)
            assertEquals(mean(base) { it.headingRatio }, mean(moved) { it.headingRatio }, 0.08)
            assertEquals(mean(base) { it.gyroRmsDps }, mean(moved) { it.gyroRmsDps }, 0.15 * mean(base) { it.gyroRmsDps })
            assertTrue(mean(moved) { it.yawRateCorrelation } > 0.8)
        }
        // The rows carry the unmeasured allowance and a timing sensitivity from moving the phone by it.
        assertTrue(base.rows.all { !it.timing.measured && it.timing.uncertaintyMs == TorsoMetricsExport.UNMEASURED_BIAS_BOUND_MS + TorsoMetricsExport.PHONE_CLOCK_SLACK_MS })
        assertTrue(base.rows.all { it.timingSensitivity != null && it.timingLimited != null })
    }

    @Test fun noPhoneOrAFailedClockCheckOrPoorCoverageIsRefused() {
        val s = Scenario()
        val none = s.analyse(rows = emptyList())
        assertTrue(none.refused); assertTrue(none.reason, none.reason.contains("no phone motion rows"))
        val noCheck = s.analyse(offsetMs = null)
        assertTrue(noCheck.refused); assertTrue(noCheck.reason.contains("phone_clock_check"))
        val badCheck = s.analyse(offsetMs = 80.0)
        assertTrue(badCheck.refused); assertTrue(badCheck.reason.contains("clock check failed"))
        val noRef = s.analyse(reference = null)
        assertTrue(noRef.refused); assertTrue(noRef.reason.contains("clock_ref"))
        val partial = Scenario(phoneFrom = 0.0, phoneTo = 20.0).analyse()
        assertTrue(partial.refused); assertTrue(partial.reason, partial.reason.contains("covers"))
        for (r in listOf(none, noCheck, badCheck, noRef, partial)) {
            assertTrue(r.rows.isEmpty())
            assertEquals("refused", r.block["status"])
            val lines = TorsoMetricsExport.csv(r.rows, r.reason).lines().filter { it.isNotEmpty() && !it.startsWith("#") }
            assertEquals(listOf(TorsoMetricsExport.HEADER.joinToString(",")), lines)
            JSONObject(TurnMetricsExport.toJson(r.block).toString())
        }
    }

    @Test fun aCandidateWithoutEnoughPhoneCoverageKeepsBlankMetrics() {
        // Phone covers 0 to 40 s of 60 s: enough to compute, but the later candidates are blank or absent.
        val r = Scenario(phoneTo = 40.0).analyse()
        assertFalse(r.reason, r.refused)
        assertTrue(r.rows.all { it.coverage > 0 })
        val partial = r.rows.filter { it.coverage < TorsoMetrics.MIN_TURN_COVERAGE }
        assertTrue(partial.all { it.gyroRmsDps == null && it.leanPeakDeg == null })
        val csv = TorsoMetricsExport.csv(r.rows, r.reason)
        val body = csv.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(TorsoMetricsExport.HEADER.joinToString(","), body.first())
        body.drop(1).forEach { assertEquals(TorsoMetricsExport.HEADER.size, it.split(",").size) }
        assertFalse(csv.contains("NaN"))
        assertTrue(csv.startsWith("# EXPERIMENTAL"))
    }

    @Test fun summaryIsNullSafeJson() {
        val r = Scenario(headingPerTurnDeg = 0.0, leanGain = 0.0).analyse()
        val json = TurnMetricsExport.toJson(r.block).toString()
        assertFalse(json.contains("NaN")); assertFalse(json.contains("Infinity"))
        val o = JSONObject(json)
        assertTrue(o.getBoolean("experimental"))
        assertEquals("computed", o.getString("status"))
        assertTrue(o.getString("placement_note").contains("unverified"))
        assertTrue(o.getJSONObject("timing").getJSONObject("L").getString("source") == "unmeasured")
    }

    @Test fun measuredTimingRemovesTheBootTimelineBias() {
        // A boot whose timeline runs 30 ms after the phone clock (transport delay), measured by symmetric pings.
        val sync = ClockSync("L")
        val sensorStartUs = 1_000_000_000L
        for (k in 0 until 40) {
            val tSec = k * 1.5
            val send = elapsedStart + (tSec * 1e9).toLong()
            val recv = send + 8_000_000L
            sync.add(PingPair("L", k, send, recv, sensorStartUs + (tSec * 1e6).toLong() + 4_000L))
        }
        val points = (0 until 6000).map { i ->
            val clockMs = sensorStartUs / 1000.0 + i * 10.0
            TimelinePoint("L", wallStart + i * 10.0 + 30.0, SensorSample(0, clockMs.toLong(), 0f, 0f, 9.8f, 0f, 0f, 0f), "flash", sensorTimeUs = (clockMs * 1000).toLong())
        }
        val timing = TorsoMetricsExport.SideTiming(ClockAlignment.anchor(points, "L"), sync, ref, 0.0).at(wallStart + 30_000.0)
        assertTrue(timing.measured)
        assertEquals(30.0, timing.biasMs, 1.0)
        assertTrue(timing.uncertaintyMs < 15.0)
        // Without pings the bias is not removed and the allowance is wide.
        val none = TorsoMetricsExport.SideTiming(ClockAlignment.anchor(points, "L"), null, ref, 20.0).at(wallStart + 30_000.0)
        assertFalse(none.measured)
        assertEquals(TorsoMetricsExport.UNMEASURED_BIAS_BOUND_MS + 20.0 + TorsoMetricsExport.PHONE_CLOCK_SLACK_MS, none.uncertaintyMs, 1e-9)
        assertEquals(0.0, none.biasMs, 0.0)
    }
}
