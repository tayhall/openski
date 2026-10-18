package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin

/** Flash samples that never reached storage: [samples] records starting at true time [atSec]. The next record carries the dropped flag. */
data class GardenDrop(val side: String, val atSec: Double, val samples: Int)

/** A stretch of true time in which one boot's live stream is lost. */
data class GardenWindow(val side: String, val fromSec: Double, val toSec: Double)

/**
 * Everything the synthetic garden session is built from. Every default describes the clean session; a test injects a
 * fault by overriding one argument. "True time" is the phone's elapsed-realtime clock in seconds from the recording start.
 */
data class GardenParams(
    val seed: Long = 20261018L,
    val durationSec: Double = 185.0,
    val wallStartMs: Long = 1_792_000_000_000L,
    val phoneBaseNs: Long = 7_200_000_000_000L,
    /** Sensor clock reading (s) of each boot at true time 0; the two differ by a few seconds. */
    val leftClockStartSec: Double = 1234.5,
    val rightClockStartSec: Double = 1238.2,
    /** Boot crystal error against the phone clock (positive: the boot clock runs fast). */
    val leftSkewPpm: Double = 23.0,
    val rightSkewPpm: Double = -31.0,
    val stompStartSec: Double = 5.0,
    val stompEndBeforeEndSec: Double = 25.0,
    val leftStartStomp: Boolean = true,
    val leftEndStomp: Boolean = true,
    val rightStartStomp: Boolean = true,
    val rightEndStomp: Boolean = true,
    val stompAmplitude: Double = 30.0,
    val rightStompGain: Double = 0.9,
    val halfTurns: Int = 20,
    /** The right boot repeats the left boot's roll this long afterwards. */
    val rightLagSec: Double = 0.060,
    val flashHz: Double = 104.0,
    val liveHz: Double = 52.0,
    val flashDrops: List<GardenDrop> = listOf(GardenDrop("L", 60.0, 3), GardenDrop("R", 100.0, 2)),
    val liveDropShare: Double = 0.005,
    val liveBlackouts: List<GardenWindow> = emptyList(),
    /** Share of samples flagged accelerometer or gyro near full scale. */
    val accelClipShare: Double = 0.002,
    val gyroClipShare: Double = 0.001,
    val includeLeftFlash: Boolean = true,
    val includeRightFlash: Boolean = true,
    val includeLeftLive: Boolean = true,
    val includeRightLive: Boolean = true,
    /** False removes every trace of the right boot (samples, capture, pings). */
    val includeRightBoot: Boolean = true,
    val includePings: Boolean = true,
    /** True makes the outbound ping delay a wait for a connection event and the return a full interval (the doc's known bias). */
    val asymmetricPingBias: Boolean = false,
    val leftConnectionIntervalMs: Double = 30.0,
    val rightConnectionIntervalMs: Double = 15.0,
    /**
     * Extra constant delay on every right-boot live notification (s). The analysis timeline anchors each boot on its smallest
     * observed transport delay, so this moves the right boot's timeline later by the same amount while the boot clocks and
     * the true left-right lag stay as they are: an unmeasured timeline bias that only clock alignment can remove.
     */
    val rightLiveExtraDelaySec: Double = 0.0,
    val includePhone: Boolean = true,
    /** False leaves out the phone_clock_check event while keeping the phone rows (the torso metrics must refuse). */
    val phoneClockCheck: Boolean = true,
    /**
     * When set, the phone rows follow this torso model (lean and heading tied to the left boot's half-turns, phone at an
     * arbitrary fixed angle in the pocket) instead of the small default sway. Off by default, so the default ZIPs keep
     * their phone rows.
     */
    val torso: GardenTorso? = null,
    /** Heading change of each boot per half-turn (deg, the turn's direction: positive roll turns left). 0 keeps the boots' heading still. */
    val bootHeadingPerTurnDeg: Double = 0.0,
    val includeMagnetometer: Boolean = true,
    /** The left boot resets at this true time (its clock restarts), and the phone reconnects [reconnectAfterSec] later. */
    val leftClockResetAtSec: Double? = null,
    val reconnectAfterSec: Double = 5.0,
)

/**
 * Known-truth torso for the phone rows: lean = [leanGain] x the left boot's roll (about the torso's forward axis), torso
 * heading rate = [yawGain] x the boot heading rate; the phone sits at the fixed rotation [pocket] (torso to phone) and the
 * game rotation vector has the arbitrary yaw reference [gameYawRad]. Noise is fixed to the torso.
 */
data class GardenTorso(
    val leanGain: Double = 0.4,
    val yawGain: Double = 0.5,
    val pocket: GardenQuat = GardenQuat.axis(Vector3(0.3, -0.8, 0.5), 2.1),
    val gameYawRad: Double = 0.7,
    val gyroNoiseRadps: Double = 0.01,
    val accelNoiseMps2: Double = 0.05,
)

/** Device-to-world rotation as a unit quaternion (w, x, y, z), for the synthetic phone. */
data class GardenQuat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: GardenQuat) = GardenQuat(w * q.w - x * q.x - y * q.y - z * q.z, w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x, w * q.z + x * q.y - y * q.x + z * q.w)
    fun conj() = GardenQuat(w, -x, -y, -z)
    fun rotate(v: Vector3): Vector3 { val r = this * GardenQuat(0.0, v.x, v.y, v.z) * conj(); return Vector3(r.x, r.y, r.z) }
    companion object {
        val IDENTITY = GardenQuat(1.0, 0.0, 0.0, 0.0)
        fun axis(ax: Vector3, angle: Double): GardenQuat { val u = ax.unit(); val s = sin(angle / 2); return GardenQuat(cos(angle / 2), u.x * s, u.y * s, u.z * s) }
    }
}

data class GardenTurn(val startSec: Double, val durationSec: Double, val signedPeakDeg: Double)

/** Known truths of a generated session (everything the tests compare against). */
class GardenTruth(val p: GardenParams, val turns: List<GardenTurn>) {
    val stompStartSecTrue get() = p.stompStartSec
    val stompEndSecTrue get() = p.durationSec - p.stompEndBeforeEndSec
    val flashCount = mutableMapOf<String, Int>()
    val liveCount = mutableMapOf<String, Int>()
    /** Flash records that never reached storage, per boot, and how many records carry the dropped-before flag. */
    val flashDroppedSamples = mutableMapOf<String, Int>()
    val flashDroppedFlags = mutableMapOf<String, Int>()
    val flashAccelRail = mutableMapOf<String, Int>()
    val flashGyroRail = mutableMapOf<String, Int>()
    val flashAnyRail = mutableMapOf<String, Int>()
    /** Maximal runs of lost live frames with a stored frame on both sides (what the health report counts as gaps). */
    val liveGapRuns = mutableMapOf<String, Int>()
    var magRows = 0
    var phoneRows = 0
    val hardIronGauss = Triple(0.15, -0.10, 0.25)
    val magHorizontalGauss = 0.22
    val magVerticalGauss = 0.38
    val magPeriodSec = 46.25
    /** Pings per side: the true send time and the two leg delays in seconds. */
    val pings = mutableMapOf<String, MutableList<Triple<Double, Double, Double>>>()
    var phoneClockCheckOffsetMs = 0.0

    private fun ppm(side: String) = if (side == "L") p.leftSkewPpm else p.rightSkewPpm
    private fun start(side: String) = if (side == "L") p.leftClockStartSec else p.rightClockStartSec
    internal val bootSec = 1.0

    /** Sensor clock (s) of a boot at true time [t]; for the left boot after a reset, the restarted clock. */
    fun sensorSec(side: String, t: Double): Double {
        val reset = p.leftClockResetAtSec
        return if (side == "L" && reset != null && t >= reset + bootSec) (t - reset - bootSec) * (1 + ppm(side) * 1e-6)
        else start(side) + t * (1 + ppm(side) * 1e-6)
    }

    /** True time at which the boot's first-epoch clock read [sensorSeconds]. */
    fun trueTimeOfSensor(side: String, sensorSeconds: Double) = (sensorSeconds - start(side)) / (1 + ppm(side) * 1e-6)

    /** Right sensor clock minus left sensor clock (s), at the moment the left clock reads [leftSensorSec] (first epoch). */
    fun offsetRightMinusLeft(leftSensorSec: Double): Double {
        val t = trueTimeOfSensor("L", leftSensorSec)
        return sensorSec("R", t) - leftSensorSec
    }

    /** Drift of the right clock against the left one in ppm (the aligner's sign: right minus left). */
    val relativeDriftPpm get() = ((1 + p.rightSkewPpm * 1e-6) / (1 + p.leftSkewPpm * 1e-6) - 1) * 1e6

    /** What a fitted phone-from-sensor drift should read: the inverse of the boot's own error. */
    fun fitDriftPpm(side: String) = (1 / (1 + ppm(side) * 1e-6) - 1) * 1e6

    fun phoneNs(t: Double) = p.phoneBaseNs + (t * 1e9).roundToLong()
    fun wallMs(t: Double) = p.wallStartMs + (t * 1000.0).roundToLong()

    /** Truth for the phone-from-sensor map of a boot (first epoch): phone ns at a sensor time in microseconds. */
    fun truePhoneNsOfSensorUs(side: String, sensorUs: Long): Double = phoneNs(trueTimeOfSensor(side, sensorUs / 1e6)).toDouble()
}

class GardenSession(val data: SessionData, val truth: GardenTruth)

/**
 * A roughly three minute two-boot garden session with known truths, in the shape the recorder stores it (flash record
 * format 3 at 104 Hz, live frames at 52 Hz with BLE jitter, clock pings, phone rows, magnetometer rows, events and
 * health snapshots). Boot sample timestamps follow each boot's own clock; the phone clock is the reference.
 *
 * Mounting: forward is sensor +x, up is +z, so a roll of phi gives accel = g (0, sin phi, cos phi) and gyro x = dphi/dt.
 */
object SyntheticGardenSession {
    private const val G = 9.80665
    private const val WRAP_US = 0x1_0000_0000L

    private class Stomp(val at: Double, val amp: Double)

    private fun stomp(t: Double, stomps: List<Stomp>): Double {
        var v = 0.0
        for (s in stomps) {
            val u = t - s.at
            if (u >= 0) v += s.amp * (1 - kotlin.math.exp(-u / 0.004)) * kotlin.math.exp(-u / 0.03) * cos(2 * PI * 18 * u)
        }
        return v
    }

    private fun expo(rng: Random, mean: Double) = -mean * ln(1.0 - rng.nextDouble())

    /** Alternating half-turns in groups of four, one group every 25 s from 15 s. */
    fun turns(p: GardenParams): List<GardenTurn> {
        val rng = Random(p.seed)
        val out = ArrayList<GardenTurn>()
        var k = 0
        var group = 0
        while (k < p.halfTurns) {
            var cursor = 15.0 + group * 25.0
            for (i in 0 until 4) {
                if (k >= p.halfTurns) break
                val duration = 1.0 + rng.nextDouble()
                val peak = 20.0 + 15.0 * rng.nextDouble()
                out.add(GardenTurn(cursor, duration, if (k % 2 == 0) peak else -peak))
                cursor += duration + 0.2 + 0.3 * rng.nextDouble()
                k++
            }
            group++
        }
        return out
    }

    /** Roll in degrees and its rate in degrees per second at true time [t]. */
    private fun roll(turns: List<GardenTurn>, t: Double): Pair<Double, Double> {
        for (turn in turns) {
            val u = t - turn.startSec
            if (u >= 0 && u < turn.durationSec) {
                // sin^2: smooth at both ends (a plain half-sine has a rate step there that no real boot produces).
                val x = PI * u / turn.durationSec
                return turn.signedPeakDeg * sin(x) * sin(x) to turn.signedPeakDeg * (PI / turn.durationSec) * sin(2 * x)
            }
        }
        return 0.0 to 0.0
    }

    /** Boot heading rate (deg/s) at true time [t]: a sin^2 lobe per half-turn whose integral is [perTurnDeg] in the turn's direction. */
    private fun headingRate(turns: List<GardenTurn>, t: Double, perTurnDeg: Double): Double {
        if (perTurnDeg == 0.0) return 0.0
        for (turn in turns) {
            val u = t - turn.startSec
            if (u >= 0 && u < turn.durationSec) {
                val x = PI * u / turn.durationSec
                return (if (turn.signedPeakDeg > 0) perTurnDeg else -perTurnDeg) * 2 / turn.durationSec * sin(x) * sin(x)
            }
        }
        return 0.0
    }

    private class Piece(val tTo: Double, val sensor0: Double, val tOf: (Double) -> Double, val flash: Boolean)

    private val gyroBias = Triple(0.012, -0.008, 0.005)

    private fun raw(storedUs: Long, flags: Int, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(((storedUs and 7L.inv()) or flags.toLong()).toInt())
            putShort(q(ax * 100)); putShort(q(ay * 100)); putShort(q(az * 100))
            putShort(q(gx * 1000)); putShort(q(gy * 1000)); putShort(q(gz * 1000))
        }.array()

    private fun q(v: Double): Short = Math.round(v).coerceIn(-32768L, 32767L).toShort()

    fun build(p: GardenParams = GardenParams()): GardenSession {
        val turns = turns(p)
        val truth = GardenTruth(p, turns)
        val sides = if (p.includeRightBoot) listOf("L", "R") else listOf("L")
        val rngMaster = Random(p.seed * 31 + 7)
        val live = ArrayList<StoredLive>()
        val flash = ArrayList<StoredFlash>()
        val captures = ArrayList<SensorCapture>()
        val sessionId = "garden-rehearsal"

        for (side in sides) {
            val rng = Random(p.seed + if (side == "L") 1 else 2)
            val lag = if (side == "R") p.rightLagSec else 0.0
            val gain = if (side == "R") p.rightStompGain else 1.0
            val stomps = ArrayList<Stomp>()
            if (if (side == "L") p.leftStartStomp else p.rightStartStomp) stomps.add(Stomp(truth.stompStartSecTrue, p.stompAmplitude * gain))
            if (if (side == "L") p.leftEndStomp else p.rightEndStomp) stomps.add(Stomp(truth.stompEndSecTrue, p.stompAmplitude * gain))
            val hasFlash = if (side == "L") p.includeLeftFlash else p.includeRightFlash
            val hasLive = if (side == "L") p.includeLeftLive else p.includeRightLive
            val reset = if (side == "L") p.leftClockResetAtSec else null
            val hz = p.flashHz
            val scale = 1 + (if (side == "L") p.leftSkewPpm else p.rightSkewPpm) * 1e-6
            val capture = SensorCapture("cap-$side", sessionId, side, if (side == "L") "AA:BB:CC:00:00:01" else "AA:BB:CC:00:00:02",
                truth.wallMs(0.35), "erased", 0, 0, "", recordFormat = 3)

            val pieces = ArrayList<Piece>()
            pieces.add(Piece(reset ?: p.durationSec, truth.sensorSec(side, 0.3), { s -> truth.trueTimeOfSensor(side, s) }, true))
            if (reset != null) {
                val back = reset + p.reconnectAfterSec
                val bootAt = reset + truth.bootSec
                pieces.add(Piece(p.durationSec, truth.sensorSec(side, back), { s -> bootAt + s / scale }, false))
            }

            val pendingDrops = p.flashDrops.filter { it.side == side }.sortedBy { it.atSec }.toMutableList()
            val windows = p.liveBlackouts.filter { it.side == side }
            val ci = if (side == "L") p.leftConnectionIntervalMs else p.rightConnectionIntervalMs
            val phase = rngMaster.nextDouble() * ci
            var lastReceipt = Long.MIN_VALUE
            var flashIndex = 0
            var flagNext = false
            var droppedTotal = 0
            var flagCount = 0
            var accelRail = 0; var gyroRail = 0; var anyRail = 0
            var liveStored = 0
            var gapRuns = 0

            for (piece in pieces) {
                var j = 0
                var dropLeft = 0
                var lostRun = false
                var seenLive = false
                while (true) {
                    val sensorNominal = piece.sensor0 + j / hz
                    val t = piece.tOf(sensorNominal)
                    if (t > piece.tTo) break
                    // The sample is measured at t; the main loop reads it up to 2 ms later and stamps it then.
                    val sensorStamp = sensorNominal + rng.nextDouble() * 0.002
                    val us8 = floor(sensorStamp * 1e6 / 8.0).toLong() * 8L
                    val storedUs = ((us8 % WRAP_US) + WRAP_US) % WRAP_US
                    val (rollDeg, rateDps) = roll(turns, t - lag)
                    val phi = Math.toRadians(rollDeg)
                    val ring = stomp(t, stomps)
                    val ax = 0.03 + rng.nextGaussian() * 0.04
                    val ay = -0.02 + G * sin(phi) + rng.nextGaussian() * 0.04
                    val az = 0.05 + G * cos(phi) + ring + rng.nextGaussian() * 0.04
                    // Heading: rotation about the vertical, which is (0, sin phi, cos phi) in the sensor frame (zero by default).
                    val h = Math.toRadians(headingRate(turns, t - lag, p.bootHeadingPerTurnDeg))
                    val gx = Math.toRadians(rateDps) + gyroBias.first + rng.nextGaussian() * 0.004
                    val gy = gyroBias.second + ring / p.stompAmplitude + rng.nextGaussian() * 0.004 + h * sin(phi)
                    val gz = gyroBias.third + rng.nextGaussian() * 0.004 + h * cos(phi)
                    var flags = 0
                    if (rng.nextDouble() < p.accelClipShare) flags = flags or SensorSample.QUALITY_ACCEL_RAIL
                    if (rng.nextDouble() < p.gyroClipShare) flags = flags or SensorSample.QUALITY_GYRO_RAIL

                    if (piece.flash && hasFlash) {
                        if (dropLeft == 0) pendingDrops.firstOrNull { t >= it.atSec }?.let { pendingDrops.remove(it); dropLeft = it.samples }
                        if (dropLeft > 0) { dropLeft--; droppedTotal++; flagNext = true }
                        else {
                            val record = FlashRecord.decode(raw(storedUs, flags or (if (flagNext) 4 else 0), ax, ay, az, gx, gy, gz), withFlags = true)!!
                            flash.add(StoredFlash(capture, flashIndex++, record))
                            if (flagNext) flagCount++
                            flagNext = false
                            if (flags and SensorSample.QUALITY_ACCEL_RAIL != 0) accelRail++
                            if (flags and SensorSample.QUALITY_GYRO_RAIL != 0) gyroRail++
                            if (flags != 0) anyRail++
                        }
                    }

                    if (hasLive && j % 2 == 0) {
                        val lost = windows.any { t >= it.fromSec && t < it.toSec } || rng.nextDouble() < p.liveDropShare
                        if (lost) { if (seenLive) lostRun = true }
                        else {
                            if (lostRun) { gapRuns++; lostRun = false }
                            seenLive = true
                            val decoded = FlashRecord.decode(raw(storedUs, 0, ax, ay, az, gx, gy, gz), withFlags = false)!!.sample
                            // Notifications leave at the next connection event, then pass a callback with a little jitter.
                            val arrivalMs = Math.ceil(((t + 0.018) * 1000.0 + phase) / ci) * ci - phase + expo(rng, 2.0) +
                                (if (side == "R") p.rightLiveExtraDelaySec * 1000.0 else 0.0)
                            val received = maxOf(lastReceipt, p.wallStartMs + arrivalMs.roundToLong())
                            lastReceipt = received
                            live.add(StoredLive(side, received, decoded.copy(sequence = (j / 2) and 0xffff, quality = flags)))
                            liveStored++
                        }
                    }
                    j++
                }
            }
            if (hasFlash) captures.add(capture.copy(expected = flashIndex, dropped = droppedTotal))
            truth.flashCount[side] = if (hasFlash) flashIndex else 0
            truth.liveCount[side] = liveStored
            truth.flashDroppedSamples[side] = droppedTotal
            truth.flashDroppedFlags[side] = flagCount
            truth.flashAccelRail[side] = accelRail
            truth.flashGyroRail[side] = gyroRail
            truth.flashAnyRail[side] = anyRail
            truth.liveGapRuns[side] = gapRuns
        }

        val clockPairs = if (p.includePings) pings(p, truth, sides) else emptyList()
        val mag = if (p.includeMagnetometer) magnetometer(p, truth) else emptyList()
        val phone = if (!p.includePhone) emptyList() else p.torso?.let { torsoRows(p, truth, turns, it) } ?: phoneRows(p, truth, turns)
        truth.magRows = mag.size
        truth.phoneRows = phone.size

        val events = ArrayList<Pair<Long, String>>()
        events.add(truth.wallMs(0.0) to "clock_ref wall_ms=${truth.wallMs(0.0)} elapsed_ns=${truth.phoneNs(0.0)}")
        if (p.includeLeftFlash) events.add(truth.wallMs(0.35) to "L sensor flash recording started")
        if (p.includeRightBoot && p.includeRightFlash) events.add(truth.wallMs(0.4) to "R sensor flash recording started")
        if (p.includePhone && p.phoneClockCheck) {
            val rng = Random(p.seed + 99)
            val deltas = List(PhoneMotionExport.CLOCK_CHECK_EVENTS) { -((200_000 + rng.nextInt(3_800_000)).toLong()) }
            truth.phoneClockCheckOffsetMs = deltas.max() / 1e6
            events.add(truth.wallMs(0.5) to PhoneMotionExport.clockCheckEvent(deltas))
        } else if (!p.includePhone) events.add(truth.wallMs(0.5) to "phone_motion unavailable")
        p.leftClockResetAtSec?.let { events.add(truth.wallMs(it) to "L boot disconnected; live samples unavailable until reconnect") }
        events.sortBy { it.first }

        val health = ArrayList<SensorHealth>()
        var ts = 5.0
        while (ts < p.durationSec) {
            for (side in sides) health.add(SensorHealth(side, truth.wallMs(ts), if (ts < 90) 87 else 86, truth.wallMs(ts - 3), -60 - (ts.toInt() % 7), truth.wallMs(ts - 1)))
            ts += 5.0
        }

        val calibrationJson = org.json.JSONObject().apply {
            sides.forEach { side ->
                put(side, org.json.JSONObject().put("time_ms", truth.wallMs(3.0).toDouble())
                    .put("axis", 0).put("sign", 1)
                    .put("gravity", org.json.JSONArray(listOf(0.0, 0.0, 1.0)))
                    .put("bias", org.json.JSONArray(listOf(gyroBias.first, gyroBias.second, gyroBias.third)))
                    .put("forward", org.json.JSONObject.NULL))
            }
        }.toString()

        val session = SkiSession(sessionId, truth.wallMs(0.0), truth.wallMs(p.durationSec),
            (truth.liveCount["L"] ?: 0).toLong(), (truth.liveCount["R"] ?: 0).toLong(), null, null,
            title = "Garden dress rehearsal (synthetic)", notes = "Synthetic known-truth session built by SyntheticGardenSession.",
            calibrationJson = calibrationJson, origin = "sensor")
        return GardenSession(SessionData(session, live.sortedBy { it.receivedAtMs }, flash, captures, events, health = health,
            clockPairs = clockPairs, mag = mag, phone = phone), truth)
    }

    private fun pings(p: GardenParams, truth: GardenTruth, sides: List<String>): List<StoredClockPair> {
        val out = ArrayList<StoredClockPair>()
        val reset = p.leftClockResetAtSec
        for (side in sides) {
            val rng = Random(p.seed + 500 + if (side == "L") 0 else 1)
            val ci = (if (side == "L") p.leftConnectionIntervalMs else p.rightConnectionIntervalMs) / 1000.0
            val shift = if (side == "L") 0.0 else 0.02
            val times = ArrayList<Double>()
            for (i in 0 until 8) times.add(-20.0 + 0.1 * i + shift)          // connect burst
            for (i in 0 until 8) times.add(0.5 + 0.1 * i + shift)            // recording-start burst
            if (reset != null) for (i in 0 until 8) times.add(reset + p.reconnectAfterSec + 0.5 + 0.1 * i + shift)
            var m = 60.0
            while (m < p.durationSec) { times.add(m + shift + 0.03); m += 60.0 }   // one a minute
            var token = 0
            for (t in times.sorted()) {
                if (side == "L" && reset != null && t >= reset && t < reset + p.reconnectAfterSec) continue
                val dOut: Double
                val dRet: Double
                if (p.asymmetricPingBias) {
                    dOut = 0.001 + rng.nextDouble() * ci                       // wait for the next connection event
                    dRet = ci + 0.002 + rng.nextGaussian() * 0.0005            // the notification leaves one interval later
                } else {
                    dOut = 0.004 + expo(rng, 0.006)
                    dRet = 0.004 + expo(rng, 0.006)
                }
                // An occasional slow callback inflates one leg; min-RTT filtering is meant to discard these.
                val ret = dRet + if (rng.nextDouble() < 0.03) 0.3 + 0.5 * rng.nextDouble() else 0.0
                truth.pings.getOrPut(side) { ArrayList() }.add(Triple(t, dOut, ret))
                val send = truth.phoneNs(t)
                val recv = send + ((dOut + ret) * 1e9).roundToLong()
                val sensorUs = (truth.sensorSec(side, t + dOut) * 1e6).roundToLong()
                out.add(StoredClockPair(side, token++ and 0xffff, send, recv, sensorUs, recv - send, true))
            }
        }
        return out
    }

    private fun magnetometer(p: GardenParams, truth: GardenTruth): List<StoredMag> {
        val rng = Random(p.seed + 700)
        val reset = p.leftClockResetAtSec
        val out = ArrayList<StoredMag>()
        val counts = MagnetometerFrame.COUNTS_PER_GAUSS
        val hard = truth.hardIronGauss
        var i = 0
        while (true) {
            val t = 0.05 + 0.1 * i + (rng.nextDouble() - 0.5) * 0.002
            i++
            if (t >= p.durationSec) break
            if (reset != null && t >= reset && t < reset + p.reconnectAfterSec) continue
            val theta = 2 * PI * t / truth.magPeriodSec
            val x = hard.first + truth.magHorizontalGauss * cos(theta) + rng.nextGaussian() * 0.003
            val y = hard.second + truth.magHorizontalGauss * sin(theta) + rng.nextGaussian() * 0.003
            val z = hard.third + truth.magVerticalGauss + rng.nextGaussian() * 0.003
            val sensorMs = Math.floorMod((truth.sensorSec("L", t) * 1000).roundToLong(), 4_294_967_296L)
            out.add(StoredMag("L", sensorMs, truth.wallMs(t) + 25 + rng.nextInt(10),
                (x * counts).roundToLong().toInt(), (y * counts).roundToLong().toInt(), (z * counts).roundToLong().toInt(), 0))
        }
        return out
    }

    private fun phoneRows(p: GardenParams, truth: GardenTruth, turns: List<GardenTurn>): List<StoredPhone> {
        val rng = Random(p.seed + 800)
        val out = ArrayList<StoredPhone>()
        var yaw = 0.0
        var i = 0
        while (true) {
            val t = i * 0.01 + rng.nextGaussian() * 0.0002
            i++
            if (t >= p.durationSec) break
            // The torso sways a little against each boot half-turn (a fraction of the boot's roll rate, about the vertical).
            val (_, rate) = roll(turns, t)
            val gz = -0.04 * Math.toRadians(rate) + rng.nextGaussian() * 0.01
            yaw += gz * 0.01
            out.add(StoredPhone(truth.phoneNs(t), (rng.nextGaussian() * 0.01).toFloat(), (rng.nextGaussian() * 0.01).toFloat(), gz.toFloat(),
                (0.3 + rng.nextGaussian() * 0.05).toFloat(), (rng.nextGaussian() * 0.05).toFloat(), (G + rng.nextGaussian() * 0.05).toFloat(),
                cos(yaw / 2).toFloat(), 0f, 0f, sin(yaw / 2).toFloat()))
        }
        return out
    }

    private fun torsoRows(p: GardenParams, truth: GardenTruth, turns: List<GardenTurn>, m: GardenTorso): List<StoredPhone> {
        val rng = Random(p.seed + 900)
        val out = ArrayList<StoredPhone>()
        val up = Vector3(0.0, 0.0, 1.0)
        var heading = 0.0
        var i = 0
        while (true) {
            val t = i * 0.01 + rng.nextGaussian() * 0.0002
            i++
            if (t >= p.durationSec) break
            val (rollDeg, rateDps) = roll(turns, t)
            val hRate = Math.toRadians(m.yawGain * headingRate(turns, t, p.bootHeadingPerTurnDeg))
            heading += hRate * 0.01
            val yawQ = GardenQuat.axis(up, m.gameYawRad + heading)
            val torso = yawQ * GardenQuat.axis(Vector3(1.0, 0.0, 0.0), Math.toRadians(m.leanGain * rollDeg))
            val q = torso * m.pocket
            val noiseW = torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * m.gyroNoiseRadps)
            val noiseA = torso.rotate(Vector3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * m.accelNoiseMps2)
            val w = up * hRate + yawQ.rotate(Vector3(1.0, 0.0, 0.0)) * Math.toRadians(m.leanGain * rateDps) + noiseW
            val gyro = q.conj().rotate(w)
            val acc = q.conj().rotate(Vector3(0.0, 0.0, G) + noiseA)
            out.add(StoredPhone(truth.phoneNs(t), gyro.x.toFloat(), gyro.y.toFloat(), gyro.z.toFloat(), acc.x.toFloat(), acc.y.toFloat(), acc.z.toFloat(),
                q.w.toFloat(), q.x.toFloat(), q.y.toFloat(), q.z.toFloat()))
        }
        return out
    }
}
