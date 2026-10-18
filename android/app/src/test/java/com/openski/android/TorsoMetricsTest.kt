package com.openski.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TorsoMetricsTest {
    private val g = 9.80665
    private val wallStart = 1_800_000_000_000L
    private val elapsedStart = 5_000_000_000_000L
    private val ref = ClockSyncExport.ClockReference(wallStart, elapsedStart)

    private data class Turn(val start: Double, val dur: Double, val peak: Double, val headingDeg: Double)

    /**
     * Synthetic boot and torso: sideways lean = [leanGain] x boot roll, fore-aft pitch = [pitchGain] x |boot roll|, torso
     * heading rate = [yawGain] x boot heading rate. The boot's timeline (rolls and candidates) runs [bootShiftMs] after the
     * phone clock; the phone gyro carries a fixed phone-frame [gyroBias]; phone rows in [gapFrom, gapTo) (s) are dropped.
     */
    private inner class Scenario(
        val leanGain: Double = 0.4, val pitchGain: Double = 0.0, val yawGain: Double = 0.5, val headingPerTurnDeg: Double = 40.0,
        val pocket: GardenQuat = GardenQuat.axis(Vector3(0.3, -0.8, 0.5), 2.1), val gameYaw: Double = 0.7,
        val gyroNoise: Double = 0.01, val accelNoise: Double = 0.05, val sway: Double = 0.0,
        val phoneShiftMs: Double = 0.0, val phoneFrom: Double = 0.0, val phoneTo: Double = 60.0,
        val bodyNoise: Boolean = false, val seed: Long = 7, val bootShiftMs: Double = 0.0,
        val gyroBias: Vector3 = Vector3(0.0, 0.0, 0.0), val gapFrom: Double = -1.0, val gapTo: Double = -1.0,
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
            BootRoll(side, wallStart + t * 1000 + bootShiftMs, roll, 0.0, rollRateRadps = Math.toRadians(rate), headingRateRadps = Math.toRadians(heading))
        }

        fun phone(): List<StoredPhone> {
            val rng = Random(seed)
            val out = ArrayList<StoredPhone>()
            var heading = 0.0
            var i = (phoneFrom * 100).toInt()
            val x = Vector3(1.0, 0.0, 0.0); val y = Vector3(0.0, 1.0, 0.0); val z = Vector3(0.0, 0.0, 1.0)
            while (i < (phoneTo * 100).toInt()) {
                val t = i * 0.01
                i++
                val (roll, rate, hRate) = boot(t)
                heading += Math.toRadians(yawGain * hRate) * 0.01
                if (t >= gapFrom && t < gapTo) continue
                val lean = Math.toRadians(leanGain * roll)
                val leanRate = Math.toRadians(leanGain * rate)
                val pitch = Math.toRadians(pitchGain * abs(roll))
                val pitchRate = Math.toRadians(pitchGain * rate * if (roll >= 0) 1 else -1)
                val yawQ = GardenQuat.axis(z, gameYaw + heading)
                val leanQ = GardenQuat.axis(x, lean)
                val torso = yawQ * leanQ * GardenQuat.axis(y, pitch)
                val q = torso * pocket
                var wWorld = z * Math.toRadians(yawGain * hRate) + yawQ.rotate(x) * leanRate + (yawQ * leanQ).rotate(y) * pitchRate
                var aWorld = Vector3(sway * sin(2 * PI * 0.7 * t), sway * cos(2 * PI * 0.9 * t), g)
                if (bodyNoise) {
                    // Noise fixed to the torso (the same physical motion whatever the pocket or the game-rotation yaw).
                    wWorld += torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * gyroNoise)
                    aWorld += torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * accelNoise)
                }
                var gyro = q.conj().rotate(wWorld) + gyroBias
                var acc = q.conj().rotate(aWorld)
                if (!bodyNoise) {
                    gyro += Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * gyroNoise
                    acc += Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * accelNoise
                }
                val ns = elapsedStart + ((t * 1000 + phoneShiftMs) * 1e6).toLong()
                out.add(StoredPhone(ns, gyro.x.toFloat(), gyro.y.toFloat(), gyro.z.toFloat(), acc.x.toFloat(), acc.y.toFloat(), acc.z.toFloat(),
                    q.w.toFloat(), q.x.toFloat(), q.y.toFloat(), q.z.toFloat()))
            }
            return out
        }

        /** Detector-like candidates: start 150 ms after the roll starts, end 100 ms after it returns. */
        fun candidates(side: String = "L") = turns.map { TorsoCandidate(side, if (it.peak > 0) "LEFT" else "RIGHT",
            wallStart + (it.start + 0.15) * 1000 + bootShiftMs, wallStart + (it.start + it.dur + 0.1) * 1000 + bootShiftMs, abs(it.peak)) }

        fun analyse(offsetMs: Double? = 0.0, reference: ClockSyncExport.ClockReference? = ref, rows: List<StoredPhone> = phone(),
                    timing: Map<String, TorsoTimingSource> = mapOf("L" to TorsoMetricsExport.SideTiming(null, null, reference, 0.0)),
                    userOffsets: Map<String, Double> = emptyMap(), rolls: List<BootRoll> = rolls()) =
            TorsoMetricsExport.analyse(rows, offsetMs, reference, candidates(), mapOf("L" to rolls), timing,
                wallStart.toDouble(), wallStart + duration * 1000, userOffsetMs = userOffsets)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(key: String) = this[key] as Map<String, Any?>
    private fun Map<String, Any?>.d(key: String) = this[key] as Double?
    private fun mean(r: TorsoMetricsExport.Result, f: (TorsoTurnRow) -> Double?) = r.rows.mapNotNull(f).average()

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
        assertTrue(mean(quiet) { it.gyroRmsDps } * 2 < mean(moving) { it.gyroRmsDps })
        assertTrue(mean(quiet) { it.horizontalAccelSd } * 3 < mean(moving) { it.horizontalAccelSd })
    }

    @Test fun torsoTiltTracksBootRollAsAMagnitude() {
        val s = Scenario(leanGain = 0.4)
        val r = s.analyse()
        assertFalse(r.reason, r.refused)
        assertEquals(s.turns.size, r.rows.size)
        // The game rotation vector is exact here, so the ratio is the injected 0.4 to within the 5-row smoothing.
        r.rows.forEach { row -> assertEquals("tilt ratio at ${row.startMs}", 0.4, row.tiltPeakDeg!! / row.bootRollPeakDeg, 0.01) }
        val tilt = r.block.m("per_boot").m("L").m("tilt_vs_roll")
        assertEquals(0.4, tilt.m("tilt_to_roll_ratio").d("mean")!!, 0.005)
        assertTrue("correlation ${tilt.d("correlation_across_turns")}", tilt.d("correlation_across_turns")!! > 0.999)
        // Left and right leans give the same unsigned magnitude: no sign is read from the unknown pocket frame.
        val left = r.rows.filter { it.direction == "LEFT" }.map { it.tiltPeakDeg!! / it.bootRollPeakDeg }.average()
        val right = r.rows.filter { it.direction == "RIGHT" }.map { it.tiltPeakDeg!! / it.bootRollPeakDeg }.average()
        assertEquals(left, right, 0.005)
    }

    @Test fun foreAftPitchRaisesTheTiltBecauseItIsTotalTiltNotLateralRoll() {
        // Same sideways lean, plus a fore-aft pitch of 0.3 x |roll|: the tilt is the combined angle, about sqrt(0.4^2 + 0.3^2) = 0.5.
        val lateral = Scenario(leanGain = 0.4).analyse()
        val pitched = Scenario(leanGain = 0.4, pitchGain = 0.3).analyse()
        val pitchOnly = Scenario(leanGain = 0.0, pitchGain = 0.3).analyse()
        assertEquals(0.4, mean(lateral) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, 0.005)
        assertEquals(0.5, mean(pitched) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, 0.01)
        // Pitch has one sign, so the neutral rows (|roll| up to 3 degrees) pull the baseline slightly towards it: a little under 0.3.
        assertEquals(0.3, mean(pitchOnly) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, 0.01)
        lateral.rows.zip(pitched.rows).forEach { (a, b) -> assertTrue(b.tiltPeakDeg!! > a.tiltPeakDeg!! + 1.0) }
    }

    @Test fun torsoYawAgreesWithBootHeadingInSignAndRatio() {
        val s = Scenario(yawGain = 0.5, headingPerTurnDeg = 40.0)
        val r = s.analyse()
        r.rows.forEach { row ->
            assertEquals("ratio at ${row.startMs}", 0.5, row.headingRatio!!, 0.01)
            assertTrue("rate correlation ${row.yawRateCorrelation}", row.yawRateCorrelation!! > 0.99)
        }
        val yaw = r.block.m("per_boot").m("L").m("yaw")
        assertEquals(1.0, yaw.d("sign_agreement_fraction")!!, 0.0)
        assertTrue(yaw.d("heading_change_correlation_across_turns")!! > 0.999)
        // A torso that counter-rotates disagrees in sign every time.
        val counter = Scenario(yawGain = -0.5).analyse()
        assertEquals(0.0, counter.block.m("per_boot").m("L").m("yaw").d("sign_agreement_fraction")!!, 0.0)
        assertEquals(-0.5, mean(counter) { it.headingRatio }, 0.01)
        // No boot heading change: the ratio, correlation and sign agreement are not meaningful and stay null.
        val still = Scenario(headingPerTurnDeg = 0.0).analyse()
        assertTrue(still.rows.all { it.headingRatio == null && it.yawRateCorrelation == null })
        assertNull(still.block.m("per_boot").m("L").m("yaw").d("sign_agreement_fraction"))
    }

    @Test fun aGapAtTheYawPeakBlanksTheHeadingFiguresInsteadOfReadingLow() {
        val s = Scenario()
        val k = 6
        val turn = s.turns[k]
        val mid = turn.start + turn.dur / 2
        // A 0.2 s hole at the middle of the turn (the heading-rate peak): coverage stays above 80 % but below 95 %.
        val r = Scenario(gapFrom = mid - 0.1, gapTo = mid + 0.1).analyse()
        val row = r.rows[k]
        assertTrue("coverage ${row.coverage}", row.coverage in TorsoMetrics.MIN_TURN_COVERAGE..TorsoMetrics.MIN_YAW_COVERAGE)
        assertNotNull(row.gyroRmsDps); assertNotNull(row.tiltPeakDeg)
        assertNull(row.torsoHeadingChangeDeg); assertNull(row.headingRatio); assertNull(row.yawRateCorrelation)
        // The neighbours are unaffected.
        assertEquals(0.5, r.rows[k - 1].headingRatio!!, 0.01)
        assertEquals(0.5, r.rows[k + 1].headingRatio!!, 0.01)
        // A boot gap at the peak blanks the boot heading change the same way.
        val rolls = s.rolls().filterNot { it.timeMs in (wallStart + (mid - 0.1) * 1000)..(wallStart + (mid + 0.1) * 1000) }
        val b = s.analyse(rolls = rolls).rows[k]
        assertTrue(b.bootCoverage < TorsoMetrics.MIN_YAW_COVERAGE)
        assertNull(b.bootHeadingChangeDeg); assertNull(b.headingRatio)
    }

    @Test fun theFrameIndependentMetricsAgreeToFloatRoundingWhateverThePocket() {
        val a = Scenario(bodyNoise = true).analyse()
        val b = Scenario(bodyNoise = true, pocket = GardenQuat.axis(Vector3(-0.9, 0.1, 0.4), -2.6), gameYaw = -2.2).analyse()
        val c = Scenario(bodyNoise = true, pocket = GardenQuat.IDENTITY, gameYaw = 0.0).analyse()
        for (other in listOf(b, c)) {
            assertEquals(a.rows.size, other.rows.size)
            a.rows.zip(other.rows).forEach { (x, y) ->
                assertEquals(x.gyroRmsDps!!, y.gyroRmsDps!!, 1e-4)
                assertEquals(x.horizontalAccelSd!!, y.horizontalAccelSd!!, 1e-5)
                assertEquals(x.tiltPeakDeg!!, y.tiltPeakDeg!!, 1e-3)
                assertEquals(x.torsoHeadingChangeDeg!!, y.torsoHeadingChangeDeg!!, 1e-3)
                assertEquals(x.yawRateCorrelation!!, y.yawRateCorrelation!!, 1e-5)
            }
            assertEquals(a.block.m("phone_gyro_bias")["still_rows"], other.block.m("phone_gyro_bias")["still_rows"])
        }
    }

    @Test fun aHundredMillisecondTimingErrorBarelyMovesTheWindowedFigures() {
        val base = Scenario().analyse()
        for (shift in listOf(-100.0, 100.0)) {
            val moved = Scenario(phoneShiftMs = shift).analyse()
            assertEquals(base.rows.size, moved.rows.size)
            assertEquals(mean(base) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, mean(moved) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, 0.01)
            assertEquals(mean(base) { it.headingRatio }, mean(moved) { it.headingRatio }, 0.05)
            assertEquals(mean(base) { it.gyroRmsDps }, mean(moved) { it.gyroRmsDps }, 0.05 * mean(base) { it.gyroRmsDps })
            // The binned rate correlation is the most timing-sensitive figure (about 0.91 at 100 ms on 1.2 to 1.8 s turns).
            assertTrue(mean(moved) { it.yawRateCorrelation } > 0.88)
        }
        // The rows carry the unmeasured allowance and a timing sensitivity from moving the phone by it.
        assertTrue(base.rows.all { !it.timing.measured && it.timing.uncertaintyMs == TorsoMetricsExport.UNMEASURED_BIAS_BOUND_MS + TorsoMetricsExport.PHONE_CLOCK_SLACK_MS })
        assertTrue(base.rows.all { it.timingSensitivity != null && it.timingLimited != null })
    }

    /** Symmetric pings for a boot whose sensor clock starts at [sensorStartUs] when the phone's elapsed clock is [elapsedStart]. */
    private fun pings(sensorStartUs: Long) = ClockSync("L").also { sync ->
        for (k in 0 until 40) {
            val tSec = k * 1.5
            val send = elapsedStart + (tSec * 1e9).toLong()
            sync.add(PingPair("L", k, send, send + 8_000_000L, sensorStartUs + (tSec * 1e6).toLong() + 4_000L))
        }
    }

    /** Timeline points of a boot whose analysis timeline runs [biasMs] after the phone clock. */
    private fun timeline(sensorStartUs: Long, biasMs: Double) = (0 until 6000).map { i ->
        val clockMs = sensorStartUs / 1000.0 + i * 10.0
        TimelinePoint("L", wallStart + i * 10.0 + biasMs, SensorSample(0, clockMs.toLong(), 0f, 0f, 9.8f, 0f, 0f, 0f), "flash", sensorTimeUs = (clockMs * 1000).toLong())
    }

    @Test fun measuredTimingRemovesTheBootTimelineBias() {
        val sensorStartUs = 1_000_000_000L
        val points = timeline(sensorStartUs, 30.0)
        val timing = TorsoMetricsExport.SideTiming(ClockAlignment.anchor(points, "L"), pings(sensorStartUs), ref, 0.0).at(wallStart + 30_000.0)
        assertTrue(timing.measured)
        assertEquals(30.0, timing.biasMs, 0.5)
        assertTrue(timing.uncertaintyMs < 15.0)
        // Without pings the bias is not removed and the allowance is wide.
        val none = TorsoMetricsExport.SideTiming(ClockAlignment.anchor(points, "L"), null, ref, 20.0).at(wallStart + 30_000.0)
        assertFalse(none.measured)
        assertEquals(TorsoMetricsExport.UNMEASURED_BIAS_BOUND_MS + 20.0 + TorsoMetricsExport.PHONE_CLOCK_SLACK_MS, none.uncertaintyMs, 1e-9)
        assertEquals(0.0, none.biasMs, 0.0)
    }

    @Test fun theMeasuredBiasIsRemovedWithTheRightSign() {
        // The boot's timeline runs 250 ms after the phone clock. The pings measure +250 ms, and removing it with that sign
        // recovers the unshifted figures; removing it with the opposite sign (a 500 ms error) visibly does not.
        val sensorStartUs = 1_000_000_000L
        val side = TorsoMetricsExport.SideTiming(ClockAlignment.anchor(timeline(sensorStartUs, 250.0), "L"), pings(sensorStartUs), ref, 0.0)
        assertEquals(250.0, side.at(wallStart + 30_000.0).biasMs, 0.5)
        val reference = Scenario().analyse()
        val s = Scenario(bootShiftMs = 250.0)
        val right = s.analyse(timing = mapOf("L" to side))
        val wrong = s.analyse(timing = mapOf("L" to TorsoTimingSource { t -> side.at(t).let { it.copy(biasMs = -it.biasMs) } }))
        val none = s.analyse(timing = mapOf("L" to TorsoTimingSource { TorsoTiming(0.0, 10.0, false) }))
        assertEquals(mean(reference) { it.headingRatio }, mean(right) { it.headingRatio }, 0.005)
        assertEquals(mean(reference) { it.yawRateCorrelation }, mean(right) { it.yawRateCorrelation }, 0.002)
        assertEquals(mean(reference) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, mean(right) { it.tiltPeakDeg!! / it.bootRollPeakDeg }, 0.002)
        val errRight = abs(mean(right) { it.headingRatio } - 0.5)
        val errNone = abs(mean(none) { it.headingRatio } - 0.5)
        val errWrong = abs(mean(wrong) { it.headingRatio } - 0.5)
        assertTrue("right $errRight < none $errNone < wrong $errWrong", errRight < 0.01 && errNone > errRight + 0.03 && errWrong > errNone + 0.03)
        assertTrue(mean(wrong) { it.yawRateCorrelation } < mean(right) { it.yawRateCorrelation } - 0.1)
    }

    @Test fun aStillPeriodGyroBiasIsEstimatedAndRemoved() {
        val bias = Vector3(0.02, -0.015, 0.01)
        val biased = Scenario(gyroBias = bias).analyse()
        val clean = Scenario().analyse()
        val b = biased.block.m("phone_gyro_bias")
        assertEquals(true, b["estimated"])
        @Suppress("UNCHECKED_CAST") val est = b["bias_radps"] as List<Double>
        assertEquals(bias.x, est[0], 0.002); assertEquals(bias.y, est[1], 0.002); assertEquals(bias.z, est[2], 0.002)
        // 0.027 rad/s left in would add about 2 degrees of heading per 1.5 s turn; removed, the ratio is unchanged.
        assertEquals(mean(clean) { it.headingRatio }, mean(biased) { it.headingRatio }, 0.005)
        assertEquals(mean(clean) { it.gyroRmsDps }, mean(biased) { it.gyroRmsDps }, 0.05)
        // The still-period RMS is the noise floor: 0.01 rad/s per axis is about 0.99 dps of magnitude.
        assertEquals(0.01 * sqrt(3.0) * 180 / PI, b.d("still_gyro_rms_dps")!!, 0.1)
        // A torso that never keeps still gives no estimate and removes nothing.
        val restless = Scenario(gyroNoise = 0.2).analyse().block.m("phone_gyro_bias")
        assertEquals(false, restless["estimated"]); assertNull(restless["bias_radps"])
    }

    @Test fun coverageOfAWindowWithNoRowsIsZero() {
        val track = PhoneTrack.build(Scenario().phone(), ref, 0.0)
        val t0 = track.t[1000]
        assertEquals(0.0, TorsoMetrics.coverage(track, t0 + 1.0, t0 + 5.0), 0.0)
        assertEquals(1.0, TorsoMetrics.coverage(track, t0, t0 + 1000.0), 0.02)
    }

    @Test fun aFigureThatVanishesWithinTheUncertaintyIsTimingLimited() {
        val s = Scenario()
        val c = s.candidates()[3]
        // The phone stops 30 ms after the candidate ends: at the best timing it is covered, moved 400 ms later it is not.
        val rows = s.phone().filter { (it.phoneNs - elapsedStart) / 1e6 <= c.endMs - wallStart + 30 }
        val track = PhoneTrack.build(rows, ref, 0.0)
        val row = TorsoMetrics.row(track, s.rolls(), c, TorsoTiming(0.0, 400.0, false), null)
        assertNotNull(row.gyroRmsDps)
        assertEquals(true, row.timingLimited)
    }

    @Test fun anUnmeasuredSideWithoutTimingUsesItsUserOffsetInTheAllowance() {
        val r = Scenario().analyse(timing = emptyMap(), userOffsets = mapOf("L" to 25.0))
        assertTrue(r.rows.all { it.timing.uncertaintyMs == TorsoMetricsExport.UNMEASURED_BIAS_BOUND_MS + 25.0 + TorsoMetricsExport.PHONE_CLOCK_SLACK_MS })
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
        assertTrue(partial.all { it.gyroRmsDps == null && it.tiltPeakDeg == null })
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
        assertTrue(o.getString("note").contains("not lateral roll"))
        assertEquals("unmeasured", o.getJSONObject("timing").getJSONObject("L").getString("source"))
    }
}
