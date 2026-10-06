package com.openski.android

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** One still capture of raw sensor data, summarised for bench checks. Units: m/s² and °/s. */
data class BenchCapture(
    val samples: Int,
    val seconds: Double,
    val effectiveHz: Double?,
    val gaps: Int,
    val accel: Vector3,
    val accelNoise: Double,
    val gyroDps: Vector3,
    val gyroNoiseDps: Double,
) {
    val magnitude get() = accel.norm()
    /** Steady enough to trust as a static reading. */
    val steady get() = samples >= 30 && gyroNoiseDps < STEADY_GYRO_DPS && accelNoise < STEADY_ACCEL
    companion object { const val STEADY_GYRO_DPS = 1.5; const val STEADY_ACCEL = 0.15 }
}

/** A face of the board resting up: [axis] 0..2 is X, Y, Z and [sign] says which end points up. */
data class Face(val axis: Int, val sign: Int) {
    val label get() = "${if (sign > 0) "+" else "−"}${"XYZ"[axis]} up"
    companion object { val all = (0..2).flatMap { axis -> listOf(1, -1).map { Face(axis, it) } } }
}

data class AxisCheck(val axis: Int, val offset: Double, val scalePercent: Double)

object BenchMath {
    const val GRAVITY = 9.80665

    /** Summarise samples collected as (sensorTimeMs, sample). Returns null with fewer than two samples. */
    fun summarize(samples: List<SensorSample>): BenchCapture? {
        if (samples.size < 2) return null
        val n = samples.size.toDouble()
        val ax = samples.map { it.accelX.toDouble() }; val ay = samples.map { it.accelY.toDouble() }; val az = samples.map { it.accelZ.toDouble() }
        val gx = samples.map { Math.toDegrees(it.gyroX.toDouble()) }; val gy = samples.map { Math.toDegrees(it.gyroY.toDouble()) }
        val gz = samples.map { Math.toDegrees(it.gyroZ.toDouble()) }
        fun mean(v: List<Double>) = v.sum() / n
        fun rms(vararg v: List<Double>) = sqrt(v.sumOf { values ->
            val m = mean(values); values.sumOf { (it - m) * (it - m) } / n }.let { it })
        // Sensor timestamps are 32-bit milliseconds; differences survive wrap-around.
        val deltas = samples.zipWithNext { a, b -> ((b.timestampMs - a.timestampMs) and 0xffff_ffffL).toDouble() }
        val span = deltas.sum()
        return BenchCapture(samples.size, span / 1000, if (span > 0) (samples.size - 1) * 1000 / span else null,
            deltas.count { it > 100 }, Vector3(mean(ax), mean(ay), mean(az)), rms(ax, ay, az),
            Vector3(mean(gx), mean(gy), mean(gz)), rms(gx, gy, gz))
    }

    /** The direction the holes edge points, from a reading taken standing the board on that edge (so it points down). */
    fun holesDirection(accelWhenStandingOnHoles: Vector3): Face = face(accelWhenStandingOnHoles).let { Face(it.axis, -it.sign) }

    /** Which face is up, judged by the strongest axis of the gravity reading. */
    fun face(accel: Vector3): Face {
        val parts = listOf(accel.x, accel.y, accel.z)
        val axis = parts.indices.maxBy { abs(parts[it]) }
        return Face(axis, if (parts[axis] >= 0) 1 else -1)
    }

    /** Angle in degrees between two gravity readings. Always positive. */
    fun angleBetween(a: Vector3, b: Vector3): Double {
        val denominator = a.norm() * b.norm()
        if (denominator < 1e-9) return 0.0
        return Math.toDegrees(acos((a.dot(b) / denominator).coerceIn(-1.0, 1.0)))
    }

    /** Angle in degrees between the reading and the axis of [face]; zero when it sits perfectly on that face. */
    fun offFace(accel: Vector3, face: Face): Double {
        val unit = listOf(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))[face.axis] * face.sign.toDouble()
        return angleBetween(accel, unit)
    }

    /** Offset and scale error per axis from the +up and −up reading of each axis. Needs both faces for an axis. */
    fun axisChecks(readings: Map<Face, Vector3>): List<AxisCheck> = (0..2).mapNotNull { axis ->
        val plus = readings[Face(axis, 1)]?.let { component(it, axis) } ?: return@mapNotNull null
        val minus = readings[Face(axis, -1)]?.let { component(it, axis) } ?: return@mapNotNull null
        AxisCheck(axis, (plus + minus) / 2, ((plus - minus) / 2 / GRAVITY - 1) * 100)
    }

    private fun component(v: Vector3, axis: Int) = when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }
}
