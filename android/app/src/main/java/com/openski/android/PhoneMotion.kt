package com.openski.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.Locale

/** One phone row as stored: gyroscope (rad/s), accelerometer (m/s^2, with gravity) and game rotation quaternion. */
data class StoredPhone(val phoneNs: Long, val gx: Float, val gy: Float, val gz: Float,
        val ax: Float, val ay: Float, val az: Float, val qw: Float, val qx: Float, val qy: Float, val qz: Float)

/**
 * Pure row assembly for the phone's own motion sensors (no Android types, JVM-tested).
 *
 * One row is produced per gyroscope event. The accelerometer and the game rotation vector arrive as separate
 * event streams, so each row holds the most recent value of each ("hold latest"). Limits: the held values can be
 * up to one sensor period old (about 10 ms at 100 Hz) or more if a stream runs slower than the gyroscope, so the
 * accelerometer and quaternion are not sample-aligned with the gyroscope; their own event times are not kept.
 * Gyroscope events before both other streams have delivered once are dropped, because there is nothing to hold.
 */
class PhoneMotionAssembler {
    private var accel: FloatArray? = null
    private var quat: FloatArray? = null
    var firstEventNs: Long? = null
        private set

    fun onAccelerometer(x: Float, y: Float, z: Float) { accel = floatArrayOf(x, y, z) }

    /** Android's rotation-vector order is x, y, z, w; the stored order is w, x, y, z. */
    fun onRotation(x: Float, y: Float, z: Float, w: Float) { quat = floatArrayOf(w, x, y, z) }

    fun onGyroscope(timestampNs: Long, x: Float, y: Float, z: Float): StoredPhone? {
        val a = accel ?: return null
        val q = quat ?: return null
        if (firstEventNs == null) firstEventNs = timestampNs
        return StoredPhone(timestampNs, x, y, z, a[0], a[1], a[2], q[0], q[1], q[2], q[3])
    }
}

/** Thread-safe hand-off between the sensor thread and the service, which drains it on its flush tick. */
class PhoneMotionBuffer {
    private val rows = ArrayList<StoredPhone>()
    @Synchronized fun add(row: StoredPhone) { rows.add(row) }
    @Synchronized fun drain(): List<StoredPhone> { val out = ArrayList(rows); rows.clear(); return out }
    /** Puts rows back at the front after a failed write. */
    @Synchronized fun restore(batch: List<StoredPhone>) { rows.addAll(0, batch) }
}

object PhoneMotionExport {
    const val COLUMNS = "phone_ns,session_elapsed_ms,gyro_x_radps,gyro_y_radps,gyro_z_radps,accel_x_mps2,accel_y_mps2,accel_z_mps2,quat_w,quat_x,quat_y,quat_z\n"

    private fun ms(v: Double) = "%.3f".format(Locale.ROOT, v)

    /** Event line written once at the first sensor event: the sensor timestamp base against elapsedRealtimeNanos. */
    fun clockCheckEvent(sensorEventNs: Long, elapsedRealtimeNs: Long) =
        "phone_clock_check offset_ms=${ms((sensorEventNs - elapsedRealtimeNs) / 1e6)}"

    /** Pulls the offset back out of the session events; null when the line is missing (older sessions, or the switch was off). */
    fun clockOffsetMs(events: List<Pair<Long, String>>): Double? = events.firstNotNullOfOrNull { (_, m) ->
        if (m.startsWith("phone_clock_check offset_ms=")) m.substringAfter("offset_ms=").trim().toDoubleOrNull() else null
    }

    /**
     * [clockRefElapsedNs] is the recording-start elapsedRealtimeNanos from the `clock_ref` event, used only for the
     * elapsed column (blank when it or the clock check is unknown). Raw values are written as stored.
     */
    fun csv(rows: List<StoredPhone>, events: List<Pair<Long, String>>, clockRefElapsedNs: Long?): String = buildString {
        val offset = clockOffsetMs(events)
        append("# Phone motion sensors (gyroscope, accelerometer, game rotation vector) from the phone carried during the session. Values are uncalibrated phone sensors.\n")
        append("# phone_ns is SensorEvent.timestamp. Clock base check: ")
        append(if (offset == null) "no phone_clock_check event was recorded, so the base is unknown.\n"
            else "offset_ms=${ms(offset)} (first event timestamp minus elapsedRealtimeNanos; near zero means the sensor clock shares the elapsedRealtimeNanos base used by clock-sync.csv).\n")
        append("# One row per gyroscope event; accelerometer and quaternion hold the latest value seen (not sample-aligned).\n")
        append(COLUMNS)
        rows.forEach { r ->
            val elapsed = if (clockRefElapsedNs == null || offset == null) "" else ms((r.phoneNs - (offset * 1e6).toLong() - clockRefElapsedNs) / 1e6)
            append("${r.phoneNs},$elapsed,${r.gx},${r.gy},${r.gz},${r.ax},${r.ay},${r.az},${r.qw},${r.qx},${r.qy},${r.qz}\n")
        }
    }
}

/** The skier's switch for recording phone motion. On by default; there is no screen for it yet, so it is a stored flag. */
class PhoneMotionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openski-phone-motion", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
}

/**
 * Registers the phone's gyroscope, accelerometer and game rotation vector at about 100 Hz (no magnetometer) on a
 * background thread. At or below 200 Hz no HIGH_SAMPLING_RATE_SENSORS permission is needed. Rows go into [buffer];
 * the first row also reports the clock base check through [onClockCheck] (called on the sensor thread).
 */
class PhoneMotionRecorder(context: Context, val buffer: PhoneMotionBuffer, private val onClockCheck: (String) -> Unit) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val assembler = PhoneMotionAssembler()
    private var thread: HandlerThread? = null
    private var checked = false

    /** Returns false when the phone lacks a gyroscope, accelerometer or game rotation vector. */
    fun start(): Boolean {
        if (thread != null) return true
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rot = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gyro == null || accel == null || rot == null) return false
        val t = HandlerThread("phone-motion").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        // Accelerometer and rotation first so the first gyroscope event already has values to hold.
        listOf(accel, rot, gyro).forEach { manager.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
        return true
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> assembler.onAccelerometer(v[0], v[1], v[2])
            Sensor.TYPE_GAME_ROTATION_VECTOR -> if (v.size >= 4) assembler.onRotation(v[0], v[1], v[2], v[3])
            Sensor.TYPE_GYROSCOPE -> assembler.onGyroscope(event.timestamp, v[0], v[1], v[2])?.let { row ->
                if (!checked) { checked = true; onClockCheck(PhoneMotionExport.clockCheckEvent(event.timestamp, SystemClock.elapsedRealtimeNanos())) }
                buffer.add(row)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object { const val SAMPLING_PERIOD_US = 10_000 }
}
